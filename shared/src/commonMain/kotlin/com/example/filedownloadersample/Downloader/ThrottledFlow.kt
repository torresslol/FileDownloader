package com.example.filedownloadersample.Downloader

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.datetime.Clock

class ThrottledFlow<T>(private val origin: MutableSharedFlow<T>, private val intervalMillis: Long) : MutableSharedFlow<T> by origin {

    private var lastEmitTime: Long = 0L

    override suspend fun emit(value: T) {
        if (canEmit()) {
            lastEmitTime = Clock.System.now().toEpochMilliseconds()
            origin.emit(value)
        }
    }

    override fun tryEmit(value: T): Boolean {
        if (canEmit()) {
            lastEmitTime = Clock.System.now().toEpochMilliseconds()
            return origin.tryEmit(value)
        }
        return false
    }

    private fun canEmit(): Boolean {
        val now = Clock.System.now().toEpochMilliseconds()
        return now - lastEmitTime >= intervalMillis
    }

}