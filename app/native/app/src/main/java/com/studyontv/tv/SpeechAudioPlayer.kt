package com.studyontv.tv

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * 题目音频播放与云端朗读播放管线：
 * 统一管理 AudioTrack PCM 流式播放（绕开华为等设备 MediaPlayer 播 WAV 失败）、MediaPlayer 及下一段预取线程。
 */
internal object SpeechAudioPlayer {
    private val worker = Executors.newSingleThreadExecutor()
    /** 下一分段的预合成放到独立线程，绝不堵塞当前分段在 worker 上的 PCM 播放。 */
    private val prefetchWorker = Executors.newSingleThreadExecutor()
    private var player: MediaPlayer? = null
    /** stopNow 暂停打断，playPcm 的 finally 唯一负责释放 AudioTrack */
    private val trackLock = Any()
    @Volatile private var track: AudioTrack? = null
    private var audioFile: File? = null

    /**
     * 决定朗读/听力播放所使用的 AudioAttributes.USAGE：
     * - 平时走 USAGE_MEDIA，确保华为智慧屏、TCL 等电视经扬声器 / HDMI 正常出声；
     * - 当 MediaPause 因后台播放器无视暂停键而静音 STREAM_MUSIC 时（#196），
     *   动态切到当前设备有声且未静音的独立音频流，避免连带把答题朗读和听力也静音。
     */
    internal fun resolveAudioUsage(
        musicMuted: Boolean,
        sdkInt: Int,
        accessibilityAudible: Boolean,
        alarmAudible: Boolean,
        notificationAudible: Boolean
    ): Int {
        if (!musicMuted) return AudioAttributes.USAGE_MEDIA
        if (sdkInt >= 26 && accessibilityAudible) return AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY
        if (alarmAudible) return AudioAttributes.USAGE_ALARM
        if (notificationAudible) return AudioAttributes.USAGE_NOTIFICATION
        return AudioAttributes.USAGE_MEDIA
    }

    @Suppress("DEPRECATION")
    private fun isStreamAudible(audio: AudioManager, stream: Int, sdkInt: Int): Boolean = try {
        val vol = audio.getStreamVolume(stream)
        val muted = if (sdkInt >= 23) audio.isStreamMute(stream) else vol <= 0
        vol > 0 && !muted
    } catch (_: Exception) {
        false
    }

    @SuppressLint("InlinedApi")
    fun attributesFor(context: Context?): AudioAttributes {
        val usage = if (!MediaPause.levelMuted || context == null) {
            AudioAttributes.USAGE_MEDIA
        } else {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val sdk = Build.VERSION.SDK_INT
            if (audio == null) {
                AudioAttributes.USAGE_MEDIA
            } else {
                val chosen = resolveAudioUsage(
                    musicMuted = true,
                    sdkInt = sdk,
                    accessibilityAudible = sdk >= 26 && isStreamAudible(audio, AudioManager.STREAM_ACCESSIBILITY, sdk),
                    alarmAudible = isStreamAudible(audio, AudioManager.STREAM_ALARM, sdk),
                    notificationAudible = isStreamAudible(audio, AudioManager.STREAM_NOTIFICATION, sdk)
                )
                if (chosen != AudioAttributes.USAGE_MEDIA) {
                    Diag.info("Speech", "music stream muted by MediaPause, routing speech to usage=$chosen")
                }
                chosen
            }
        }
        return AudioAttributes.Builder().setUsage(usage)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    }

    fun stopNow() {
        synchronized(trackLock) {
            track?.let {
                try { it.pause(); it.flush() } catch (_: Exception) { }
            }
        }
        player?.release(); player = null; audioFile?.delete(); audioFile = null
    }

    fun playUrl(
        context: Context,
        url: String,
        request: Int,
        onDone: () -> Unit,
        onFailed: (reason: String, message: String) -> Unit
    ) {
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(attributesFor(context)); setDataSource(url)
                setOnPreparedListener {
                    if (request == SpeechReader.currentGeneration()) {
                        SpeechReader.markSpeaking(true); start()
                    } else release()
                }
                setOnCompletionListener {
                    release()
                    if (request == SpeechReader.currentGeneration()) {
                        SpeechReader.markSpeaking(false); player = null; onDone()
                    }
                }
                setOnErrorListener { _, what, extra ->
                    release()
                    if (request == SpeechReader.currentGeneration()) {
                        SpeechReader.markSpeaking(false); player = null
                    }
                    onFailed("player-error", "音频无法播放（what=$what extra=$extra），请检查网络或改用朗读")
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            player?.release(); player = null
            onFailed(
                e.javaClass.simpleName,
                "音频无法播放（${e.javaClass.simpleName} ${e.message?.take(80).orEmpty()}），请检查网络或改用朗读"
            )
        }
    }

