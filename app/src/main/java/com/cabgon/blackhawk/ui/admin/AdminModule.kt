package com.cabgon.blackhawk.ui.admin

data class AdminModule(
    val key: String,
    val titleRes: Int,
    val desc: String,
    val iconRes: Int,
    val badge: String,
    val fragmentClassCandidates: List<String>
)
