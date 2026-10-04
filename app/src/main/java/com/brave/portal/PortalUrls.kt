package com.brave.portal

import android.net.Uri

/** Single place that decides which URLs belong to the BRAVE Portal (stay inside the app). */
object PortalUrls {
    private val hosts: Set<String> by lazy {
        val set = mutableSetOf<String>()
        Uri.parse(BuildConfig.WEBSITE_URL).host?.let { set += it.lowercase() }
        BuildConfig.EXTRA_INTERNAL_HOSTS.split(",")
            .map { it.trim().lowercase() }.filter { it.isNotEmpty() }.forEach { set += it }
        set
    }

    fun isInternal(uri: Uri): Boolean {
        val host = uri.host?.lowercase() ?: return false
        return uri.scheme.equals("https", true) && host in hosts
    }

    /** Returns the URL only if it is an https URL on a portal host; otherwise null (ignored). */
    fun safeInternalUrl(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val uri = Uri.parse(raw.trim())
        return if (isInternal(uri)) uri.toString() else null
    }
}
