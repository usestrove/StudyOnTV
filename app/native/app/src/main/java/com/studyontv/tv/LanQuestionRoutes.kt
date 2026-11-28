package com.studyontv.tv

import org.json.JSONArray
import org.json.JSONObject

/** 题库管理、错题本与加练池、在线题库广场/自动同步以及主观题批改路由。 */
internal fun LanServer.registerQuestionRoutes() {
    router.post("/api/questions/generate") { ctx ->
        val input = try { JSONObject(ctx.body.ifEmpty { "{}" }) } catch (_: Exception) { return@post json(ctx, 200, err("bad_json")) }
        try {
            json(ctx, 200, GradingService.generateQuestions(store, input))
        } catch (e: Exception) {
            json(ctx, 200, err("generate_failed").put("message", e.message ?: "生成失败，请稍后重试"))
        }
    }
    router.get("/api/questions") { ctx ->
        val q = ctx.query
        val all = q["all"] == "1" || q["enabledOnly"] == "false"
        json(ctx, 200, store.questionPage(
            subject = q["subject"], level = q["level"], bank = q["bank"], search = q["search"],
            type = q["type"], difficulty = q["difficulty"]?.toIntOrNull(),
            wrongOnly = q["wrong"] == "1", enabledOnly = !all,
            sortBy = q["sort"] ?: "random",
            limit = q["limit"]?.toIntOrNull() ?: 500,
            offset = q["offset"]?.toIntOrNull() ?: 0,
            inlineMedia = q["inlineMedia"] == "1"
        ))
    }
    router.get("/api/questions/meta") { ctx ->
        json(ctx, 200, store.questionMeta())
    }
    router.get("/api/coverage") { ctx ->
        val gradeSubjects = LocalStore.parseGradeSubjects(store.config().optJSONObject("gradeSubjects"))
        json(ctx, 200, store.coverage(ctx.query["sample"]?.toIntOrNull()?.coerceIn(1, 100) ?: 20, gradeSubjects = gradeSubjects))
    }
    router.get("/api/wrong-questions") { ctx ->
        val q = ctx.query
        json(ctx, 200, store.listWrongQuestions(
            subject = q["subject"],
            level = q["level"],
            bank = q["bank"],
            q = q["q"]
        ))
    }
    router.post("/api/wrong-questions/master") { ctx ->
        val input = JSONObject(ctx.body.ifEmpty { "{}" })
        val all = input.optBoolean("all", false)
        val idsArr = input.optJSONArray("ids")
        val ids = if (idsArr != null) (0 until idsArr.length()).map { idsArr.optString(it) }.filter { it.isNotBlank() } else emptyList()
        val updated = store.markWrongMastered(ids = ids, all = all)
        json(ctx, 200, JSONObject().put("ok", true).put("updated", updated).put("study", store.studySummary()))
    }
    router.post("/api/wrong-questions/extra-pool") { ctx ->
        val input = JSONObject(ctx.body.ifEmpty { "{}" })
        val idsArr = input.optJSONArray("ids") ?: JSONArray()
        val ids = (0 until idsArr.length()).map { idsArr.optString(it) }.filter { it.isNotBlank() }
        val result = DeviceRuntime.learning.addWrongToExtraPool(ids)
        GateService.refreshNow()
        json(ctx, 200, result)
    }
    router.post("/api/extra-pool/clear") { ctx ->
        val result = DeviceRuntime.learning.clearExtraPool()
        GateService.refreshNow()
        json(ctx, 200, result)
    }
    router.post("/api/questions/pause") { ctx ->
        val input = JSONObject(ctx.body)
        val ids = input.getJSONArray("ids")
        val enabled = input.optBoolean("enabled", false)
        require(ids.length() in 1..50000) { "请选择题目" }
        val updated = DeviceRuntime.learning.setQuestionsEnabled((0 until ids.length()).map { ids.getString(it) }, enabled)
        GateService.refreshNow()
        json(ctx, 200, JSONObject().put("ok", true).put("updated", updated))
    }
    router.post("/api/questions") { ctx ->
        val payload = try { JSONObject(ctx.body) } catch (_: Exception) { return@post json(ctx, 200, err("bad_json")) }
        if (payload.has("items")) {
            val result = store.importQuestions(payload.getJSONArray("items"), payload.optBoolean("skipDuplicates"))
            return@post json(ctx, 200, JSONObject().put("ok", true)
                .put("added", result.getInt("added")).put("skipped", result.getInt("skipped")))
        }
        try {
            val id = store.upsertQuestion(payload)
            store.logEvent("question_added", JSONObject().put("id", id))
            json(ctx, 200, JSONObject().put("ok", true).put("id", id))
        } catch (e: IllegalArgumentException) {
            json(ctx, 200, JSONObject().put("error", "bad_question").put("message", e.message ?: ""))
        }
    }
    router.get("/api/bank-automation") { ctx -> json(ctx, 200, BankAutomation.settings(store)) }
    router.put("/api/bank-automation") { ctx -> json(ctx, 200, BankAutomation.save(store, JSONObject(ctx.body))) }
    router.get("/api/banks/market") { ctx -> json(ctx, 200, BankMarket.market(store)) }
    router.post("/api/banks/sources") { ctx ->
        val payload = JSONObject(ctx.body.ifEmpty { "{}" })
        json(ctx, 200, BankMarket.addSource(store, payload.optString("name"), payload.optString("url")))
    }
    router.delete("/api/banks/sources") { ctx ->
        val payload = JSONObject(ctx.body.ifEmpty { "{}" })
        json(ctx, 200, BankMarket.removeSource(store, payload.optString("url")))
    }
    router.post("/api/banks/fetch") { ctx ->
        val payload = JSONObject(ctx.body.ifEmpty { "{}" })
        json(ctx, 200, JSONObject().put("ok", true).put("data", BankMarket.json(payload.optString("url"))))
    }
    router.post("/api/bank-automation/sync") { ctx ->
        BankAutomation.start(store)
        json(ctx, 200, BankAutomation.settings(store))
    }
    router.delete("/api/questions/bank") { ctx ->
        val input = JSONObject(ctx.body)
        require(input.optBoolean("confirm")) { "请确认删除题库" }
        val deleted = DeviceRuntime.learning.clearQuestions(input.getString("bank"))
        GateService.refreshNow()
        json(ctx, 200, JSONObject().put("deleted", deleted))
    }
    router.post("/api/questions/clear") { ctx ->
        require(JSONObject(ctx.body).optBoolean("confirm")) { "请确认清空全部题库" }
        val deleted = DeviceRuntime.learning.clearQuestions()
        GateService.refreshNow()
        json(ctx, 200, JSONObject().put("deleted", deleted))
    }
    router.post("/api/questions/delete") { ctx ->
        val ids = JSONObject(ctx.body).getJSONArray("ids")
        require(ids.length() in 1..50000) { "请选择要删除的题目" }
        val deleted = DeviceRuntime.learning.deleteQuestions((0 until ids.length()).map { ids.getString(it) })
        GateService.refreshNow()
        json(ctx, 200, JSONObject().put("ok", true).put("deleted", deleted))
    }
    router.prefixDelete("/api/questions/") { ctx ->
        val id = LanServer.decodePathSegment(ctx.path.removePrefix("/api/questions/"))
        val deleted = DeviceRuntime.learning.deleteQuestions(listOf(id))
        GateService.refreshNow()
        json(ctx, 200, JSONObject().put("ok", deleted > 0))
    }
    router.get("/api/reviews") { ctx ->
        val items = store.reviews(ctx.query["state"] ?: "pending", ctx.query["child"]?.takeIf { it.isNotEmpty() })
        // 手写图已经以 /api/media/<sha256>.png 短路径落盘，summary 视图直接标上 hasDrawing 并去掉路径字段
        if (ctx.query["view"] == "summary") for (i in 0 until items.length()) {
            val input = items.getJSONObject(i).optJSONObject("input") ?: continue
            if (input.optString("drawing").isNotEmpty()) input.put("hasDrawing", true)
            input.remove("drawing")
        }
        json(ctx, 200, JSONObject().put("items", items))
    }
    router.prefixGet("/api/reviews/") { ctx ->
        val id = LanServer.decodePathSegment(ctx.path.removePrefix("/api/reviews/").substringBefore('/'))
        val review = store.review(id) ?: return@prefixGet json(ctx, 404, err("not_found"))
        json(ctx, 200, review)
    }
    router.prefixPost("/api/reviews/") { ctx ->
        val id = LanServer.decodePathSegment(ctx.path.removePrefix("/api/reviews/").substringBefore('/'))
        if (ctx.path.endsWith("/retry")) {
            require(store.review(id) != null) { "题目不存在" }
            GradingService.enqueue(store, DeviceRuntime.learning, id)
            return@prefixPost json(ctx, 200, JSONObject().put("ok", true))
        }
        val decision = JSONObject(ctx.body)
        json(ctx, 200, DeviceRuntime.learning.grade(id, decision.getDouble("score"), decision.optString("feedback"), "parent_or_agent"))
    }
}
