package com.cabgon.blackhawk.util

object Roles {

    const val USER = "user"
    const val MODERATOR = "moderator"
    const val ADMIN = "admin"
    const val DEVELOPER = "developer"

    fun normalize(role: String?): String =
        role?.trim()?.lowercase().takeUnless { it.isNullOrBlank() } ?: USER

    fun level(role: String?): Int = when (normalize(role)) {
        DEVELOPER -> 3
        ADMIN -> 2
        MODERATOR -> 1
        else -> 0
    }

    fun isAtLeast(role: String?, required: String): Boolean =
        level(role) >= level(required)
}