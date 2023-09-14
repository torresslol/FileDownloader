package com.example.filedownloadersample.Downloader

import com.example.filedownloadersample.FILESYSTEM
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.onDownload
import io.ktor.client.request.head
import io.ktor.client.request.prepareGet
import io.ktor.http.contentLength
import io.ktor.util.collections.ConcurrentMap
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.CancellationException
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil
import kotlin.math.min

// File information
class FileInfo(val fileSize: Long, val acceptRange: Boolean)

// Progress of a slice
private data class SliceProgress(val url: String, val sliceIndex: Int, val progress: Progress)

class FileDownloader(
    private val maxThreadCount: Int = 5,
    private val validator: FileValidator? = null
) {

    // HTTP client with retry and timeout settings
    private val client = HttpClient {

        install(HttpRequestRetry) {
            retryOnException(maxRetries = 1, retryOnTimeout = true)
            exponentialDelay()
        }

        install(HttpTimeout) {
            requestTimeoutMillis = 600 * 1000
            socketTimeoutMillis = 30000
            connectTimeoutMillis = 30000
        }

    }

    // Get file info, including size and whether it supports range requests or not
    private suspend fun getFileInfo(url: String): FileInfo {
        val response = client.head(url)
        val fileSize = response.contentLength()
            ?: kotlin.run { throw GetFileInfoFailed("contentLength not found") }
        return FileInfo(fileSize, response.headers["Accept-Ranges"]?.contains("bytes") == true)
    }

    // Download a file and report progress
    fun downloadFile(
        url: String,
        outputPath: String,
        fileInfo: FileInfo? = null
    ): Flow<DownloadResult> = callbackFlow {

        // Progress map to keep track of progress for each slice
        val progressMap = ConcurrentMap<Int, Progress>()

        // Exception handler to catch and handle exceptions
        val exceptionHandler = CoroutineExceptionHandler { _, exception ->
            println("FileDownloader coroutineExceptionHandler caught exception $exception \n")
        }

        // Launch the download in the background
        this.launch(Dispatchers.IO + exceptionHandler) {
            try {
                // Get the target file info. If fileInfo is null, fetch it from the URL
                val targetFileInfo = fileInfo ?: getFileInfo(url)
                val tempPath = createUrlFolder(outputPath.toPath(), url)

                val f = MutableSharedFlow<SliceProgress>(
                    extraBufferCapacity = 100,
                    onBufferOverflow = BufferOverflow.DROP_OLDEST
                )

                val flow = ThrottledFlow(f, 200)

                // Update progress
                fun updateProgress(url: String, clientIndex: Int, bytesSent: Long, contentLength: Long) {
                    progressMap[clientIndex] = Progress(bytesSent, contentLength)

                    val totalDownloaded = progressMap.values.sumOf { it.downloaded }
                    val totalContentLength = targetFileInfo.fileSize

                    println("FileDownloader download progress update downloaded = $totalDownloaded, total = $totalContentLength, url= $url \n")

                    trySend(DownloadResult.Single.ProgressUpdate(Progress(totalDownloaded, totalContentLength), url))
                }

                // Collect from the flow to update progress
                async {
                    flow.collect {
                        updateProgress(it.url, it.sliceIndex, it.progress.downloaded, it.progress.total)
                    }
                }

                // Initialize threadCount as 1
                var threadCount = 1
                var downloads = emptyList<Deferred<*>>()

                println("FileDownloader start download url = $url \n")

                // If the target file does not support range requests, download it in a single thread
                if (!targetFileInfo.acceptRange) {
                    val filePath = tempPath.resolve("0.part")
                    downloadFileNormally(
                        url = url,
                        fileSize = targetFileInfo.fileSize,
                        tempPath = filePath,
                        progressFlow = flow
                    )
                } else {
                    // If the target file supports range requests, download it in multiple threads
                    threadCount = calculateThreads(fileSize = targetFileInfo.fileSize)
                    val chunkSize = ceil(targetFileInfo.fileSize.toDouble() / threadCount).toLong()

                    downloads = (0 until threadCount).map { threadIndex ->
                        async {
                            val start = chunkSize * threadIndex
                            val end = min(start + chunkSize - 1, targetFileInfo.fileSize - 1)

                            val tempFilePath = tempPath.resolve("$threadIndex.part")

                            downloadFilePart(
                                url,
                                start,
                                end,
                                tempFilePath,
                                progressFlow = flow,
                                index = threadIndex
                            )
                        }
                    }
                }

                // Wait for all parts to be downloaded
                downloads.awaitAll()

                println("FileDownloader downloaded to temp directory start moving url = $url \n")

                // Merge all parts into one file if downloaded in multiple threads
                if (threadCount > 1) {
                    mergeFileParts(url, tempPath, outputPath.toPath(), threadCount)
                } else {
                    val tempFilePath = tempPath.resolve("0.part")
                    safeMoveFile(url, tempFilePath, outputPath.toPath())
                }

                // Verify downloaded file if validator is provided
                validator?.let {
                    if (!it.validateFileWithDigest(outputPath))
                        send(DownloadResult.Single.DownloadFailed(ValidateFileFailed("FileDownloader validate file failed"), url))
                }

                println("FileDownloader download success url= $url \n")

                // If no exception occurred, send DownloadCompleted event
                if (!isClosedForSend) send(DownloadResult.Single.DownloadCompleted(outputPath, url))

            } catch (exception: Exception) {

                println("FileDownloader caught exception = $exception url= $url \n")

                // If any exception occurred, send DownloadFailed event
                if (exception !is CancellationException) {
                    trySend(DownloadResult.Single.DownloadFailed(exception, url))
                }
            } finally {
                // Close the channel when done
                channel.close()
            }
        }

        // Wait for the download to complete
        awaitClose {
            println("FileDownloader awaitClose")
        }
    }

    // Download a file in a single thread
    private suspend fun downloadFileNormally(
        url: String,
        fileSize: Long,
        tempPath: Path,
        progressFlow: MutableSharedFlow<SliceProgress>
    ) {
        // Check if the temp file already exists and has correct size
        val existSize = checkTempFile(tempPath)
        if (existSize != fileSize) {
            // If not, delete the temp file
            FILESYSTEM.delete(tempPath, mustExist = false)

            val request = client.prepareGet(url) {
                onDownload { bytesSentTotal, contentLength ->
                    val progress = SliceProgress(url, sliceIndex = 0, Progress(bytesSentTotal, contentLength))
                    progressFlow.tryEmit(progress)
                }
            }

            val fileHandle = FILESYSTEM.openReadWrite(tempPath, mustCreate = false, mustExist = false)

            fileHandle.use { handle ->
                var bytesReadTotal = 0L
                request.execute { it ->
                    val bytes = ByteArray(DEFAULT_CHUNK_SIZE)
                    var bytesRead: Int
                    val channel: ByteReadChannel = it.body()

                    while (channel.readAvailable(bytes).also { bytesRead = it } != -1) {
                        handle.write(bytesReadTotal, bytes, 0, bytesRead)
                        bytesReadTotal += bytesRead
                    }
                }
            }
        }
    }

    // Download a part of a file in a single thread
    private suspend fun downloadFilePart(
        url: String,
        start: Long,
        end: Long,
        tempPath: Path,
        progressFlow: MutableSharedFlow<SliceProgress>,
        index: Int
    ) {
        var startLocation = start
        val existSize = checkTempFile(tempPath)

        // Adjust the start location if part of the file has already been downloaded
        if (existSize in 1 until (end - start + 1)) {
            startLocation += existSize
        } else {
            FILESYSTEM.delete(tempPath, mustExist = false)
        }

        val request = client.prepareGet(url) {
            onDownload { bytesSentTotal, contentLength ->
                val progress = SliceProgress(
                    url,
                    sliceIndex = index,
                    Progress(
                        (bytesSentTotal + existSize),
                        (contentLength + existSize)
                    )
                )
                progressFlow.tryEmit(progress)
            }

            this.headers.append(name = "Range", value = "bytes=${startLocation}-${end}")
        }

        val fileHandle = FILESYSTEM.openReadWrite(tempPath, mustCreate = false, mustExist = false)

        fileHandle.use { handle ->
            var bytesReadTotal: Long = startLocation - start
            request.execute { it ->
                coroutineContext.ensureActive()
                val bytes = ByteArray(DEFAULT_CHUNK_SIZE)
                var bytesRead: Int
                val channel: ByteReadChannel = it.body()
                while (channel.readAvailable(bytes).also { bytesRead = it } != -1) {
                    coroutineContext.ensureActive()
                    handle.write(bytesReadTotal, bytes, 0, bytesRead)
                    bytesReadTotal += bytesRead
                }
            }
        }
    }

    // Calculate the number of threads to use for downloading
    private fun calculateThreads(fileSize: Long): Int {
        return when {
            fileSize < 5 * 1024 * 1024 -> 1
            fileSize < 50 * 1024 * 1024 -> min(2, maxThreadCount)
            fileSize < 100 * 1024 * 1024 -> min(3, maxThreadCount)
            else -> maxThreadCount
        }
    }

    companion object {
        private const val DEFAULT_CHUNK_SIZE = 8192
    }
}

