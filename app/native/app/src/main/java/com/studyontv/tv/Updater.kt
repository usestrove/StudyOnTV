package com.studyontv.tv

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/**
 * 版本检查：只使用蒲公英应用内更新接口（appKey + 当前版本）。
 * 不再访问 GitHub 或第三方镜像；蒲公英失败时由更新页引导用户手动下载。
 */
object Updater {
    const val OWNER_REPO = "chadyi/StudyOnTV"
    val DEFAULT_MIRRORS = emptyList<String>()
    private const val CACHE_MS = 6L * 60 * 60 * 1000

    data class Info(
        val latest: String,
        val current: String,
        val force: Boolean,
        val notes: String,
        val url: String,
        val sha256: String = "",
        val source: String = ""
    )

    fun api(ctx: Context): String = Prefs.updateApi(ctx)

    fun mirrors(ctx: Context): List<String> {
        val saved = Prefs.updateMirrors(ctx) ?: return DEFAULT_MIRRORS
        return saved.split('\n', ',', ' ').map { it.trim() }.filter { it.startsWith("https://") }
            .map { if (it.endsWith("/")) it else "$it/" }.distinct()
    }

    /** 可用的 APK 下载地址 */
    fun downloadCandidates(ctx: Context, url: String): List<Pair<String, String>> =
        listOf("蒲公英" to url)

    fun check(ctx: Context, forceRefresh: Boolean = false, onResult: (Info?) -> Unit) {
        if (LocalOnlyGuard.enabled) {
            onResult(null)
            return
        }
        val cachedAt = Prefs.updateCheckedAt(ctx)
        val cached = cachedInfo(ctx)
        if (!forceRefresh && cached != null && System.currentTimeMillis() - cachedAt < CACHE_MS) {
            preDownloadIfNeeded(ctx, cached)
            onResult(cached)
            return
        }
        Thread { onResult(checkNow(ctx).first) }.apply { isDaemon = true }.start()
    }

    private val checkingFlag = java.util.concurrent.atomic.AtomicBoolean(false)
    val checking: Boolean get() = checkingFlag.get()

    /** Web 端一键在线更新的线程安全状态机快照（ST-603 / #184）。 */
    data class InstallSnapshot(
        val state: String = "idle",      // "idle" | "downloading" | "installing" | "error"
        val progress: Int = 0,           // 0..100
        val message: String = "",
        val version: String = "",
        val updatedAtMs: Long = 0L
    )

    internal const val INSTALLING_TTL_MS = 60_000L
    internal const val INSTALLING_RETRY_COOLDOWN_MS = 15_000L

    const val SILENT_DEVICE_OWNER = "device_owner"
    const val SILENT_SELF_UPDATE = "self_update"
    const val SILENT_CONFIRM = "confirm"

    /**
     * 判断当前设备的官方合规静默更新能力（ST-607）：
     * Device Owner -> "device_owner"；Android 12+ 且同源安装 -> "self_update"；其余 -> "confirm"。
     */
    internal fun resolveSilentCapability(
        isDeviceOwner: Boolean,
        sdkInt: Int,
        installerPackage: String?,
        ownPackage: String
    ): String = when {
        isDeviceOwner -> SILENT_DEVICE_OWNER
        sdkInt >= 31 && ownPackage.isNotBlank() && installerPackage == ownPackage -> SILENT_SELF_UPDATE
        else -> SILENT_CONFIRM
    }

    fun silentCapability(ctx: Context): String {
        val isOwner = try { UninstallGuard.owner(ctx) } catch (_: Exception) { false }
        val ownPkg = try { ctx.packageName.orEmpty() } catch (_: Exception) { "" }
        val installerPkg = if (Build.VERSION.SDK_INT >= 30 && ownPkg.isNotEmpty()) {
            try {
                ctx.packageManager.getInstallSourceInfo(ownPkg).installingPackageName
            } catch (_: Exception) {
                null
            }
        } else null
        return resolveSilentCapability(isOwner, Build.VERSION.SDK_INT, installerPkg, ownPkg)
    }

