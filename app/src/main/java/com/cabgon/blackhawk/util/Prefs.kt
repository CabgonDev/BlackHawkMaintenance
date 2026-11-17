package com.cabgon.blackhawk.util

import android.content.Context
import android.util.Base64
import androidx.core.content.edit

object Prefs {
    /** Set false in a later release to stop forcing this one-time migration. */
    const val AUTH_MIGRATION_ENABLED = true

    /** One-time migration shipped with the release that introduced device binding. */
    const val AUTH_MIGRATION_VERSION_CODE = 501L

    private const val FILE = "bh_prefs"
    private const val KEY_PKG = "pkg"
    private const val KEY_LOGIN_EMAIL = "login_email"
    private const val KEY_PENDING_AUTH_RESET_VERSION = "pending_auth_reset_version"
    private const val KEY_COMPLETED_AUTH_MIGRATION_VERSION = "completed_auth_migration_version"
    private const val KEY_LAST_MAIN_SECTION = "last_main_section"
    private const val KEY_LAST_PDF_ASSET = "last_pdf_asset"
    private const val KEY_LAST_PDF_PAGE = "last_pdf_page"
    private const val KEY_LAST_PDF_HIGHLIGHT_QUERIES = "last_pdf_highlight_queries"
    private const val KEY_LAST_PDF_SHOW_PART_LOADING = "last_pdf_show_part_loading"
    private const val KEY_LAST_PDF_PART_DESCRIPTION = "last_pdf_part_description"
    private const val KEY_LAST_PDF_PART_NUMBER = "last_pdf_part_number"
    private const val KEY_LAST_PDF_PART_NSN = "last_pdf_part_nsn"

    data class LastPdfState(
        val assetPath: String,
        val page1: Int,
        val highlightQueries: List<String>,
        val showPartSearchLoading: Boolean,
        val partDescription: String?,
        val partDescriptionNumber: String?,
        val partDescriptionNsn: String?
    )

