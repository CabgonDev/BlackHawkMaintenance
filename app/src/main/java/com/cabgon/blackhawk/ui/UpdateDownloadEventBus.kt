package com.cabgon.blackhawk.ui

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

sealed class UpdateDownloadEvent {
    data class Progress(val percent: Int) : UpdateDownloadEvent()
    data class Finished(val success: Boolean, val percent: Int) : UpdateDownloadEvent()
}

object UpdateDownloadEventBus {
    private val _events = MutableSharedFlow<UpdateDownloadEvent>(
        replay = 1,
        extraBufferCapacity = 16
    )
    val events: SharedFlow<UpdateDownloadEvent> = _events

    fun emit(event: UpdateDownloadEvent) {
        _events.tryEmit(event)
    }
}
