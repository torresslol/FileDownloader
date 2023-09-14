package com.example.filedownloadersample.Downloader

import io.ktor.util.encodeBase64
import okio.ByteString.Companion.encodeUtf8

fun String.byteStringToBase64(): String {
    val byteArray = this.chunked(2).map { it.toInt(radix = 16).toByte() }.toByteArray()
    return byteArray.encodeBase64()
}

fun String.md5(): String {
    return this.encodeUtf8().md5().hex()
}