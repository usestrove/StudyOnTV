package com.studyontv.tv

import org.json.JSONObject

/** 学习状态、答题流程、统计分析、历史记录与多孩子管理路由。 */
internal fun LanServer.registerQuizRoutes() {
    // 学习页轮询只取页面需要的数据，保留家长面板完整的 /api/state。
    router.get("/api/learning/state") { ctx ->
        if (!ctx.deviceAccess) return@get json(ctx, 403, err("forbidden"))
        val config = store.config()
        val speech = SpeechReader.settings(store)
        val learning = DeviceRuntime.learning.status()
        val page = JSONObject()
            .put("config", config)
            .put("learning", learning)
            .put("children", store.children()).put("activeChild", store.activeChild())
            .put("speech", JSONObject().put("autoRead", speech.optBoolean("autoRead"))
                .put("readScope", speech.optString("readScope", "question")))
        if (ctx.query["study"] == "1" || !config.optBoolean("supervisionEnabled", true) ||
            learning.optBoolean("free")) {
            page.put("study", store.studySummary())
        }
        json(ctx, 200, page)
    }
    router.get("/api/state") { ctx ->
        DeviceRuntime.learning.flushUsage()
        val learning = DeviceRuntime.learning.status()
        json(ctx, 200, JSONObject()
            .put("config", store.config())
            .put("today", store.todayStats())
            .put("questions", store.countQuestions())
            .put("roundsDoneToday", learning.getInt("roundsDoneToday"))
            .put("repetition", store.repetitionStats())
            .put("study", store.studySummary())
            .put("learning", learning)
            .put("children", store.children()).put("activeChild", store.activeChild())
            .put("speech", JSONObject()
                .put("autoRead", SpeechReader.settings(store).optBoolean("autoRead"))
                .put("readScope", SpeechReader.settings(store).optString("readScope", "question"))))
    }
    router.get("/api/analytics") { ctx ->
        DeviceRuntime.learning.flushUsage()
        val days = ctx.query["days"]?.toIntOrNull() ?: 14
        json(ctx, 200, store.learningAnalytics(days))
    }
    router.get("/api/children") { ctx ->
        json(ctx, 200, JSONObject().put("items", store.children()).put("active", store.activeChild()))
    }
    router.post("/api/children") { ctx ->
        val input = JSONObject(ctx.body)
        val child = store.createChild(input.getString("name"), LanServer.extractOptionalPin(input))
        json(ctx, 200, JSONObject().put("ok", true).put("child", child).put("items", store.children()))
    }
    router.put("/api/children") { ctx ->
        val input = JSONObject(ctx.body)
        store.updateChild(input.getString("id"), input.getString("name"), LanServer.extractOptionalPin(input))
        json(ctx, 200, JSONObject().put("ok", true).put("items", store.children()))
    }
    router.post("/api/children/switch") { ctx ->
        val input = JSONObject(ctx.body)
        val targetId = input.optString("id")
        val decision = LanServer.evaluateChildSwitch(
            targetId = targetId,
            activeId = store.activeChild(),
            isParent = ctx.parent,
            targetHasPin = store.childHasPin(targetId),
            pin = LanServer.extractOptionalPin(input),
            verifyChildPin = { store.verifyChildPin(targetId, it) },
            verifyParentPassword = { AdminAuth.verify(context, it) }
        )
        if (!decision.allowed) {
            return@post json(
                ctx,
                403,
                err(decision.errorCode ?: "child_pin_invalid")
                    .put("message", decision.message ?: "切换密码错误，请重试")
            )
        }
        DeviceRuntime.learning.switchChild(targetId)
        GateService.refreshNow()
        json(ctx, 200, JSONObject().put("ok", true).put("items", store.children())
            .put("active", store.activeChild()))
    }
    router.post("/api/children/delete") { ctx ->
        DeviceRuntime.learning.deleteChild(JSONObject(ctx.body).optString("id"))
        GateService.refreshNow()
        json(ctx, 200, JSONObject().put("ok", true).put("items", store.children())
            .put("active", store.activeChild()))
    }
    router.get("/api/history") { ctx ->
        DeviceRuntime.learning.flushUsage()
        val days = ctx.query["days"]?.toIntOrNull() ?: 7
        json(ctx, 200, JSONObject().put("items", store.historyDays(days)))
    }
    router.get("/api/events") { ctx ->
        json(ctx, 200, JSONObject().put("items", store.recentEvents(ctx.query["limit"]?.toIntOrNull() ?: 50)))
    }
    router.post("/api/quiz/start") { ctx ->
        if (!ctx.deviceAccess) return@post json(ctx, 403, err("device_only"))
        json(ctx, 200, DeviceRuntime.learning.start(JSONObject(ctx.body.ifBlank { "{}" })))
    }
    router.post("/api/quiz/memorized") { ctx ->
        if (!ctx.deviceAccess) return@post json(ctx, 403, err("device_only"))
        val payload = JSONObject(ctx.body.ifBlank { "{}" })
        json(ctx, 200, DeviceRuntime.learning.memorized(payload.optString("roundId")))
    }
    router.post("/api/quiz/rate") { ctx ->
        if (!ctx.deviceAccess) return@post json(ctx, 403, err("device_only"))
        val payload = JSONObject(ctx.body)
        json(ctx, 200, DeviceRuntime.learning.rateFlashcard(
            payload.optString("roundId"),
            payload.getString("qid"),
            payload.optInt("quality", 4)
        ))
    }
    router.post("/api/quiz/continue") { ctx ->
        if (!ctx.deviceAccess) return@post json(ctx, 403, err("device_only"))
        json(ctx, 200, DeviceRuntime.learning.continueFeedback())
    }
    router.post("/api/quiz/review") { ctx ->
        if (!ctx.deviceAccess) return@post json(ctx, 403, err("device_only"))
        json(ctx, 200, DeviceRuntime.learning.acknowledgeReview(JSONObject(ctx.body).getString("reviewId")))
    }
    router.post("/api/quiz/skip") { ctx ->
        if (!ctx.deviceAccess) return@post json(ctx, 403, err("device_only"))
        json(ctx, 200, DeviceRuntime.learning.skipPending())
    }
    router.post("/api/quiz/answer") { ctx ->
        if (!ctx.deviceAccess) return@post json(ctx, 403, err("device_only"))
        val result = DeviceRuntime.learning.answer(JSONObject(ctx.body))
        Diag.info("Quiz", "answer correct=${result.optBoolean("correct")} finished=${result.optBoolean("finished")} pending=${result.optBoolean("pending")} reward=${result.optDouble("rewardMinutes", 0.0)}")
        if (result.optBoolean("pending")) GradingService.enqueue(store, DeviceRuntime.learning, result.getString("reviewId"))
        json(ctx, 200, result)
    }
    router.post("/api/event") { ctx ->
        val payload = try { JSONObject(ctx.body) } catch (_: Exception) { JSONObject() }
        val type = payload.optString("type")
        if (type != "study") return@post json(ctx, 403, err("event_managed_by_device"))
        store.logEvent(type, payload)
        json(ctx, 200, JSONObject().put("ok", true))
    }
}
