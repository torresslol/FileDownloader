package com.example.filedownloadersample.Downloader

sealed class DownloadResult {
    abstract val exception: Exception?

    sealed class Single : DownloadResult() {
        data class DownloadCompleted(
            val filePath: String,
            val url: String
        ) : Single() {
            override val exception: Exception? = null
        }

        data class DownloadFailed(
            override val exception: Exception,
            val url: String? = null
        ) : Single()

        data class ProgressUpdate(
            val progress: Progress,
            val url: String
        ) : Single() {
            override val exception: Exception? = null
        }
    }

    sealed class Batch : DownloadResult() {
        data class DownloadCompleted(
            val filePath: String,
            val urls: List<String>
        ) : Batch() {
            override val exception: Exception? = null
        }

        data class DownloadFailed(
            override val exception: Exception,
            val urls: List<String>? = null
        ) : Batch()

        data class ProgressUpdate(
            val progress: Progress,
            val urls: List<String>
        ) : Batch() {
            override val exception: Exception? = null
        }
    }
}

data class Progress(val downloaded: Long, val total: Long) {
    val value: Float get() = downloaded.toFloat() / total.toFloat()
}