    internal fun checkApkReady(
        fileExists: Boolean,
        fileLength: Long,
        expectedVersion: String,
        expectedSha256: String,
        savedVersion: String,
        savedSha256: String
    ): Boolean = UpdateDownload.checkApkReady(fileExists, fileLength, expectedVersion, expectedSha256, savedVersion, savedSha256)

    fun isApkReady(ctx: Context, info: Info?): Boolean = UpdateDownload.isApkReady(ctx, info)
    internal fun markApkReady(ctx: Context, version: String, sha256: String) = UpdateDownload.markApkReady(ctx, version, sha256)
    internal fun clearReadyApk(ctx: Context) = UpdateDownload.clearReadyApk(ctx)
    internal fun resolveAutoDownloadSetting(requested: Boolean, localOnly: Boolean): Boolean =
        UpdateDownload.resolveAutoDownloadSetting(requested, localOnly)
    internal fun shouldPreDownload(localOnly: Boolean, autoDownload: Boolean, newerInfo: Info?, apkReady: Boolean): Boolean =
        UpdateDownload.shouldPreDownload(localOnly, autoDownload, newerInfo, apkReady)
    internal fun shouldPromptOnDevice(autoDownload: Boolean, apkReady: Boolean, force: Boolean): Boolean =
        UpdateDownload.shouldPromptOnDevice(autoDownload, apkReady, force)
    internal fun canRetryPreDownload(lastAttemptMs: Long, lastVersion: String, targetVersion: String, nowMs: Long): Boolean =
        UpdateDownload.canRetryPreDownload(lastAttemptMs, lastVersion, targetVersion, nowMs)
    fun addApkReadyListener(listener: (Info) -> Unit) = UpdateDownload.addApkReadyListener(listener)
    fun removeApkReadyListener(listener: (Info) -> Unit) = UpdateDownload.removeApkReadyListener(listener)

