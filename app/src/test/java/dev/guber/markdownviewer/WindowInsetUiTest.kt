package dev.guber.markdownviewer

import kotlin.test.Test
import kotlin.test.assertEquals

class WindowInsetUiTest {

    @Test
    fun `toolbar top padding includes status bar inset`() {
        assertEquals(64, WindowInsetUi.adjustTopPadding(16, 48))
    }

    @Test
    fun `zero inset keeps original padding`() {
        assertEquals(16, WindowInsetUi.adjustTopPadding(16, 0))
    }
}
