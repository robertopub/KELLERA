package com.kellera.kellera03.launcher

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import java.net.URLEncoder
import android.app.SearchManager

class AppLauncher(
    private val context: Context
) {

    fun openGoogle(): Boolean {
        return openPackage("com.google.android.googlequicksearchbox")
                || openChrome()
                || openUrl("https://www.google.com")
    }

    fun openChrome(): Boolean {
        return openPackage("com.android.chrome")
    }

    fun searchGoogle(query: String): Boolean {

        if (query.isBlank()) return false

        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val searchUri =
            Uri.parse("https://www.google.com/search?q=$encodedQuery")

        return try {
            val chromeIntent = Intent(
                Intent.ACTION_VIEW,
                searchUri
            ).apply {
                setPackage("com.android.chrome")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(chromeIntent)
            true

        } catch (_: Exception) {
            try {
                val browserIntent = Intent(
                    Intent.ACTION_VIEW,
                    searchUri
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                context.startActivity(browserIntent)
                true

            } catch (_: Exception) {
                false
            }
        }
    }

    fun openYouTube(): Boolean {
        return openPackage("com.google.android.youtube")
                || openUrl("https://www.youtube.com")
    }

    fun openWhatsApp(): Boolean {
        return openPackage("com.whatsapp")
    }

    fun openSettings(): Boolean {
        return try {
            val intent = Intent(Settings.ACTION_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun openPackage(packageName: String): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun openUrl(url: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }
}