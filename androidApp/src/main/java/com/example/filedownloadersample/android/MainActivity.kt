package com.example.filedownloadersample.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.filedownloadersample.Downloader.DownloadResult
import com.example.filedownloadersample.Downloader.FileDownloader

class MainActivity : ComponentActivity() {
    private val fileDownloader = FileDownloader(maxThreadCount = 5, validator = null)
    private val url = "your sample url"

    @ExperimentalMaterialApi
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var downloadState by remember { mutableStateOf(DownloadState.NOT_STARTED) }
            var downloadProgress by remember { mutableStateOf(0.0f) }

            LaunchedEffect(Unit) {
                val outputPath = getExternalFilesDir(null)?.absolutePath + "/sample.pak"
                fileDownloader.downloadFile(url, outputPath, null).collect { downloadResult ->
                    when(downloadResult) {
                        is DownloadResult.Single.DownloadCompleted -> {
                            downloadState = DownloadState.DOWNLOADED
                        }
                        is DownloadResult.Single.DownloadFailed -> {
                            downloadState = DownloadState.NOT_STARTED
                        }
                        is DownloadResult.Single.ProgressUpdate -> {
                            downloadProgress = downloadResult.progress.value
                            downloadState = DownloadState.DOWNLOADING
                        }

                        else -> {}
                    }
                }
            }

            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = "Download State: ${downloadState.description}")
                Spacer(modifier = Modifier.height(16.dp))
                when (downloadState) {
                    DownloadState.NOT_STARTED -> Button(onClick = {}) {
                        Text(text = "Start Download")
                    }
                    DownloadState.DOWNLOADING -> {
                        CircularProgressIndicator(downloadProgress)
                        Text(text = "Downloading...")
                    }
                    DownloadState.DOWNLOADED -> Text(text = "Download Completed")
                }
            }
        }
    }

    enum class DownloadState(val description: String) {
        NOT_STARTED("Not Started"),
        DOWNLOADING("Downloading"),
        DOWNLOADED("Downloaded")
    }
}
