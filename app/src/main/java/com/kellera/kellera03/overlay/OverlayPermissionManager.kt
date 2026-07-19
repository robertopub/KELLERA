package com.kellera.kellera03.overlay

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

class OverlayPermissionManager(
    private val context: Context
) {

    fun hasPermission(): Boolean {
        return Settings.canDrawOverlays(context)
    }

    fun createPermissionIntent(): Intent {
        return Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
    }
}