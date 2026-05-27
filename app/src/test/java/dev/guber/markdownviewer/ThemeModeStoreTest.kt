package dev.guber.markdownviewer

import androidx.appcompat.app.AppCompatDelegate
import kotlin.test.Test
import kotlin.test.assertEquals

class ThemeModeStoreTest {

    @Test
    fun `system mode maps to follow system night mode`() {
        assertEquals(
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
            ThemeModeStore.toNightMode(ThemeMode.SYSTEM)
        )
    }

    @Test
    fun `dark mode maps to yes night mode`() {
        assertEquals(
            AppCompatDelegate.MODE_NIGHT_YES,
            ThemeModeStore.toNightMode(ThemeMode.DARK)
        )
    }

    @Test
    fun `light mode maps to no night mode`() {
        assertEquals(
            AppCompatDelegate.MODE_NIGHT_NO,
            ThemeModeStore.toNightMode(ThemeMode.LIGHT)
        )
    }

    @Test
    fun `in-memory persistence stores selected mode`() {
        val persistence = InMemoryThemeModePersistence()
        val store = ThemeModeStore(persistence)

        store.save(ThemeMode.DARK)

        assertEquals(ThemeMode.DARK, store.load())
    }

    @Test
    fun `missing persistence falls back to system`() {
        val store = ThemeModeStore(InMemoryThemeModePersistence())
        assertEquals(ThemeMode.SYSTEM, store.load())
    }
}
