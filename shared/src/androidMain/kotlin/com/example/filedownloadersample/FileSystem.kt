package com.example.filedownloadersample

import okio.FileSystem

actual val FILESYSTEM: FileSystem
    get() = FileSystem.SYSTEM