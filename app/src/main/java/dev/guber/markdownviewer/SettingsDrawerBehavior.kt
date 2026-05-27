package dev.guber.markdownviewer

import androidx.core.view.GravityCompat

object SettingsDrawerBehavior {
    fun drawerGravity(): Int = GravityCompat.END

    fun onNavigationClick(isOpen: Boolean): DrawerAction {
        return if (isOpen) DrawerAction.CLOSE else DrawerAction.OPEN
    }
}
