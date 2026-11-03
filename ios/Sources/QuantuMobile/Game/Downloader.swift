import Foundation
import CryptoKit

struct DownloadTask {
    let url: String
    let dest: URL
    var sha1: String?
    var size: Int64 = -1
}

/// Parallel downloads with SHA-1 verification and retries (port of the Android Downloader).
enum Downloader {
    static func sha1Hex(of url: URL) throws -> String {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        var hasher = Insecure.SHA1()
        while let chunk = try handle.read(upToCount: 1 << 20), !chunk.isEmpty { hasher.update(data: chunk) }
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }

    /// True when the file already exists and matches the expected size/hash.
    static func isValid(_ task: DownloadTask) -> Bool {
        let fm = FileManager.default
        guard let attrs = try? fm.attributesOfItem(atPath: task.dest.path), let size = attrs[.size] as? NSNumber else { return false }
        if task.size >= 0 && size.int64Value != task.size { return false }
        if let sha1 = task.sha1, (try? sha1Hex(of: task.dest)) != sha1.lowercased() { return false }
        return true
    }

    static func download(_ task: DownloadTask, attempts: Int = 3) async throws {
        if isValid(task) { return }
        guard let url = URL(string: task.url) else { throw GameError("Bad URL: \(task.url)") }
        try FileManager.default.createDirectory(at: task.dest.deletingLastPathComponent(), withIntermediateDirectories: true)
        var lastError: Error = GameError("Download failed: \(task.url)")
        for attempt in 1...max(1, attempts) {
            do {
                let (tmp, response) = try await URLSession.shared.download(from: url)
                let code = (response as? HTTPURLResponse)?.statusCode ?? 0
                guard (200..<300).contains(code) else { throw GameError("HTTP \(code): \(task.url)") }
                if let sha1 = task.sha1, try sha1Hex(of: tmp) != sha1.lowercased() { throw GameError("Hash mismatch: \(task.url)") }
                try? FileManager.default.removeItem(at: task.dest)
                try FileManager.default.moveItem(at: tmp, to: task.dest)
                return
            } catch {
                lastError = error
                if attempt < attempts { try await Task.sleep(nanoseconds: UInt64(attempt) * 500_000_000) }
            }
        }
        throw lastError
    }

    /// Downloads every task with at most `parallelism` in flight; `progress(done, total)` after each one.
    static func downloadAll(_ tasks: [DownloadTask], parallelism: Int = 8, progress: @escaping @Sendable (Int, Int) -> Void) async throws {
        let total = tasks.count
        progress(0, total)
        try await withThrowingTaskGroup(of: Void.self) { group in
            var next = 0
            var done = 0
            func addNext() {
                guard next < total else { return }
                let task = tasks[next]; next += 1
                group.addTask { try await download(task) }
            }
            for _ in 0..<min(parallelism, total) { addNext() }
            while try await group.next() != nil {
                done += 1
                progress(done, total)
                addNext()
            }
        }
    }
}