    fun cloudPart(
        context: Context,
        main: Handler,
        parts: List<String>,
        index: Int,
        language: String,
        config: JSONObject,
        mode: String,
        request: Int
    ) {
        if (index >= parts.size || SpeechReader.currentGeneration() != request) return
        worker.execute {
            if (SpeechReader.currentGeneration() != request) return@execute
            try {
                val bytes = CloudSpeechProviders.synthWithCache(context, mode, config, language, parts[index])
                if (index + 1 < parts.size && mode in listOf("edge", "deepseek")) {
                    prefetchWorker.execute {
                        if (SpeechReader.currentGeneration() == request) {
                            try { CloudSpeechProviders.synthWithCache(context, mode, config, language, parts[index + 1]) } catch (_: Exception) { }
                        }
                    }
                }
                // DeepSeek 返回 WAV：自己解析 PCM 用 AudioTrack 直接在内存播放，绕开华为等设备 MediaPlayer 兼容问题
                val pcm = if (mode == "deepseek") WavAudio.parse(bytes) else null
                if (mode == "deepseek" && pcm == null && !WavAudio.isWave(bytes)) {
                    throw IllegalStateException("云端朗读返回的不是音频：${CloudSpeechProviders.bodySnippet(bytes)}")
                }
                if (pcm != null) {
                    main.post {
                        if (SpeechReader.currentGeneration() != request) return@post
                        playPcm(context, pcm, request, main,
                            onStarted = if (index == 0) {
                                { SpeechReader.notify(true, mode, "ok", language, language, false, "", request) }
                            } else null,
                            onDone = { cloudPart(context, main, parts, index + 1, language, config, mode, request) },
                            onError = {
                                SpeechReader.fallbackToSystem(context, parts.drop(index).joinToString(""), language, request, mode)
                            })
                    }
                    return@execute
                }
                val file = File.createTempFile("reading-", if (mode == "deepseek") ".wav" else ".mp3", context.cacheDir)
                file.writeBytes(bytes)
                main.post {
                    if (SpeechReader.currentGeneration() != request) { file.delete(); return@post }
                    try {
                        audioFile = file
                        player = MediaPlayer().apply {
                            setAudioAttributes(attributesFor(context)); setDataSource(file.absolutePath)
                            setOnCompletionListener { mp ->
                                mp.release()
                                file.delete()
                                if (SpeechReader.currentGeneration() == request) {
                                    SpeechReader.markSpeaking(false)
                                    if (player === mp) player = null
                                    if (audioFile == file) audioFile = null
                                    cloudPart(context, main, parts, index + 1, language, config, mode, request)
                                }
                            }
                            setOnErrorListener { mp, what, extra ->
                                mp.release()
                                file.delete()
                                if (SpeechReader.currentGeneration() == request) {
                                    SpeechReader.markSpeaking(false)
                                    if (player === mp) player = null
                                    if (audioFile == file) audioFile = null
                                    Diag.warn("Speech", "cloud audio error mode=$mode what=$what extra=$extra, fallback to system")
                                    SpeechReader.fallbackToSystem(context, parts.drop(index).joinToString(""), language, request, mode)
                                }
                                true
                            }
                            prepare(); SpeechReader.markSpeaking(true); start()
                            if (index == 0) SpeechReader.notify(true, mode, "ok", language, language, false, "", request)
                        }
                    } catch (e: Exception) {
                        Diag.warn("Speech", "cloud playback failed mode=$mode ${e.javaClass.simpleName}: ${e.message.orEmpty().take(120)}, fallback to system")
                        player?.release(); player = null; file.delete(); audioFile = null
                        if (SpeechReader.currentGeneration() == request) {
                            SpeechReader.fallbackToSystem(context, parts.drop(index).joinToString(""), language, request, mode)
                        }
                    }
                }
            } catch (e: Exception) {
                if (SpeechReader.currentGeneration() != request) return@execute
                val errMsg = (e.message ?: e.javaClass.simpleName).take(120)
                Diag.warn("Speech", "cloud synth failed mode=$mode ${e.javaClass.simpleName}: $errMsg, fallback to system")
                val cloudNotice = if (errMsg.startsWith("DeepSeek") || errMsg.startsWith("云端")) errMsg else "云端朗读失败：$errMsg"
                SpeechReader.notify(false, mode, "cloud-unavailable", language, "", true, cloudNotice, request)
                main.post {
                    if (SpeechReader.currentGeneration() != request) return@post
                    SpeechReader.fallbackToSystem(context, parts.drop(index).joinToString(""), language, request, mode, cloudNotice)
                }
            }
        }
    }

