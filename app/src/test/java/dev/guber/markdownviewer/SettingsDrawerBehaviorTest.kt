package dev.guber.markdownviewer

import androidx.core.view.GravityCompat
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsDrawerBehaviorTest {

    @Test
    fun `closed end drawer should open on nav click`() {
        assertEquals(DrawerAction.OPEN, SettingsDrawerBehavior.onNavigationClick(isOpen = false))
    }

    @Test
    fun `open end drawer should close on nav click`() {
        assertEquals(DrawerAction.CLOSE, SettingsDrawerBehavior.onNavigationClick(isOpen = true))
    }

    @Test
    fun `settings drawer gravity is end`() {
        assertEquals(GravityCompat.END, SettingsDrawerBehavior.drawerGravity())
    }
}
