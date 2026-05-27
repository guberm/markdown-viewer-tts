package dev.guber.markdownviewer

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.text.method.LinkMovementMethod
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.chip.Chip
import dev.guber.markdownviewer.databinding.ActivityMainBinding
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.linkify.LinkifyPlugin
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale
import java.util.UUID
import java.util.regex.Pattern

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var markwon: Markwon
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var documentStateStore: DocumentStateStore
    private var tts: TextToSpeech? = null
    private var currentText: String = SAMPLE_MARKDOWN
    private var currentTitle: String = "Sample.md"
    private var currentTags: List<String> = emptyList()
    private var selectedTag: String? = null
    private var ttsReady = false
    private var currentDocumentUriString: String? = null
    private var suppressScrollPersistence = false

    private enum class SpeechScript {
        CYRILLIC,
        LATIN,
        MIXED,
        UNKNOWN
    }

    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            openUri(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("markdown_viewer_prefs", MODE_PRIVATE)
        documentStateStore = DocumentStateStore(SharedPrefsDocumentStatePersistence(prefs))
        markwon = Markwon.builder(this)
            .usePlugin(TablePlugin.create(this))
            .usePlugin(HtmlPlugin.create())
            .usePlugin(LinkifyPlugin.create())
            .build()
        tts = TextToSpeech(this, this)

        setupToolbarAndDrawer()
        applyWindowInsets()
        setupControls()
        handleIncomingIntent(intent)
        if (intent?.data == null && intent?.action != Intent.ACTION_SEND) {
            renderMarkdown(SAMPLE_MARKDOWN, "Sample.md", null)
        }
        renderRecentDocumentsSummary()
        updateResumeUi()
    }

    override fun onPause() {
        persistCurrentReadingPosition()
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_open -> {
                openDocument.launch(arrayOf("text/plain", "text/*", "application/octet-stream"))
                true
            }
            R.id.action_read_aloud -> {
                speakCurrent()
                true
            }
            R.id.action_stop_reading -> {
                tts?.stop()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            ttsReady = true
            setBestTtsLanguageForText(currentText)
            updateSpeechRate()
        } else {
            ttsReady = false
        }
    }

    override fun onDestroy() {
        persistCurrentReadingPosition()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    private fun setupToolbarAndDrawer() {
        setSupportActionBar(binding.toolbar)
        val toggle = ActionBarDrawerToggle(
            this,
            binding.drawerLayout,
            binding.toolbar,
            R.string.app_name,
            R.string.app_name
        )
        binding.drawerLayout.addDrawerListener(toggle)
        toggle.syncState()
    }

    private fun applyWindowInsets() {
        val toolbarBasePaddingTop = binding.toolbar.paddingTop
        val drawerBasePaddingTop = binding.settingsDrawer.paddingTop

        ViewCompat.setOnApplyWindowInsetsListener(binding.toolbar) { view, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            view.setPadding(
                view.paddingLeft,
                WindowInsetUi.adjustTopPadding(toolbarBasePaddingTop, statusBars.top),
                view.paddingRight,
                view.paddingBottom,
            )
            insets
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.settingsDrawer) { view, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            view.setPadding(
                view.paddingLeft,
                WindowInsetUi.adjustTopPadding(drawerBasePaddingTop, statusBars.top),
                view.paddingRight,
                view.paddingBottom,
            )
            insets
        }

        ViewCompat.requestApplyInsets(binding.drawerLayout)
    }

    private fun setupControls() {
        val fontOptions = listOf("Sans", "Serif", "Monospace")
        binding.fontSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, fontOptions)
        val savedFont = prefs.getString("font_family", "Sans") ?: "Sans"
        binding.fontSpinner.setSelection(fontOptions.indexOf(savedFont).coerceAtLeast(0))
        binding.fontSpinner.setOnItemSelectedListener(SimpleItemSelectedListener { position ->
            prefs.edit { putString("font_family", fontOptions[position]) }
            renderFilteredMarkdown()
        })

        val fontSize = prefs.getInt("font_size", 18)
        binding.fontSeek.progress = (fontSize - 12).coerceIn(0, 18)
        binding.fontSeek.setOnSeekBarChangeListener(SimpleSeekBarListener { progress ->
            val size = progress + 12
            prefs.edit { putInt("font_size", size) }
            binding.fontSizeLabel.text = "Font size: ${size}sp"
            renderFilteredMarkdown()
        })
        binding.fontSizeLabel.text = "Font size: ${fontSize}sp"

        val speechProgress = prefs.getInt("speech_rate_progress", 5)
        binding.speechSeek.progress = speechProgress
        binding.speechSeek.setOnSeekBarChangeListener(SimpleSeekBarListener { progress ->
            prefs.edit { putInt("speech_rate_progress", progress) }
            val rate = progressToSpeechRate(progress)
            binding.speechRateLabel.text = "Speech rate: ${String.format(Locale.US, "%.2f", rate)}"
            updateSpeechRate()
        })
        binding.speechRateLabel.text = "Speech rate: ${String.format(Locale.US, "%.2f", progressToSpeechRate(speechProgress))}"

        binding.openFileButton.setOnClickListener {
            openDocument.launch(arrayOf("text/plain", "text/*", "application/octet-stream"))
        }
        binding.primaryOpenButton.setOnClickListener {
            openDocument.launch(arrayOf("text/plain", "text/*", "application/octet-stream"))
        }
        binding.readButton.setOnClickListener {
            speakCurrent()
        }
        binding.primaryTtsButton.setOnClickListener {
            speakCurrent()
        }
        binding.resumeReadingButton.setOnClickListener {
            resumeReadingPosition()
        }
        binding.reopenLastButton.setOnClickListener {
            reopenLastDocument()
        }
        binding.clearHistoryButton.setOnClickListener {
            documentStateStore.clearAll()
            renderRecentDocumentsSummary()
            updateResumeUi()
            Toast.makeText(this, "History cleared", Toast.LENGTH_SHORT).show()
        }

        binding.contentScrollView.setOnScrollChangeListener { _: View, _: Int, scrollY: Int, _: Int, _: Int ->
            if (!suppressScrollPersistence) {
                currentDocumentUriString?.let { uri ->
                    documentStateStore.saveReadingPosition(uri, scrollY)
                    updateResumeUi()
                    renderRecentDocumentsSummary()
                }
            }
        }

        binding.contentView.movementMethod = LinkMovementMethod.getInstance()
        binding.contentView.linksClickable = true
        applyFontPrefs()
    }

    private fun applyFontPrefs() {
        val size = prefs.getInt("font_size", 18).toFloat()
        val family = prefs.getString("font_family", "Sans") ?: "Sans"
        val typeface = when (family) {
            "Serif" -> Typeface.SERIF
            "Monospace" -> Typeface.MONOSPACE
            else -> Typeface.SANS_SERIF
        }
        binding.contentView.textSize = size
        binding.contentView.typeface = typeface
    }

    private fun updateSpeechRate() {
        tts?.setSpeechRate(progressToSpeechRate(prefs.getInt("speech_rate_progress", 5)))
    }

    private fun progressToSpeechRate(progress: Int): Float = 0.4f + (progress * 0.05f)

    private fun handleIncomingIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data?.let { openUri(it) }
            Intent.ACTION_SEND -> {
                val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (!sharedText.isNullOrBlank()) {
                    renderMarkdown(sharedText, "Shared text", null)
                }
            }
        }
    }

    private fun openUri(uri: Uri) {
        runCatching {
            val title = queryDisplayName(uri) ?: "Opened.md"
            val text = contentResolver.openInputStream(uri)?.use { input ->
                BufferedReader(InputStreamReader(input)).readText()
            } ?: error("Unable to read file")
            documentStateStore.recordOpen(uri.toString(), title)
            renderMarkdown(text, title, uri.toString())
        }.onFailure {
            Toast.makeText(this, "Failed to open file: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return cursor.getString(idx)
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun renderMarkdown(markdown: String, title: String, uriString: String?) {
        currentText = markdown
        currentTitle = title
        currentDocumentUriString = uriString
        binding.fileNameText.text = title
        currentTags = extractTags(markdown)
        renderTagChips(currentTags)
        if (ttsReady) {
            setBestTtsLanguageForText(markdown)
        }
        renderFilteredMarkdown()
        if (uriString != null) {
            restoreReadingPosition(uriString)
        } else {
            binding.contentScrollView.post { binding.contentScrollView.scrollTo(0, 0) }
        }
        renderRecentDocumentsSummary()
        updateResumeUi()
    }

    private fun renderFilteredMarkdown() {
        val filtered = if (selectedTag.isNullOrBlank()) currentText else filterByTag(currentText, selectedTag!!)
        applyFontPrefs()
        markwon.setMarkdown(binding.contentView, filtered)
        binding.contentView.movementMethod = LinkMovementMethod.getInstance()
        binding.contentView.linksClickable = true
    }

    private fun restoreReadingPosition(uriString: String) {
        val savedPosition = documentStateStore.getReadingPosition(uriString) ?: 0
        suppressScrollPersistence = true
        binding.contentScrollView.post {
            binding.contentScrollView.scrollTo(0, savedPosition)
            binding.contentScrollView.post {
                suppressScrollPersistence = false
            }
        }
    }

    private fun resumeReadingPosition() {
        val uriString = currentDocumentUriString ?: return
        val savedPosition = documentStateStore.getReadingPosition(uriString) ?: return
        binding.contentScrollView.post {
            binding.contentScrollView.scrollTo(0, savedPosition)
            Toast.makeText(this, "Resumed reading position", Toast.LENGTH_SHORT).show()
        }
    }

    private fun reopenLastDocument() {
        val last = documentStateStore.getMostRecentDocument()
        if (last == null) {
            Toast.makeText(this, "No recent documents yet", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            openUri(Uri.parse(last.uriString))
        }.onFailure {
            Toast.makeText(this, "Failed to reopen last document", Toast.LENGTH_LONG).show()
        }
    }

    private fun persistCurrentReadingPosition() {
        currentDocumentUriString?.let { uri ->
            documentStateStore.saveReadingPosition(uri, binding.contentScrollView.scrollY)
        }
    }

    private fun renderRecentDocumentsSummary() {
        val recent = documentStateStore.getRecentDocuments()
        binding.recentDocsText.text = if (recent.isEmpty()) {
            "No recent documents yet"
        } else {
            recent.take(5).joinToString("\n") { doc ->
                val resumeNote = doc.lastScrollY?.let { " - resume saved" } ?: ""
                "- ${doc.title}${resumeNote}"
            }
        }
        binding.reopenLastButton.isEnabled = recent.isNotEmpty()
        binding.clearHistoryButton.isEnabled = recent.isNotEmpty()
    }

    private fun updateResumeUi() {
        val uriString = currentDocumentUriString
        val savedPosition = uriString?.let { documentStateStore.getReadingPosition(it) }
        val hasResume = savedPosition != null && savedPosition > 0
        binding.resumeReadingButton.isEnabled = hasResume
        binding.resumeStatusText.text = when {
            uriString == null -> "Resume works for opened files"
            hasResume -> "Saved reading position is available"
            else -> "No saved reading position yet"
        }
    }

    private fun renderTagChips(tags: List<String>) {
        binding.tagContainer.removeAllViews()
        addTagChip("All tags", null)
        tags.forEach { addTagChip("#$it", it) }
    }

    private fun addTagChip(label: String, tag: String?) {
        val chip = Chip(this)
        chip.text = label
        chip.isCheckable = true
        chip.isChecked = tag == selectedTag || (tag == null && selectedTag == null)
        chip.setChipBackgroundColorResource(android.R.color.transparent)
        chip.setBackgroundResource(R.drawable.tag_chip_bg)
        chip.setOnClickListener {
            selectedTag = tag
            renderTagChips(currentTags)
            renderFilteredMarkdown()
        }
        val params = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        params.marginEnd = 12
        chip.layoutParams = params
        binding.tagContainer.addView(chip)
    }

    private fun speakCurrent() {
        if (currentText.isBlank()) return
        if (!ttsReady) {
            Toast.makeText(this, "Text-to-speech is still initializing", Toast.LENGTH_SHORT).show()
            return
        }
        val spokenText = stripMarkdown(currentText)
        if (spokenText.isBlank()) return
        if (!setBestTtsLanguageForText(spokenText)) {
            Toast.makeText(this, "This TTS voice is not available on the device", Toast.LENGTH_LONG).show()
            return
        }
        updateSpeechRate()
        tts?.stop()
        val chunks = chunkForTts(spokenText)
        if (chunks.isEmpty()) return
        var hadError = false
        chunks.forEachIndexed { index, chunk ->
            val queueMode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val result = tts?.speak(chunk, queueMode, null, "markdown-viewer-${UUID.randomUUID()}")
            if (result == TextToSpeech.ERROR) {
                hadError = true
            }
        }
        if (hadError) {
            Toast.makeText(this, "Failed to start text-to-speech", Toast.LENGTH_LONG).show()
        }
    }

    private fun setBestTtsLanguageForText(text: String): Boolean {
        val engine = tts ?: return false
        val preferredLocales = buildPreferredLocales(text)

        val availableVoices = runCatching { engine.voices.orEmpty() }.getOrDefault(emptySet())
        val bestVoice = availableVoices
            .asSequence()
            .filter { voice ->
                !voice.isNetworkConnectionRequired &&
                    voice.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true &&
                    preferredLocales.any { preferred -> matchesLocale(voice.locale, preferred) }
            }
            .sortedWith(compareByDescending<Voice> { voiceQualityScore(it) }
                .thenByDescending { it.locale.country.equals("RU", ignoreCase = true) }
                .thenBy { it.name })
            .firstOrNull()

        if (bestVoice != null) {
            val localeResult = engine.setLanguage(bestVoice.locale)
            val voiceSetWorked = runCatching {
                engine.voice = bestVoice
                true
            }.getOrDefault(false)
            if (localeResult >= TextToSpeech.LANG_AVAILABLE && voiceSetWorked) {
                return true
            }
        }

        for (locale in preferredLocales) {
            val availability = engine.isLanguageAvailable(locale)
            if (availability >= TextToSpeech.LANG_AVAILABLE) {
                val setResult = engine.setLanguage(locale)
                if (setResult >= TextToSpeech.LANG_AVAILABLE) {
                    return true
                }
            }
        }
        return false
    }

    private fun buildPreferredLocales(text: String): List<Locale> = buildList {
        when (detectSpeechScript(text)) {
            SpeechScript.CYRILLIC -> {
                add(Locale("ru", "RU"))
                add(Locale("ru"))
            }
            SpeechScript.LATIN -> {
                add(Locale.US)
                add(Locale.UK)
                add(Locale.ENGLISH)
            }
            SpeechScript.MIXED -> {
                add(Locale.getDefault())
                add(Locale.US)
                add(Locale("ru", "RU"))
                add(Locale("ru"))
            }
            SpeechScript.UNKNOWN -> {
                add(Locale.getDefault())
            }
        }
        add(Locale.getDefault())
        add(Locale.US)
    }.distinct()

    private fun matchesLocale(candidate: Locale?, preferred: Locale): Boolean {
        if (candidate == null) return false
        if (!candidate.language.equals(preferred.language, ignoreCase = true)) return false
        return preferred.country.isBlank() || candidate.country.equals(preferred.country, ignoreCase = true)
    }

    private fun voiceQualityScore(voice: Voice): Int {
        var score = 0
        score += voice.quality
        score += voice.latency
        if (voice.locale.country.equals("RU", ignoreCase = true)) score += 1000
        val name = voice.name.lowercase(Locale.ROOT)
        if ("natural" in name) score += 500
        if ("premium" in name) score += 250
        if ("enhanced" in name) score += 150
        if ("local" in name) score += 100
        if ("network" in name) score -= 500
        return score
    }

    private fun detectSpeechScript(text: String): SpeechScript {
        var cyrillicCount = 0
        var latinCount = 0
        text.forEach {
            when (Character.UnicodeBlock.of(it)) {
                Character.UnicodeBlock.CYRILLIC -> cyrillicCount++
                Character.UnicodeBlock.BASIC_LATIN,
                Character.UnicodeBlock.LATIN_1_SUPPLEMENT,
                Character.UnicodeBlock.LATIN_EXTENDED_A,
                Character.UnicodeBlock.LATIN_EXTENDED_B,
                Character.UnicodeBlock.LATIN_EXTENDED_ADDITIONAL -> if (it.isLetter()) latinCount++
            }
        }
        return when {
            cyrillicCount > 0 && latinCount == 0 -> SpeechScript.CYRILLIC
            latinCount > 0 && cyrillicCount == 0 -> SpeechScript.LATIN
            latinCount > 0 && cyrillicCount > 0 -> SpeechScript.MIXED
            else -> SpeechScript.UNKNOWN
        }
    }

    private fun chunkForTts(text: String, maxChunkLength: Int = 3000): List<String> {
        val normalized = text.replace(Regex("\\s+"), " ").trim()
        if (normalized.isBlank()) return emptyList()
        if (normalized.length <= maxChunkLength) return listOf(normalized)

        val chunks = mutableListOf<String>()
        var remaining = normalized
        while (remaining.isNotBlank()) {
            if (remaining.length <= maxChunkLength) {
                chunks += remaining.trim()
                break
            }
            val candidate = remaining.substring(0, maxChunkLength)
            val splitAt = listOf(
                candidate.lastIndexOf(". "),
                candidate.lastIndexOf("! "),
                candidate.lastIndexOf("? "),
                candidate.lastIndexOf("; "),
                candidate.lastIndexOf(": "),
                candidate.lastIndexOf(", "),
                candidate.lastIndexOf(' ')
            ).firstOrNull { it >= maxChunkLength / 2 } ?: maxChunkLength

            val endIndex = if (splitAt == maxChunkLength) maxChunkLength else splitAt + 1
            val chunk = remaining.substring(0, endIndex).trim()
            if (chunk.isNotBlank()) {
                chunks += chunk
            }
            remaining = remaining.substring(endIndex).trimStart()
        }
        return chunks
    }

    private fun filterByTag(markdown: String, tag: String): String {
        val lines = markdown.lines()
        val keep = lines.filter { it.contains("#$tag") || it.contains(tag, ignoreCase = true) }
        return if (keep.isNotEmpty()) keep.joinToString("\n") else markdown
    }

    private fun stripMarkdown(markdown: String): String {
        return markdown
            .replace(Regex("```[\\s\\S]*?```"), " ")
            .replace(Regex("`([^`]+)`"), "$1")
            .replace(Regex("!\\[[^\\]]*]\\([^)]*\\)"), " ")
            .replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
            .replace(Regex("[#>*_~\\-]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun extractTags(markdown: String): List<String> {
        val tags = linkedSetOf<String>()
        val frontMatter = Regex("^---\\s*\\n([\\s\\S]*?)\\n---", RegexOption.MULTILINE).find(markdown)
        frontMatter?.groupValues?.getOrNull(1)?.let { body ->
            Regex("^tags:\\s*\\[(.*?)]\\s*$", RegexOption.MULTILINE).find(body)?.groupValues?.getOrNull(1)
                ?.split(',')
                ?.map { it.trim().trim('"', '\'', ' ') }
                ?.filter { it.isNotBlank() }
                ?.forEach { tags.add(it) }
            Regex("^tags:\\s*\\n((?:\\s*-\\s*.+\\n?)+)", RegexOption.MULTILINE).find(body)?.groupValues?.getOrNull(1)
                ?.lines()
                ?.map { it.replace(Regex("^\\s*-\\s*"), "").trim() }
                ?.filter { it.isNotBlank() }
                ?.forEach { tags.add(it) }
        }
        val pattern = Pattern.compile("(?<!\\w)#([\\p{L}\\p{N}_\\-/]+)")
        val matcher = pattern.matcher(markdown)
        while (matcher.find()) {
            matcher.group(1)?.takeIf { it.isNotBlank() }?.let(tags::add)
        }
        return tags.toList()
    }

    companion object {
        private const val SAMPLE_MARKDOWN = """
---
tags: [demo, android, markdown, tts]
---

# Native Android Markdown Viewer

This app is a **native Kotlin Android project**.

- Open `.md`, `.markdown`, and `.txt`
- Render Markdown with tables and code blocks
- Read aloud with Android TTS
- Change font family and size
- Filter by #tags

## Example

| Feature | Status |
|---|---|
| SAF file open | done |
| Open with | done |
| Font controls | done |
| TTS | done |

Inline tag examples: #android #markdown #tts
        """
    }
}
