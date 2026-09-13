package com.example.webrtcdemo.diag

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.example.webrtcdemo.BuildConfig
import com.example.webrtcdemo.R
import com.example.webrtcdemo.log.AppLog
import com.example.webrtcdemo.log.FileLogger
import com.example.webrtcdemo.nativebridge.NativeLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// ============================================================================
// 日志导出（文件：diag/LogExporter.kt，文档依据 doc/14 §2.1 / §9.5）
// ----------------------------------------------------------------------------
// 【§9.5 契约，逐条对齐】
//   1. 导出前先 `NativeLog.nativeFlush()` + Kotlin `FileLogger.flush()`；
//   2. 生成 `<filesDir>/logs/export/webrtcdemo-logs-<yyyyMMdd-HHmmssZ>.zip`，
//      内含 app*.log、native*.log、encoder_bitrate.csv、webrtc*.log、
//      device-info.txt、session-summary.txt；
//   3. `FileProvider.getUriForFile(ctx, AUTHORITY, zip)` → ACTION_SEND(type=application/zip)
//      + FLAG_GRANT_READ_URI_PERMISSION → createChooser；
//   4. `export/` 只保留最近 3 个 zip（App 启动时清理更旧的，见 [cleanupOldExports]）。
//   authority 冻结为字面量 `com.example.webrtcdemo.fileprovider`（= ${applicationId}.fileprovider）。
//   打包范围仅 logs/（不含其它应用数据）；`allowBackup=false` 已在 Manifest 冻结。
//
// 【留给 t8 的挂点】session-summary.txt 需要 roomId / 角色 / ICE 结果 /
// encoderImplementation，这些属信令与 WebRTC 契约类型，本文件不引用它们；
// t8 在会话建立后设置 [sessionSummaryProvider] 即可（默认输出占位说明）。
//
// 本文件不引用任何 JNI / 信令契约类型，可独立编译。
// ============================================================================

/**
 * 日志导出器：flush → zip → FileProvider → 分享/另存。
 */
object LogExporter {

    /** §9.5 冻结：FileProvider authority 字面量 */
    const val AUTHORITY = "com.example.webrtcdemo.fileprovider"

    /** §9.5 冻结：导出子目录（位于 `<filesDir>/logs/` 下） */
    const val EXPORT_DIR_NAME = "export"

    /** §9.5 冻结：导出 zip 文件名前缀 */
    const val ZIP_PREFIX = "webrtcdemo-logs-"

    /** 导出 zip 的 MIME 类型 */
    const val ZIP_MIME = "application/zip"

    /** 环境信息条目名（§9.5 冻结） */
    const val DEVICE_INFO_ENTRY = "device-info.txt"

    /** 会话摘要条目名（§9.5 冻结） */
    const val SESSION_SUMMARY_ENTRY = "session-summary.txt"

    /** §9.5 冻结：export/ 只保留最近 3 个 zip */
    const val MAX_EXPORTS = 3

    /** 打包进 zip 的日志文件名模式（§9.5） */
    private val LOG_NAME_PATTERNS = listOf("app", "native", "webrtc")

    /** 打包进 zip 的附加文件（§9.5） */
    private const val BITRATE_CSV = "encoder_bitrate.csv"

    private val STAMP_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss'Z'", Locale.US)

    /**
     * 会话摘要提供者（由 t8 在会话建立后设置）。
     *
     * 契约 §9.5 要求 session-summary.txt 含 roomId、角色、ICE 结果、encoderImplementation。
     * 这些信息属 t8 的领域模型，故通过该回调注入，避免本文件反向依赖契约类型。
     */
    @Volatile
    var sessionSummaryProvider: (() -> String)? = null

    /**
     * 导出结果。
     */
    sealed interface Result {
        /**
         * 成功。
         *
         * @property zipFile 生成的 zip 文件（位于 logs/export/）。
         * @property uri FileProvider 暴露的 content:// URI。
         * @property fileCount 打包的日志文件个数（不含 device-info/session-summary）。
         * @property totalBytes 打包的日志原始字节数（未压缩）。
         */
        data class Success(
            val zipFile: File,
            val uri: Uri,
            val fileCount: Int,
            val totalBytes: Long,
        ) : Result

        /** 没有可导出的日志（友好提示，不视为错误）。 */
        data object NoLogs : Result

        /**
         * 失败。
         *
         * @property message 人类可读原因。
         * @property cause 原始异常，可为 null。
         */
        data class Failure(val message: String, val cause: Throwable? = null) : Result
    }

    /** 日志根目录 `<filesDir>/logs/`（§9.2）。 */
    fun logDir(context: Context): File = AppLog.logDir(context)

    /** 导出目录 `<filesDir>/logs/export/`（§9.5），不保证已创建。 */
    fun exportDir(context: Context): File = File(logDir(context), EXPORT_DIR_NAME)

