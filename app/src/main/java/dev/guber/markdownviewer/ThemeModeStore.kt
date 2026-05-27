package dev.guber.markdownviewer

import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate

enum class ThemeMode(val storageValue: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        fun fromStorageValue(value: String?): ThemeMode {
            return entries.firstOrNull { it.storageValue == value } ?: SYSTEM
        }
    }
}

interface ThemeModePersistence {
    fun load(): ThemeMode?
    fun save(mode: ThemeMode)
}

class InMemoryThemeModePersistence : ThemeModePersistence {
    private var current: ThemeMode? = null

    override fun load(): ThemeMode? = current

    override fun save(mode: ThemeMode) {
        current = mode
    }
}

class SharedPrefsThemeModePersistence(
    private val prefs: SharedPreferences,
) : ThemeModePersistence {
    override fun load(): ThemeMode? = ThemeMode.fromStorageValue(prefs.getString(KEY_THEME_MODE, null))

    override fun save(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.storageValue).apply()
    }

    companion object {
        private const val KEY_THEME_MODE = "theme_mode"
    }
}

class ThemeModeStore(
    private val persistence: ThemeModePersistence,
) {
    fun load(): ThemeMode = persistence.load() ?: ThemeMode.SYSTEM

    fun save(mode: ThemeMode) {
        persistence.save(mode)
    }

    companion object {
        fun toNightMode(mode: ThemeMode): Int = when (mode) {
            ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
        }
    }
}
