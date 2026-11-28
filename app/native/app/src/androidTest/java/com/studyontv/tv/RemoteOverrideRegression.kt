package com.studyontv.tv

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject

/** 独立数据库与可控开机计数，不修改真实设备的规则或系统设置。 */
object RemoteOverrideRegression {
    fun run(context: Context) {
        val name = "remote-override-regression.db"
        context.deleteDatabase(name)
        var boot = 41
        fun openStore() = LocalStore(context, name, readBootCount = { boot })
        var store = openStore()
        fun engine() = LearningEngine(store)
        fun rejected(block: () -> Unit) {
            try { block(); error("invalid remote override accepted") } catch (_: IllegalArgumentException) { }
        }
        try {
            var learning = engine()
            learning.saveConfig(JSONObject().put("supervisionEnabled", true).put("allDay", true)
                .put("dailyFreeMinutes", 0).put("bootFreeMinutes", 0).put("restEnabled", false))
            learning.remote("credit", 10, "test")
            val balance = learning.status().getLong("remainingSeconds")
            val used = learning.status().getDouble("usedMinutes")
            val status = learning.remote("unlock", 0, "test", untilShutdown = true)
            check(status.getBoolean("free") && status.getBoolean("canExit"))
            check(status.getJSONObject("override").getBoolean("untilShutdown"))
            check(status.getJSONObject("override").getLong("remainingSeconds") == -1L)
            learning.consume(5000)
            check(learning.status().getLong("remainingSeconds") == balance)
            check(learning.status().getDouble("usedMinutes") == used) { "到本次关机不扣余额和用量" }

            learning.awake(false)
            learning.awake(true)
            check(learning.status().optJSONObject("override")?.getBoolean("untilShutdown") == true) { "待机唤醒不能结束解锁" }
            store.close()
            store = openStore()
            learning = engine()
            check(learning.status().optJSONObject("override")?.getBoolean("untilShutdown") == true) { "应用/数据库重建应保留解锁" }

            val original = store.get("remote_override")
            val persisted = JSONObject(original)
            check(persisted.getLong("until") < 0 && persisted.getLong("untilElapsed") < 0) {
                "旧版应用不得将该记录读成无限期解锁"
            }
            rejected { learning.remote("lock", 0, "test", untilShutdown = true) }
            rejected { learning.remote("unlock", 30, "test", untilShutdown = true) }
            rejected { learning.remote("cancel", 0, "test", untilShutdown = true) }
            check(store.get("remote_override") == original) { "无效请求不能覆盖当前解锁" }
            boot = -1
            rejected { learning.remote("unlock", 0, "test", untilShutdown = true) }
            check(store.get("remote_override") == original)
            check(learning.status().optJSONObject("override") == null) { "开机计数不可用时必须恢复规则" }

            boot = 41
            learning.remote("unlock", 0, "test", untilShutdown = true)
            boot++ // 模拟断电/重启，没有任何关机或开机广播。
            learning = engine()
            check(learning.status().optJSONObject("override") == null && store.get("remote_override").isEmpty())
            check(!learning.status().getBoolean("free")) { "下次开机应恢复规则" }
            learning.remote("unlock", 0, "test", untilShutdown = true)
            val sibling = store.createChild("另一个孩子").getString("id")
            learning.switchChild(sibling)
            learning.remote("unlock", 0, "test", untilShutdown = true)
            boot++
            check(learning.status().optJSONObject("override") == null)
            learning.switchChild(LocalStore.DEFAULT_CHILD)
            check(learning.status().optJSONObject("override") == null) { "未激活孩子的旧解锁也不能跨开机复活" }

            learning.remote("unlock", 0, "test", untilShutdown = true)
            val future = JSONObject(store.get("remote_override")).put("atElapsed", SystemClock.elapsedRealtime() + 60000)
            store.set("remote_override", future.toString())
            check(learning.status().optJSONObject("override") == null) { "单调时钟回退时恢复规则" }
            learning.remote("unlock", 0, "test", untilShutdown = true)
            learning.remote("cancel", 0, "test")
            check(learning.status().optJSONObject("override") == null) { "家长可提前取消" }
            learning.remote("unlock", 0, "test", untilShutdown = true)
            check(learning.remote("lock", 30, "test").getString("reason") == "locked")
            learning.remote("unlock", 30, "test")
            check(learning.status().optJSONObject("override")?.optBoolean("untilShutdown") == false)
            check(learning.status().optJSONObject("override")!!.getLong("remainingSeconds") in 1..1800)
            rejected { learning.remote("unlock", 0, "test") }
            learning.remote("lock", 0, "test")
            check(learning.status().optJSONObject("override")!!.getLong("remainingSeconds") == -1L) { "持续暂停兼容旧行为" }
            learning.remote("indefinite", 0, "test")
            boot++
            check(learning.status().optJSONObject("override")?.getString("mode") == "indefinite") { "无限时关闭管控仍跨重启有效" }
        } finally {
            store.close()
            context.deleteDatabase(name)
        }
    }
}
