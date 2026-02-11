package com.cabgon.blackhawk.ui

/**
 * Pequeño flag global para saber si la UpdateRequiredActivity está visible.
 * Lo usa el servicio para decidir qué tipo de notificación mostrar.
 */
object UpdateDownloadVisibility {
    @Volatile
    var isUpdateActivityVisible: Boolean = false
}
