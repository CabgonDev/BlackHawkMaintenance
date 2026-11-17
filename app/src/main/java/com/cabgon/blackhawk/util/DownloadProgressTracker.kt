package com.cabgon.blackhawk.util

import kotlin.math.roundToInt

/** Tracks verified completion separately from transferred bytes. */
class DownloadProgressTracker {
    private var indexBytes: Long? = null
    private var manualBytes: Long? = null
    private var indexProgress = 0f
    private var manualProgress = 0f
    private var indexComplete = false
    private var manualsComplete = false
    private val manualTransfers = mutableMapOf<Int, Long>()

    fun reset(indexBytes: Long? = null, manualBytes: Long? = null) {
        this.indexBytes = indexBytes
        this.manualBytes = manualBytes
        indexProgress = 0f
        manualProgress = 0f
        indexComplete = false
        manualsComplete = false
        manualTransfers.clear()
    }

    fun updateIndex(progress: Float) {
        indexProgress = maxOf(indexProgress, progress.coerceIn(0f, 1f))
    }

    fun completeIndex() {
        indexComplete = true
        indexProgress = 1f
    }

    fun updateManual(itemIndex: Int, itemCount: Int, progress: Float, transferred: Long = 0L) {
        val count = itemCount.coerceAtLeast(1)
        val index = itemIndex.coerceIn(1, count)
        manualTransfers[index] = maxOf(manualTransfers[index] ?: 0L, transferred.coerceAtLeast(0L))
        val fraction = if ((manualBytes ?: 0L) > 0L) {
            manualTransfers.values.sum().toDouble().div(manualBytes!!.toDouble()).toFloat()
        } else {
            ((index - 1) + progress.coerceIn(0f, 1f)) / count.toFloat()
        }
        manualProgress = maxOf(manualProgress, fraction.coerceIn(0f, 1f))
    }

    fun completeManuals() {
        manualsComplete = true
        manualProgress = 1f
    }

    fun percent(): Int {
        if (indexComplete && manualsComplete) return 100
        val knownBytes = indexBytes != null && manualBytes != null &&
            indexBytes!! + manualBytes!! > 0L
        val indexWeight = if (knownBytes) indexBytes!!.toDouble() else if (indexBytes == 0L) 0.0 else 1.0
        val manualWeight = if (knownBytes) manualBytes!!.toDouble() else if (manualBytes == 0L) 0.0 else 1.0
        val total = indexWeight + manualWeight
        if (total == 0.0) return 0
        return ((indexProgress * indexWeight + manualProgress * manualWeight) / total * 100)
            .roundToInt().coerceIn(0, 99)
    }
}
