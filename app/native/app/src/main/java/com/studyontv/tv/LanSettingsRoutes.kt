package com.studyontv.tv

import org.json.JSONObject
import java.util.Calendar

/** 学习规则、节假日、朗读、AI 批改模型与应用更新设置路由。 */
internal fun LanServer.registerSettingsRoutes() {
    router.get("/api/holidays") { ctx ->
        json(ctx, 200, HolidayService.info(context))
    }
    router.post("/api/holidays/sync") { ctx ->
        val year = ctx.query["year"]?.toIntOrNull() ?: DeviceClock.calendar(store).get(Calendar.YEAR)
        try {
            json(ctx, 200, HolidayService.fetch(context, year))
        } catch (e: Exception) {
            json(ctx, 200, err("holiday_failed").put("message", e.message ?: "节假日同步失败"))
        }
    }
    router.put("/api/config") { ctx ->
        val input = try { JSONObject(ctx.body) } catch (_: Exception) { return@put json(ctx, 200, err("bad_json")) }
        val config = DeviceRuntime.learning.saveConfig(input)
        GateService.refreshNow()
        json(ctx, 200, JSONObject().put("config", config))
    }
    router.get("/api/speech") { ctx -> json(ctx, 200, SpeechReader.settings(store)) }
    router.put("/api/speech") { ctx -> json(ctx, 200, SpeechReader.save(store, JSONObject(ctx.body))) }
    router.get("/api/speech/engines") { ctx ->
        json(ctx, 200, JSONObject().put("engines", TtsProbe.installedEngines(context))
            .put("defaultEngine", TtsProbe.defaultEngine(context)))
    }
    router.get("/api/speech/probe") { ctx ->
        json(ctx, 200, TtsProbe.probe(context, ctx.query["language"].orEmpty().ifBlank { "zh-CN" }))
    }
    router.post("/api/speech/install-voice") { ctx ->
        json(ctx, 200, JSONObject().put("ok", TtsProbe.openInstallVoiceData(context)))
    }
    router.post("/api/speech/test") { ctx ->
        val text = try { JSONObject(ctx.body.ifEmpty { "{}" }).optString("text").trim() } catch (_: Exception) { "" }
        SpeechReader.read(context, text.ifBlank { "今天天气不错" }.take(200))
        val started = System.currentTimeMillis()
        while (System.currentTimeMillis() - started < 800 && SpeechReader.status().optString("reason") == "idle") Thread.sleep(50)
        json(ctx, 200, SpeechReader.status())
    }
    router.get("/api/grading") { ctx -> json(ctx, 200, GradingService.settings(store).put("providers", GradingService.providers())) }
    router.put("/api/grading") { ctx -> json(ctx, 200, GradingService.save(store, JSONObject(ctx.body))) }
    router.post("/api/grading/test") { ctx -> json(ctx, 200, GradingService.test(store, JSONObject(ctx.body.ifEmpty { "{}" }))) }
    router.post("/api/grading/models") { ctx -> json(ctx, 200, GradingService.models(store, JSONObject(ctx.body.ifEmpty { "{}" }))) }
    router.get("/api/update") { ctx -> json(ctx, 200, Updater.status(context)) }
    router.put("/api/update") { ctx -> json(ctx, 200, Updater.saveSettings(context, JSONObject(ctx.body))) }
    router.post("/api/update/check") { ctx ->
        Updater.startCheck(context)
        json(ctx, 200, Updater.status(context))
    }
    router.post("/api/update/install") { ctx ->
        val (code, payload) = Updater.startRemoteInstall(context)
        json(ctx, code, payload)
    }
}
