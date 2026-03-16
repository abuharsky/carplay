package com.alexander.carplay.data.files

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class SharedDownloadsMirror(
    context: Context,
) {
    companion object {
        private const val TAG = "CarPlayFiles"
        private const val MAX_LOG_FILE_BYTES = 10 * 1024 * 1024
        private val DOWNLOAD_DIRECTORY_CANDIDATES = listOf(
            "/storage/sdcard0/Download",
            "/storage/emulated/0/Download",
            "/storage/emulated/0/Downloads",
        )

        private val ioExecutor = Executors.newSingleThreadExecutor()
        private val pendingLogLines = ConcurrentLinkedQueue<String>()
        private val logFlushScheduled = AtomicBoolean(false)

        @Volatile
        private var lastWorkingDirectory: File? = null

        @Volatile
        private var lastReportedPath: String? = null
    }

    private val appContext = context.applicationContext

    fun appendLog(line: String) {
        pendingLogLines.add(line)
        scheduleLogFlush()
    }

    private fun scheduleLogFlush() {
        if (!logFlushScheduled.compareAndSet(false, true)) return
        ioExecutor.execute {
            try {
                while (true) {
                    val batch = drainPendingLogLines()
                    if (batch.isEmpty()) return@execute

                    val result = writeFirstAvailable("carplay.log") { file ->
                        appendBoundedUtf8(file, batch)
                    }
                    result.exceptionOrNull()?.let { error ->
                        Log.w(TAG, "Failed to append carplay.log", error)
                    }

                    if (pendingLogLines.isEmpty()) return@execute
                }
            } finally {
                logFlushScheduled.set(false)
                if (pendingLogLines.isNotEmpty()) {
                    scheduleLogFlush()
                }
            }
        }
    }

    fun writeSharedFile(
        name: String,
        data: ByteArray,
    ): File? {
        if (data.isEmpty()) return null
        return writeFirstAvailable(name) { file ->
            file.writeBytes(data)
        }.getOrElse { error ->
            Log.w(TAG, "Failed to write shared file $name", error)
            null
        }
    }

    private fun drainPendingLogLines(): String {
        val builder = StringBuilder()
        while (true) {
            val line = pendingLogLines.poll() ?: break
            builder.append(line)
        }
        return builder.toString()
    }

    private fun orderedDirectories(): List<File> {
        val candidates = buildList {
            lastWorkingDirectory?.let(::add)
            DOWNLOAD_DIRECTORY_CANDIDATES
                .map(::File)
                .forEach(::add)
            appContext.externalMediaDirs
                .firstOrNull()
                ?.let(::add)
            appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let(::add)
        }

        return candidates.distinctBy { it.absolutePath }
    }

    private fun writeFirstAvailable(
        name: String,
        action: (File) -> Unit,
    ): Result<File> {
        var lastError: Throwable? = null
        orderedDirectories().forEach { directory ->
            runCatching {
                ensureDirectory(directory)
                val targetFile = File(directory, name)
                action(targetFile)
                lastWorkingDirectory = directory
                reportResolvedPath(targetFile)
                return Result.success(targetFile)
            }.onFailure { error ->
                lastError = error
            }
        }
        return Result.failure(lastError ?: IllegalStateException("No writable directory for $name"))
    }

    private fun ensureDirectory(directory: File) {
        if (!directory.exists()) {
            directory.mkdirs()
        }
        require(directory.exists() && directory.isDirectory) {
            "Directory is unavailable: ${directory.absolutePath}"
        }
    }

    private fun reportResolvedPath(file: File) {
        val path = file.absolutePath
        if (lastReportedPath == path) return
        lastReportedPath = path
        Log.i(TAG, "Using shared file path: $path")
    }

    private fun appendBoundedUtf8(
        file: File,
        content: String,
    ) {
        val newBytes = content.toByteArray(StandardCharsets.UTF_8)
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(raf.length())
            raf.write(newBytes)

            val fileLength = raf.length()
            if (fileLength <= MAX_LOG_FILE_BYTES) return

            val retainLength = MAX_LOG_FILE_BYTES.toLong().coerceAtMost(fileLength)
            val retainedBytes = ByteArray(retainLength.toInt())
            raf.seek(fileLength - retainLength)
            raf.readFully(retainedBytes)

            val normalizedTail = normalizeUtf8Tail(retainedBytes)
            raf.setLength(0)
            raf.seek(0)
            raf.write(normalizedTail)
        }
    }

    private fun normalizeUtf8Tail(bytes: ByteArray): ByteArray {
        if (bytes.isEmpty()) return bytes

        var start = 0
        while (start < bytes.size && (bytes[start].toInt() and 0xC0) == 0x80) {
            start++
        }

        if (start >= bytes.size) return ByteArray(0)

        var newlineIndex = -1
        for (index in start until bytes.size) {
            if (bytes[index] == 0x0A.toByte()) {
                newlineIndex = index
                break
            }
        }
        if (newlineIndex != -1 && newlineIndex + 1 < bytes.size) {
            start = newlineIndex + 1
        }

        return bytes.copyOfRange(start, bytes.size)
    }
}
