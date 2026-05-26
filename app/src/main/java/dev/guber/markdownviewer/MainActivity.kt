package dev.guber.markdownviewer

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.speech.tts.TextToSpeech
import android.text.method.LinkMovementMethod
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import com.google.android.material.chip.Chip
import dev.guber.markdownviewer.databinding.ActivityMainBinding
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.linkify.LinkifyPlugin
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale
import java.util.regex.Pattern

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var markwon: Markwon
    private lateinit var prefs: android.content.SharedPreferences
    private var tts: TextToSpeech? = null
    private var currentText: String = SAMPLE_MARKDOWN
    private var currentTitle: String = "Sample.md"
    private var currentTags: List<String> = emptyList()
    private var selectedTag: String? = null

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
        markwon = Markwon.builder(this)
            .usePlugin(TablePlugin.create(this))
            .usePlugin(HtmlPlugin.create())
            .usePlugin(LinkifyPlugin.create())
            .build()
        tts = TextToSpeech(this, this)

        setupToolbarAndDrawer()
        setupControls()
        handleIncomingIntent(intent)
        if (intent?.data == null && intent?.action != Intent.ACTION_SEND) {
            renderMarkdown(SAMPLE_MARKDOWN, "Sample.md")
        }
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
            tts?.language = Locale.US
            updateSpeechRate()
        }
    }

    override fun onDestroy() {
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
                    renderMarkdown(sharedText, "Shared text")
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
            renderMarkdown(text, title)
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

    private fun renderMarkdown(markdown: String, title: String) {
        currentText = markdown
        currentTitle = title
        binding.fileNameText.text = title
        currentTags = extractTags(markdown)
        renderTagChips(currentTags)
        renderFilteredMarkdown()
    }

    private fun renderFilteredMarkdown() {
        val filtered = if (selectedTag.isNullOrBlank()) currentText else filterByTag(currentText, selectedTag!!)
        applyFontPrefs()
        markwon.setMarkdown(binding.contentView, filtered)
        binding.contentView.movementMethod = LinkMovementMethod.getInstance()
        binding.contentView.linksClickable = true
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
        updateSpeechRate()
        tts?.stop()
        tts?.speak(stripMarkdown(currentText), TextToSpeech.QUEUE_FLUSH, null, "markdown-viewer")
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
