package dev.guber.markdownviewer

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class RecentDocument(
    val uriString: String,
    val title: String,
    val lastOpenedAt: Long,
    val lastScrollY: Int? = null,
)

interface DocumentStatePersistence {
    fun loadRecentDocuments(): List<RecentDocument>
    fun saveRecentDocuments(documents: List<RecentDocument>)
    fun loadReadingPosition(uriString: String): Int?
    fun saveReadingPosition(uriString: String, scrollY: Int)
    fun clearAll()
}

class InMemoryDocumentStatePersistence : DocumentStatePersistence {
    private var recentDocuments: List<RecentDocument> = emptyList()
    private val readingPositions = linkedMapOf<String, Int>()

    override fun loadRecentDocuments(): List<RecentDocument> = recentDocuments

    override fun saveRecentDocuments(documents: List<RecentDocument>) {
        recentDocuments = documents
    }

    override fun loadReadingPosition(uriString: String): Int? = readingPositions[uriString]

    override fun saveReadingPosition(uriString: String, scrollY: Int) {
        readingPositions[uriString] = scrollY
        recentDocuments = recentDocuments.map {
            if (it.uriString == uriString) it.copy(lastScrollY = scrollY) else it
        }
    }

    override fun clearAll() {
        recentDocuments = emptyList()
        readingPositions.clear()
    }
}

class SharedPrefsDocumentStatePersistence(
    private val prefs: SharedPreferences,
) : DocumentStatePersistence {
    override fun loadRecentDocuments(): List<RecentDocument> {
        val raw = prefs.getString(KEY_RECENT_DOCS_JSON, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val obj = array.getJSONObject(index)
                    add(
                        RecentDocument(
                            uriString = obj.getString("uriString"),
                            title = obj.getString("title"),
                            lastOpenedAt = obj.optLong("lastOpenedAt", 0L),
                            lastScrollY = if (obj.has("lastScrollY") && !obj.isNull("lastScrollY")) obj.getInt("lastScrollY") else null,
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    override fun saveRecentDocuments(documents: List<RecentDocument>) {
        val array = JSONArray()
        documents.forEach { doc ->
            array.put(
                JSONObject().apply {
                    put("uriString", doc.uriString)
                    put("title", doc.title)
                    put("lastOpenedAt", doc.lastOpenedAt)
                    if (doc.lastScrollY != null) put("lastScrollY", doc.lastScrollY) else put("lastScrollY", JSONObject.NULL)
                }
            )
        }
        prefs.edit().putString(KEY_RECENT_DOCS_JSON, array.toString()).apply()
    }

    override fun loadReadingPosition(uriString: String): Int? {
        if (!prefs.contains(positionKey(uriString))) return null
        return prefs.getInt(positionKey(uriString), 0)
    }

    override fun saveReadingPosition(uriString: String, scrollY: Int) {
        prefs.edit().putInt(positionKey(uriString), scrollY).apply()
        val updated = loadRecentDocuments().map {
            if (it.uriString == uriString) it.copy(lastScrollY = scrollY) else it
        }
        saveRecentDocuments(updated)
    }

    override fun clearAll() {
        val editor = prefs.edit()
        loadRecentDocuments().forEach { doc ->
            editor.remove(positionKey(doc.uriString))
        }
        editor.remove(KEY_RECENT_DOCS_JSON)
        editor.apply()
    }

    private fun positionKey(uriString: String): String = "doc_scroll_${uriString.hashCode()}"

    companion object {
        private const val KEY_RECENT_DOCS_JSON = "recent_documents_json"
    }
}

class DocumentStateStore(
    private val persistence: DocumentStatePersistence,
    private val maxRecentDocuments: Int = 12,
) {
    fun recordOpen(uriString: String, title: String, openedAt: Long = System.currentTimeMillis()) {
        val existing = persistence.loadRecentDocuments()
        val updated = listOf(
            RecentDocument(
                uriString = uriString,
                title = title,
                lastOpenedAt = openedAt,
                lastScrollY = persistence.loadReadingPosition(uriString),
            )
        ) + existing.filterNot { it.uriString == uriString }

        persistence.saveRecentDocuments(updated.take(maxRecentDocuments))
    }

    fun getRecentDocuments(): List<RecentDocument> = persistence.loadRecentDocuments()

    fun getMostRecentDocument(): RecentDocument? = persistence.loadRecentDocuments().firstOrNull()

    fun saveReadingPosition(uriString: String, scrollY: Int) {
        persistence.saveReadingPosition(uriString, scrollY)
    }

    fun getReadingPosition(uriString: String): Int? = persistence.loadReadingPosition(uriString)

    fun clearAll() {
        persistence.clearAll()
    }
}
