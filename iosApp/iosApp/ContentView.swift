import SwiftUI
import shared

struct ContentView: View {
    let fileDownloader = FileDownloader(maxThreadCount: 5, validator: nil)
    let url = "your resource url"
    let outputPath = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        .appendingPathComponent("FileDownloader/sample.pak")
        .path

    @State private var downloadState: DownloadState = .notStarted
    @State private var downloadProgress: Float = 0.0
    @State private var downloadCancelable: Canceller?

    var body: some View {
        VStack(spacing: 20) {
            Text("Download State: \(downloadState.description)")
                .font(.headline)
                .transition(.slide)

            Button(action: {
                if downloadState == .notStarted {
                     withAnimation(.spring()) {
                         startDownload()
                     }
                 }
            }) {
                switch downloadState {
                case .notStarted:
                    Text("Start Download")
                case .downloading:
                    VStack {
                        Text("Downloading...")
                        ProgressView(value: Double(downloadProgress), total: 1.0)
                            .progressViewStyle(LinearProgressViewStyle(tint: .blue))
                    }
                    .padding()
                    .background(Color.blue.opacity(0.2))
                    .cornerRadius(10)
                    .transition(.scale)
                case .downloaded:
                    Text("Download Completed")
                        .transition(.scale)
                }
            }
        }
        .padding()
        .onDisappear {
            withAnimation {
                self.downloadCancelable?.cancel()
            }
        }
    }

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
}

struct ContentView_Previews: PreviewProvider {
    static var previews: some View {
        ContentView()
    }
}

enum DownloadState: String {
    case notStarted = "Not Started"
    case downloading = "Downloading"
    case downloaded = "Downloaded"

    var description: String {
        return rawValue
    }
}