    internal fun currentInstallSnapshot(nowMs: Long = System.currentTimeMillis()): InstallSnapshot =
        UpdateDownload.currentInstallSnapshot(nowMs)
    internal fun resetInstallState() = UpdateDownload.resetInstallState()
    internal fun updateInstallState(
        state: String,
        progress: Int,
        message: String,
        version: String,
        nowMs: Long = System.currentTimeMillis()
    ): InstallSnapshot = UpdateDownload.updateInstallState(state, progress, message, version, nowMs)
    internal fun compareAndUpdateInstallState(
        expected: InstallSnapshot,
        state: String,
        progress: Int,
        message: String,
        version: String,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean = UpdateDownload.compareAndUpdateInstallState(expected, state, progress, message, version, nowMs)
    internal fun onInstallPendingUserAction(nowMs: Long = System.currentTimeMillis()) =
        UpdateDownload.onInstallPendingUserAction(nowMs)
    internal fun onInstallResult(success: Boolean, message: String = "", nowMs: Long = System.currentTimeMillis()) =
        UpdateDownload.onInstallResult(success, message, nowMs)
    internal fun validateRemoteInstallPrecondition(localOnly: Boolean, newerInfo: Info?): Pair<Int, String>? =
        UpdateDownload.validateRemoteInstallPrecondition(localOnly, newerInfo)
    internal fun tryBeginInstall(
        version: String,
        nowMs: Long = System.currentTimeMillis(),
        message: String = "正在准备下载 v$version…"
    ): Boolean = UpdateDownload.tryBeginInstall(version, nowMs, message)
    fun preDownloadIfNeeded(ctx: Context, info: Info?, forceRetry: Boolean = false) =
        UpdateDownload.preDownloadIfNeeded(ctx, info, forceRetry)
    internal fun appendInstallStatus(target: JSONObject, snap: InstallSnapshot = currentInstallSnapshot()): JSONObject =
        UpdateDownload.appendInstallStatus(target, snap)
    fun startRemoteInstall(ctx: Context): Pair<Int, JSONObject> = UpdateDownload.startRemoteInstall(ctx)

    /** 管理面板“立即检查”：后台执行，面板轮询 status()（不能加锁，否则会等正在进行的检查） */
    fun startCheck(ctx: Context) {
        if (LocalOnlyGuard.enabled) return
        if (!checkingFlag.compareAndSet(false, true)) return
        Thread { try { checkNow(ctx) } finally { checkingFlag.set(false) } }.apply { isDaemon = true }.start()
    }

    /** 同步检查；返回结果和每个来源的尝试记录（给管理面板显示） */
    @Synchronized
    fun checkNow(ctx: Context): Pair<Info?, JSONArray> {
        val log = JSONArray()
        if (LocalOnlyGuard.enabled) {
            log.put(JSONObject().put("source", "LocalOnly").put("ok", false).put("ms", 0)
                .put("message", "仅本地隐私版（EU Local-Only）已禁用在线更新"))
            Prefs.setUpdateInfo(ctx, "")
            Prefs.setUpdateCheckedAt(ctx, System.currentTimeMillis())
            Prefs.setUpdateLog(ctx, JSONObject().put("at", System.currentTimeMillis()).put("items", log).toString())
            return null to log
        }
        UpdateDownload.cleanupObsoleteApk(ctx)
        val current = currentVersion(ctx)
        val started = System.currentTimeMillis()
        var info: Info? = null
        try {
            info = Pgyer.info(ctx, current, currentVersionCode(ctx))
            log.put(JSONObject().put("source", "蒲公英").put("ok", true).put("ms", System.currentTimeMillis() - started)
                .put("message", if (info == null) "当前已是最新版本" else "v" + info.latest))
        } catch (e: Exception) {
            Diag.error("Update", "Pgyer check failed", e)
            log.put(JSONObject().put("source", "蒲公英").put("ok", false).put("ms", System.currentTimeMillis() - started)
                .put("message", e.message?.take(160) ?: e.javaClass.simpleName))
        }
        Prefs.setUpdateInfo(ctx, info?.let { toJson(it).toString() } ?: "")
        Prefs.setUpdateCheckedAt(ctx, System.currentTimeMillis())
        Prefs.setUpdateLog(ctx, JSONObject().put("at", System.currentTimeMillis()).put("items", log).toString())
        preDownloadIfNeeded(ctx, info)
        return info to log
    }

    fun toJson(info: Info): JSONObject = JSONObject()
        .put("latest", info.latest).put("current", info.current).put("force", info.force)
        .put("notes", info.notes).put("url", info.url).put("sha256", info.sha256).put("source", info.source)

    internal fun effectiveCachedInfo(cached: Info?, localOnly: Boolean): Info? = if (localOnly) null else cached

    fun cachedInfo(ctx: Context): Info? {
        if (LocalOnlyGuard.enabled) return null
        val raw = Prefs.updateInfo(ctx)
        if (raw.isEmpty()) return null
        return try {
            val o = JSONObject(raw)
            Info(o.optString("latest"), o.optString("current"), o.optBoolean("force"),
                o.optString("notes"), o.optString("url"), o.optString("sha256"), o.optString("source"))
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 比较远程版本是否比本地版本更新（遵循语义化版本 SemVer）：
     * 主版本号段 `[major, minor, patch]` 与先行版后缀分开解析，确保正式版高于同号内测版。
     */
    fun isNewer(latest: String, current: String): Boolean {
        val remoteClean = latest.trim().removePrefix("v").removePrefix("V").substringBefore('+')
        val localClean = current.trim().removePrefix("v").removePrefix("V").substringBefore('+')
        if (remoteClean.isEmpty()) return false
        val remoteCore = remoteClean.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val localCore = localClean.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(remoteCore.size, localCore.size)) {
            val x = remoteCore.getOrElse(i) { 0 }
            val y = localCore.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        val remotePre = remoteClean.substringAfter('-', "")
        val localPre = localClean.substringAfter('-', "")
        if (remotePre.isEmpty() && localPre.isEmpty()) return false
        if (remotePre.isEmpty() && localPre.isNotEmpty()) return true
        if (remotePre.isNotEmpty() && localPre.isEmpty()) return false
        val rParts = remotePre.split('.', '-')
        val lParts = localPre.split('.', '-')
        for (i in 0 until minOf(rParts.size, lParts.size)) {
            val rp = rParts[i]
            val lp = lParts[i]
            if (rp == lp) continue
            val rn = rp.toIntOrNull()
            val ln = lp.toIntOrNull()
            return when {
                rn != null && ln != null -> rn > ln
                rn != null -> false
                ln != null -> true
                else -> rp > lp
            }
        }
        return rParts.size > lParts.size
    }

    fun currentVersion(ctx: Context): String =
        try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0.0.0" } catch (_: Exception) { "0.0.0" }

    fun currentVersionCode(ctx: Context): Int = try {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else @Suppress("DEPRECATION") info.versionCode
    } catch (_: Exception) { 0 }

    fun newerThanCurrent(ctx: Context, info: Info?): Info? =
        if (info != null && isNewer(info.latest, currentVersion(ctx))) info else null

    /** 管理面板用：当前状态 */
    fun status(ctx: Context): JSONObject {
        val info = effectiveCachedInfo(cachedInfo(ctx), LocalOnlyGuard.enabled)
        val hasUpdate = newerThanCurrent(ctx, info) != null
        val snap = currentInstallSnapshot().let { cur ->
            if (!hasUpdate && cur.state == "installing") {
                resetInstallState()
                InstallSnapshot()
            } else cur
        }
        val autoDownload = resolveAutoDownloadSetting(Prefs.autoDownloadUpdate(ctx), LocalOnlyGuard.enabled)
        val apkReady = hasUpdate && isApkReady(ctx, info)
        val downloadedVersion = if (apkReady) info?.latest.orEmpty() else ""
        val log = try { JSONObject(Prefs.updateLog(ctx)) } catch (_: Exception) { JSONObject() }
        return appendInstallStatus(
            JSONObject().put("current", currentVersion(ctx))
                .put("latest", info?.latest ?: "").put("hasUpdate", hasUpdate)
                .put("notes", info?.notes ?: "").put("source", info?.source ?: "")
                .put("url", info?.url ?: "")
                .put("checkedAt", Prefs.updateCheckedAt(ctx)).put("checking", checking)
                .put("logAt", log.optLong("at"))
                .put("api", api(ctx)).put("mirrors", JSONArray(mirrors(ctx))).put("defaultMirrors", JSONArray(DEFAULT_MIRRORS))
                .put("lastDownloadSource", Prefs.updateLastSource(ctx))
                .put("canInstall", ApkInstaller.canInstall(ctx))
                .put("installError", Prefs.updateInstallError(ctx))
                .put("autoDownload", autoDownload)
                .put("apkReady", apkReady)
                .put("downloadedVersion", downloadedVersion)
                .put("silentCapability", silentCapability(ctx))
                .put("log", log.optJSONArray("items") ?: JSONArray()),
            snap
        )
    }

    fun saveSettings(ctx: Context, input: JSONObject): JSONObject {
        if (input.has("api")) {
            val api = input.optString("api").trim()
            require(api.isEmpty() || api.startsWith("https://")) { "自定义更新地址需要以 https:// 开头" }
            Prefs.setUpdateApi(ctx, api)
        }
        if (input.has("mirrors")) {
            val raw = input.get("mirrors")
            val list = if (raw is JSONArray) (0 until raw.length()).map { raw.optString(it) } else raw.toString().split('\n', ',')
            val clean = list.map { it.trim() }.filter { it.isNotEmpty() }
            clean.forEach { require(it.startsWith("https://")) { "镜像地址需要以 https:// 开头：$it" } }
            require(clean.size <= 10) { "最多填写 10 个镜像" }
            Prefs.setUpdateMirrors(ctx, if (input.optBoolean("resetMirrors")) null else clean.joinToString("\n"))
        }
        if (input.has("autoDownload")) {
            val effective = resolveAutoDownloadSetting(input.optBoolean("autoDownload", false), LocalOnlyGuard.enabled)
            Prefs.setAutoDownloadUpdate(ctx, effective)
            if (effective) {
                preDownloadIfNeeded(ctx, cachedInfo(ctx), forceRetry = true)
            }
        }
        if (input.has("api") || input.has("mirrors")) {
            Prefs.setUpdateCheckedAt(ctx, 0)
        }
        return status(ctx)
    }
}