    fun validatePcmBufferSize(minBytes: Int): Int {
        require(minBytes > 0) { "AudioTrack minBufferSize invalid ($minBytes)" }
        return maxOf(minBytes, 16 * 1024)
    }

    fun validateAudioTrackState(state: Int) {
        if (state != AudioTrack.STATE_INITIALIZED) {
            throw IllegalStateException("AudioTrack not initialized (state=$state)")
        }
    }

    fun validatePcmWrite(written: Int, isCurrent: Boolean): Int {
        if (written > 0) return written
        if (!isCurrent) return 0
        throw IllegalStateException("AudioTrack.write failed (code=$written)")
    }

    /** 直接播放 16 位 PCM WAV（DeepSeek 朗读），顺序在 worker 线程执行。 */
    private fun playPcm(
        context: Context,
        pcm: WavAudio.Pcm,
        request: Int,
        main: Handler,
        onStarted: (() -> Unit)? = null,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) {
        worker.execute {
            var audioTrack: AudioTrack? = null
            try {
                if (request != SpeechReader.currentGeneration()) return@execute
                val mask = if (pcm.channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
                val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(pcm.sampleRate).setChannelMask(mask).build()
                val minBytes = AudioTrack.getMinBufferSize(pcm.sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
                val bufferBytes = validatePcmBufferSize(minBytes)
                val created = AudioTrack(attributesFor(context), format, bufferBytes, AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE)
                try {
                    validateAudioTrackState(created.state)
                } catch (e: Exception) {
                    try { created.release() } catch (_: Exception) { }
                    throw e
                }
                synchronized(trackLock) {
                    if (request != SpeechReader.currentGeneration()) {
                        try { created.release() } catch (_: Exception) { }
                        return@execute
                    }
                    audioTrack = created
                    track = created
                }
                SpeechReader.markSpeaking(true)
                created.play()
                if (request == SpeechReader.currentGeneration()) onStarted?.invoke()
                var offset = 0
                while (offset < pcm.data.size && request == SpeechReader.currentGeneration()) {
                    val written = created.write(pcm.data, offset, minOf(16 * 1024, pcm.data.size - offset))
                    val advance = validatePcmWrite(written, request == SpeechReader.currentGeneration())
                    if (advance == 0) break
                    offset += advance
                }
                // 等缓冲区里的最后一段播完再进入下一句；playbackHeadPosition 是无符号 32 位帧计数
                val durationMs = pcm.frames * 1000L / pcm.sampleRate.coerceAtLeast(1)
                val deadline = System.currentTimeMillis() + durationMs + 3000L
                while (request == SpeechReader.currentGeneration() &&
                    (created.playbackHeadPosition.toLong() and 0xFFFFFFFFL) < pcm.frames &&
                    System.currentTimeMillis() < deadline
                ) {
                    Thread.sleep(20)
                }
                if (request == SpeechReader.currentGeneration()) {
                    main.post { if (request == SpeechReader.currentGeneration()) onDone() }
                }
            } catch (e: Exception) {
                val message = "${e.javaClass.simpleName}: ${e.message.orEmpty().take(80)}"
                Diag.warn("Speech", "pcm playback failed $message")
                main.post { if (request == SpeechReader.currentGeneration()) onError(message) }
            } finally {
                if (request == SpeechReader.currentGeneration()) SpeechReader.markSpeaking(false)
                synchronized(trackLock) {
                    if (track === audioTrack) track = null
                    try { audioTrack?.pause(); audioTrack?.flush() } catch (_: Exception) { }
                    try { audioTrack?.release() } catch (_: Exception) { }
                }
            }
        }
    }
}
