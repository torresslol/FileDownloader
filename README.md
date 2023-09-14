# FileDownloader

## About
FileDownloader is a robust library for multi-threaded file downloading in KMM. It provides easy-to-use interfaces and extensive customization options for efficient file downloads.

## Key Features
- **Parallel downloads:** Downloads files in multiple threads, offering speedy downloads even for large files.
- **Progress Tracking:** Monitor the download progression for each file slice in real-time.
- **Error Handling:** Robust error handling for issues such as network problems.
- **Customizability:** Adjustable thread count and file validation functionality to suit individual requirements.

## Usage

### Downloading a File

Here is an example of how to use FileDownloader to download a file in iOS with Swift:

```swift
    private func startDownload() {
        guard downloadState == .notStarted else { return }

        downloadState = .downloading
        let downloadResultFlow = fileDownloader.downloadFile(url: url, outputPath: outputPath, fileInfo: nil)
        let flow = FlowAdapter<DownloadResult>(flow: downloadResultFlow)
        
        downloadCancelable = flow.subscribe { [self] downloadResult in
            switch downloadResult {
            case is DownloadResult.Single.SingleDownloadCompleted:
                downloadState = .downloaded
            case is DownloadResult.Single.SingleDownloadFailed:
                downloadState = .notStarted
            case is DownloadResult.Single.SingleProgressUpdate:
                if let progressUpdate = downloadResult as? DownloadResult.Single.SingleProgressUpdate {
                    downloadProgress = progressUpdate.progress.value
                }
            default:
                break
            }
            
        } onComplete: {
            
        } onThrow: { _ in
            
        }
    }
```

### Setting up with a custom file validator

Here is an example of how to set up FileDownloader with a custom file validator:
```kotlin
val validator = object : FileValidator {
    override fun validateFileWithDigest(filePath: String): Boolean {
        // insert your validation logic here
    }
}

val downloader = FileDownloader(maxThreadCount = 5, validator = validator)
// Continue as normal
```

## Limitations
- The maximum number of threads that can be used for a download is limited by the server's capability and the device's network condition.

## License
This project is licensed under the terms of the MIT license.

---
