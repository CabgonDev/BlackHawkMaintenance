package com.cabgon.blackhawk.util

import android.content.Context
import android.util.Base64

object Prefs {
    private const val FILE = "bh_prefs"
    private const val KEY_PKG = "pkg"

    fun setPackage(ctx: Context, value: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_PKG, value)
            .apply()
    }

    fun getPackage(ctx: Context): String? =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_PKG, null)

    // ===== Manual Reader PRO =====
    private const val KEY_MANUAL_LAST_PAGE_PREFIX = "manual_last_page_"

    private fun keyForManual(assetPath: String): String {
        // Evita caracteres raros en keys
        val safe = Base64.encodeToString(assetPath.toByteArray(), Base64.NO_WRAP)
        return KEY_MANUAL_LAST_PAGE_PREFIX + safe
    }

    fun getManualLastPage1(ctx: Context, assetPath: String): Int {
        return ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getInt(keyForManual(assetPath), 1)
            .coerceAtLeast(1)
    }

    fun setManualLastPage1(ctx: Context, assetPath: String, page1: Int) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putInt(keyForManual(assetPath), page1.coerceAtLeast(1))
            .apply()
    }
    private const val KEY_MANUAL_CUSTOM_TITLE_PREFIX = "manual_custom_title::"

    fun getManualCustomTitle(ctx: Context, assetPath: String): String? {
        val p = ctx.getSharedPreferences("blackhawk_prefs", Context.MODE_PRIVATE)
        return p.getString(KEY_MANUAL_CUSTOM_TITLE_PREFIX + assetPath, null)
    }

    fun setManualCustomTitle(ctx: Context, assetPath: String, title: String) {
        val p = ctx.getSharedPreferences("blackhawk_prefs", Context.MODE_PRIVATE)
        p.edit().putString(KEY_MANUAL_CUSTOM_TITLE_PREFIX + assetPath, title.trim()).apply()
    }

    fun clearManualCustomTitle(ctx: Context, assetPath: String) {
        val p = ctx.getSharedPreferences("blackhawk_prefs", Context.MODE_PRIVATE)
        p.edit().remove(KEY_MANUAL_CUSTOM_TITLE_PREFIX + assetPath).apply()
    }

}
