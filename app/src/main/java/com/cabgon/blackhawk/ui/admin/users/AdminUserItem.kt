package com.cabgon.blackhawk.ui.admin.users

import com.google.firebase.Timestamp

data class AdminUserItem(
    val uid: String,
    val nombre: String,
    val email: String,
    val role: String,
    val status: String,
    val deviceLastSeenAt: Timestamp?,
    val appLastSeenAt: Timestamp?,
    val appVersionCode: Long?,
    val appVersionName: String
)
