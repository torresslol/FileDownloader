package com.example.filedownloadersample.Downloader

import com.example.filedownloadersample.FILESYSTEM
import okio.HashingSink
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import kotlin.random.Random

abstract class FileValidator(private val expect: String) {
    abstract fun hashFileWithHashSink(outputPath: Path): String

    fun validateFileWithDigest(filePath: String, needBase64: Boolean = true): Boolean {
        if (!FILESYSTEM.exists(filePath.toPath())) return false
        val hash = hashFileWithHashSink(filePath.toPath()).lowercase()
        if (!needBase64) {
            return hash == expect.lowercase()
        }
        val base64 = hash.byteStringToBase64().lowercase()
        return base64 == expect.lowercase()
    }

    protected fun createTempFile(outputPath: Path): Path {
        val parentPath = outputPath.parent ?: kotlin.run { outputPath }
        val temp = randomString(10)
        val resultPath = parentPath.resolve(temp)
        if (!FILESYSTEM.exists(resultPath)) {
            val path = resultPath.toString() + "1"
            return path.toPath()
        }
        return resultPath
    }

    private fun randomString(
        length: Int,
        allowedChars: String = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    ): String {
        return (1..length)
            .map { allowedChars.random(Random.Default) }
            .joinToString("")
    }
}

class Md5FileValidator(private val expect: String) : FileValidator(expect) {
    override fun hashFileWithHashSink(outputPath: Path): String {
        lateinit var md5HashingSink: HashingSink
        val tempFilePath = createTempFile(outputPath)
        FILESYSTEM.source(outputPath).use { source ->
            md5HashingSink = HashingSink.md5(FILESYSTEM.sink(tempFilePath))
            source.buffer().use { bufferedSource ->
                bufferedSource.readAll(md5HashingSink)
            }
        }
        val digest = md5HashingSink.hash.hex()
        FILESYSTEM.delete(tempFilePath, false)
        return digest
    }
}

class SHA256FileValidator(private val expect: String) : FileValidator(expect) {
    override fun hashFileWithHashSink(outputPath: Path): String {
        lateinit var sha256HashingSink: HashingSink
        val tempFilePath = createTempFile(outputPath)
        FILESYSTEM.source(outputPath).use { source ->
            sha256HashingSink = HashingSink.sha256(FILESYSTEM.sink(tempFilePath))
            source.buffer().use { bufferedSource ->
                bufferedSource.readAll(sha256HashingSink)
            }
        }
        val digest = sha256HashingSink.hash.hex()
        FILESYSTEM.delete(tempFilePath, false)
        return digest
    }
}