    fun getLoginEmail(ctx: Context): String? =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_LOGIN_EMAIL, null)

    fun setLoginEmail(ctx: Context, email: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit {
            putString(KEY_LOGIN_EMAIL, email.trim())
        }
    }

    fun clearLoginEmail(ctx: Context) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit {
            remove(KEY_LOGIN_EMAIL)
        }
    }

    /** Version of the one-time credential/device migration that is waiting to complete. */
    fun getPendingAuthResetVersion(ctx: Context): Long? {
        val prefs = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_PENDING_AUTH_RESET_VERSION)) return null
        return prefs.getLong(KEY_PENDING_AUTH_RESET_VERSION, 0L).takeIf { it > 0L }
    }

    fun setPendingAuthResetVersion(ctx: Context, versionCode: Long) {
        if (versionCode <= 0L) return
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit {
            putLong(KEY_PENDING_AUTH_RESET_VERSION, versionCode)
        }
    }

    /** Last migration version completed after a successful device claim. */
    fun getCompletedAuthMigrationVersion(ctx: Context): Long? {
        val prefs = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_COMPLETED_AUTH_MIGRATION_VERSION)) return null
        return prefs.getLong(KEY_COMPLETED_AUTH_MIGRATION_VERSION, 0L).takeIf { it > 0L }
    }

    fun markAuthMigrationComplete(ctx: Context, versionCode: Long) {
        if (versionCode <= 0L) return
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit {
            remove(KEY_PENDING_AUTH_RESET_VERSION)
            putLong(KEY_COMPLETED_AUTH_MIGRATION_VERSION, versionCode)
        }
    }

    fun clearPackage(ctx: Context) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit {
            remove(KEY_PKG)
        }
    }

    fun setPackage(ctx: Context, value: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit {
            putString(KEY_PKG, value)
        }
    }

    fun getPackage(ctx: Context): String? =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_PKG, null)

    fun getLastMainSection(ctx: Context): String? =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_LAST_MAIN_SECTION, null)

    fun setLastMainSection(ctx: Context, sectionTag: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit {
            putString(KEY_LAST_MAIN_SECTION, sectionTag)
        }
    }

    fun clearLastMainSection(ctx: Context) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit {
            remove(KEY_LAST_MAIN_SECTION)
        }
    }

    fun getLastPdfState(ctx: Context): LastPdfState? {
        val prefs = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val assetPath = prefs.getString(KEY_LAST_PDF_ASSET, null)
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: return null

        return LastPdfState(
            assetPath = assetPath,
            page1 = prefs.getInt(KEY_LAST_PDF_PAGE, 1).coerceAtLeast(1),
            highlightQueries = prefs.getStringSet(KEY_LAST_PDF_HIGHLIGHT_QUERIES, emptySet())
                .orEmpty()
                .filter(String::isNotBlank),
            showPartSearchLoading = prefs.getBoolean(KEY_LAST_PDF_SHOW_PART_LOADING, false),
            partDescription = prefs.getString(KEY_LAST_PDF_PART_DESCRIPTION, null),
            partDescriptionNumber = prefs.getString(KEY_LAST_PDF_PART_NUMBER, null),
            partDescriptionNsn = prefs.getString(KEY_LAST_PDF_PART_NSN, null)
        )
    }

    fun setLastPdfState(ctx: Context, state: LastPdfState) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit {
            putString(KEY_LAST_PDF_ASSET, state.assetPath)
            putInt(KEY_LAST_PDF_PAGE, state.page1.coerceAtLeast(1))
            putStringSet(
                KEY_LAST_PDF_HIGHLIGHT_QUERIES,
                state.highlightQueries.filter(String::isNotBlank).toSet()
            )
            putBoolean(KEY_LAST_PDF_SHOW_PART_LOADING, state.showPartSearchLoading)

            if (state.partDescription.isNullOrBlank()) {
                remove(KEY_LAST_PDF_PART_DESCRIPTION)
            } else {
                putString(KEY_LAST_PDF_PART_DESCRIPTION, state.partDescription)
            }
            if (state.partDescriptionNumber.isNullOrBlank()) {
                remove(KEY_LAST_PDF_PART_NUMBER)
            } else {
                putString(KEY_LAST_PDF_PART_NUMBER, state.partDescriptionNumber)
            }
            if (state.partDescriptionNsn.isNullOrBlank()) {
                remove(KEY_LAST_PDF_PART_NSN)
            } else {
                putString(KEY_LAST_PDF_PART_NSN, state.partDescriptionNsn)
            }
        }
    }

    fun clearLastPdfState(ctx: Context) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit {
            remove(KEY_LAST_PDF_ASSET)
            remove(KEY_LAST_PDF_PAGE)
            remove(KEY_LAST_PDF_HIGHLIGHT_QUERIES)
            remove(KEY_LAST_PDF_SHOW_PART_LOADING)
            remove(KEY_LAST_PDF_PART_DESCRIPTION)
            remove(KEY_LAST_PDF_PART_NUMBER)
            remove(KEY_LAST_PDF_PART_NSN)
        }
    }

    // ===== Manual Reader PRO =====
    private const val KEY_MANUAL_LAST_PAGE_PREFIX = "manual_last_page_id_"

    private fun keyForManualId(manualId: String): String {
        // Por si acaso viene con caracteres raros
        val safe = Base64.encodeToString(manualId.toByteArray(), Base64.NO_WRAP)
        return KEY_MANUAL_LAST_PAGE_PREFIX + safe
    }

    fun getManualLastPage1(ctx: Context, manualId: String): Int {
        return ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getInt(keyForManualId(manualId), 1)
            .coerceAtLeast(1)
    }

    fun setManualLastPage1(ctx: Context, manualId: String, page1: Int) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit {
            putInt(keyForManualId(manualId), page1.coerceAtLeast(1))
        }
    }

    // ===== Manual Custom Title (lo dejo intacto para no romper) =====
    private const val KEY_MANUAL_CUSTOM_TITLE_PREFIX = "manual_custom_title::"

    fun getManualCustomTitle(ctx: Context, assetPath: String): String? {
        val p = ctx.getSharedPreferences("blackhawk_prefs", Context.MODE_PRIVATE)
        return p.getString(KEY_MANUAL_CUSTOM_TITLE_PREFIX + assetPath, null)
    }
}