// Check if the temp file exists and return its size
private fun checkTempFile(tempFilePath: Path): Long {
    return FILESYSTEM.takeIf { it.exists(tempFilePath) }?.run {
        metadataOrNull(tempFilePath)?.size ?: 0
    } ?: 0
}

// Create a folder for the temp file
private fun createUrlFolder(outputPath: Path, url: String): Path {
    val parentPath = outputPath.parent ?: outputPath
    val urlPath = url.toPath().name.md5()
    val resultPath = parentPath.resolve(urlPath)

    // If the folder does not exist, create it
    if (!FILESYSTEM.exists(resultPath)) {
        FILESYSTEM.createDirectories(resultPath, false)
    }
    return resultPath
}

// Merge all parts into a single file
private fun mergeFileParts(url: String, tempPath: Path, outputPath: Path, threadCount: Int) {
    try {
        // Delete the output file if it already exists
        FILESYSTEM.delete(path = outputPath, mustExist = false)

        // Open the output file
        FILESYSTEM.sink(outputPath).buffer().use { output ->
            try {
                for (index in 0 until threadCount) {
                    val tPath = tempPath.resolve("$index.part")
                    // Check if the part file exists; it might not exist if the file size is smaller than expected
                    if (FILESYSTEM.exists(tPath)) {
                        val metadata = FILESYSTEM.metadata(tPath)
                        val partSize = metadata.size

                        // Read from the part file and write to the output file
                        FILESYSTEM.source(tPath).buffer().use { input ->
                            val size = input.readAll(output)
                            println("FileDownloader readAll from path = $tPath, size = $size \n")
                        }
                    }
                }

                // Delete all parts
                FILESYSTEM.deleteRecursively(tempPath, mustExist = false)
            } catch (e: Exception) {
                throw HandleFileFailed(
                    "merge tempFile to output path failed exception = ${e.message}",
                    e
                )
            }
        }
    } catch (e: Exception) {
        throw HandleFileFailed(
            "merge tempFile to output path failed exception = ${e.message}",
            e
        )
    }
}

// Move temp file to final location
private fun safeMoveFile(url: String, tempPath: Path, outputPath: Path) {
    try {
        FILESYSTEM.atomicMove(tempPath, outputPath)
    } catch (e: Exception) {
        throw HandleFileFailed(
            "move tempFile to output path failed exception = ${e.message}",
            e
        )
    }
}