    /** 按当前时间生成 zip 文件名：`webrtcdemo-logs-<yyyyMMdd-HHmmssZ>.zip`（UTC）。 */
    fun zipFileName(now: OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC)): String =
        "$ZIP_PREFIX${STAMP_FORMAT.format(now)}.zip"

    /**
     * 导出日志 zip（**阻塞**，必须在后台线程调用；UI 请用 [exportZip]）。
     *
     * @param context 任意 Context。
     * @return [Result]；无日志返回 [Result.NoLogs]，异常返回 [Result.Failure]。
     */
    fun exportBlocking(context: Context): Result {
        // 第 1 步：flush（§9.5 第 1 步硬性要求：native 与 Kotlin **两层都要**）。
        FileLogger.get()?.flush()
        // native 层 flush（§6.2「导出前必调」）：未初始化/库缺失时静默返回，不抛异常。
        // 收敛在此处，故首页 / 通话页 / 诊断页三个导出入口都自动满足 §9.5 第 1 步。
        NativeLog.flush()

        // 第 2 步：收集 logs/ 下需要打包的文件（不含 export/ 子目录）
        val dir = logDir(context)
        val logFiles: List<File> =
            if (dir.isDirectory) {
                (dir.listFiles() ?: emptyArray())
                    .filter { it.isFile && shouldPackage(it.name) }
                    .sortedBy { it.name }
            } else {
                emptyList()
            }
        if (logFiles.isEmpty()) return Result.NoLogs

        val exportDir = exportDir(context)
        if (!exportDir.isDirectory && !exportDir.mkdirs()) {
            return Result.Failure("无法创建导出目录: ${exportDir.absolutePath}")
        }
        val zipFile = File(exportDir, zipFileName())

        return try {
            var totalBytes = 0L
            ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zip ->
                for (file in logFiles) {
                    zip.putNextEntry(ZipEntry(file.name))
                    FileInputStream(file).use { input -> input.copyTo(zip) }
                    zip.closeEntry()
                    totalBytes += file.length()
                }
                writeEntry(zip, DEVICE_INFO_ENTRY, buildDeviceInfo(context))
                writeEntry(zip, SESSION_SUMMARY_ENTRY, buildSessionSummary())
            }
            // 第 4 步：只保留最近 3 个
            trimOldExports(context)
            val uri = FileProvider.getUriForFile(context, AUTHORITY, zipFile)
            AppLog.i(
                TAG,
                "export_zip",
                mapOf(
                    "zip" to zipFile.name,
                    "files" to logFiles.size.toString(),
                    "bytes" to totalBytes.toString(),
                )
            )
            Result.Success(zipFile, uri, logFiles.size, totalBytes)
        } catch (t: Throwable) {
            if (zipFile.exists() && !zipFile.delete()) {
                AppLog.w(TAG, "export_cleanup_failed", mapOf("zip" to zipFile.name))
            }
            AppLog.e(TAG, "export_failed", mapOf("reason" to (t.message ?: t.javaClass.simpleName)))
            Result.Failure(t.message ?: t.javaClass.simpleName, t)
        }
    }

    /**
     * 导出日志 zip 的挂起版本，自动切到 IO 线程，UI 可直接调用。
     *
     * @param context 任意 Context。
     */
    suspend fun exportZip(context: Context): Result = withContext(Dispatchers.IO) {
        exportBlocking(context)
    }

    /**
     * 清理过旧的导出 zip，只保留最近 [MAX_EXPORTS] 个（§9.5 第 4 步）。
     *
     * 建议在 `WebRtcDemoApp.onCreate` 调用一次。
     *
     * @return 删除的文件个数。
     */
    fun cleanupOldExports(context: Context): Int = trimOldExports(context)

    /**
     * 构造 ACTION_SEND 分享意图（已包一层选择器）。
     *
     * @param context 用于取字符串资源。
     * @param uri [Result.Success.uri]。
     * @param zipName zip 文件名（主题与 ClipData 标签）。
     */
    fun buildShareChooser(context: Context, uri: Uri, zipName: String): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = ZIP_MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, zipName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // 部分接收方只读 ClipData，补上以提升兼容性
            clipData = ClipData.newRawUri(zipName, uri)
        }
        return Intent.createChooser(send, context.getString(R.string.log_export_share_title))
    }

    private const val TAG = "export"

    /** 是否属于 §9.5 的打包范围：`app*` / `native*` / `webrtc*` / `encoder_bitrate.csv` */
    private fun shouldPackage(name: String): Boolean =
        name == BITRATE_CSV || LOG_NAME_PATTERNS.any { name.startsWith(it) }

    private fun writeEntry(zip: ZipOutputStream, entryName: String, content: String) {
        zip.putNextEntry(ZipEntry(entryName))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    /** 删除最早的历史导出，只留最近 [MAX_EXPORTS] 个 */
    private fun trimOldExports(context: Context): Int {
        val dir = exportDir(context)
        if (!dir.isDirectory) return 0
        val zips = (dir.listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.startsWith(ZIP_PREFIX) && it.name.endsWith(".zip") }
            .sortedByDescending { it.name }
        var deleted = 0
        for (index in zips.indices) {
            if (index < MAX_EXPORTS) continue
            if (zips[index].delete()) deleted++
        }
        if (deleted > 0) {
            AppLog.i(TAG, "export_cleanup", mapOf("deleted" to deleted.toString()))
        }
        return deleted
    }

    /** §9.5 device-info.txt：型号 / SDK / ABI / 信令地址 / 日志配置 */
    private fun buildDeviceInfo(context: Context): String = buildString {
        appendLine("app=${context.packageName}")
        appendLine("version=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})")
        appendLine("build_type=${BuildConfig.BUILD_TYPE} debug=${BuildConfig.DEBUG}")
        appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("android=${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("abi=${Build.SUPPORTED_ABIS.joinToString(",")}")
        appendLine("signaling_url=${BuildConfig.SIGNALING_URL}")
        appendLine("libwebrtc_head=unknown(t5 产出后由构建期回填)")
        appendLine("log_dir=${logDir(context).absolutePath}")
        appendLine("log_level=${AppLog.level().label}(${AppLog.level().code})")
        appendLine("exported_at=${OffsetDateTime.now(ZoneOffset.UTC)}")
    }

    /** §9.5 session-summary.txt：由 t8 通过 [sessionSummaryProvider] 提供 */
    private fun buildSessionSummary(): String =
        sessionSummaryProvider?.invoke()
            ?: "session-summary: 尚未由 android 层设置（t8 在会话建立后设置 LogExporter.sessionSummaryProvider）\n"
}
