package com.studyontv.tv

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class LocalStore(
    context: Context,
    databaseName: String = "studyontv.db",
    private val readBootCount: () -> Int = {
        try {
            android.provider.Settings.Global.getInt(context.contentResolver, "boot_count", -1)
        } catch (_: Exception) { -1 }
    },
    private val readClock: () -> DeviceClock.Reading = DeviceClock::read
) : SQLiteOpenHelper(context, databaseName, null, 11) {
    internal fun clockReading(): DeviceClock.Reading = readClock()
    internal fun bootCount(): Int = readBootCount()

    companion object {
        val DEFAULT_PRAISE = listOf("太棒了！", "答对啦！", "完全正确！", "真厉害！", "好样的，继续保持！", "漂亮！你越来越熟练了")
        val DEFAULT_ENCOURAGE = listOf("没关系，记住正确答案就好", "差一点点，下一题加油！", "错误也是学习的一部分", "别灰心，再接再厉！")
        /** 第一个孩子沿用旧版本的存储键（不带前缀），升级时不需要搬运任何数据 */
        const val DEFAULT_CHILD = ""
        /** 设备级共享的设置：不随当前孩子切换 */
        internal val SHARED_KEYS = setOf(
            "admin_token", "speech_settings", "grading_settings", "bank_automation", "bank_automation_status",
            "children", "active_child", "parent_access", "onboarded", "bank_market_sources"
        )
        /** 备份导出时必须排除的含密钥 / 鉴权存储键 */
        internal val SECRET_KV_KEYS = setOf(
            "admin_token", "speech_settings", "grading_settings", "parent_access"
        )
        /** 绑定本机单调时钟、重启计数或半途会话的瞬态存储键：导出与恢复一律跳过，避免换机污染 */
        internal val EPHEMERAL_KV_KEYS = setOf(
            "active_round", "last_answer", "feedback_result", "feedback_deadline",
            "review_feedback", "remote_override", "rest_deadline", "rest_watched_ms",
            "boot_quota_ms", "video_lesson"
        )
        const val MAX_CHILDREN = 8
        /** 多设备裁剪式同步允许的范围白名单 */
        val SYNC_SCOPES = setOf("config", "questions", "speech", "grading")

        /** 统一 SQL 范围过滤结果：全部值通过参数化占位符绑定，杜绝 SQL 注入。 */
        internal data class SqlFilter(val clause: String, val args: List<String>)

        internal fun isValidChildId(id: String): Boolean = isValidChildIdImpl(id)
        internal fun normalizeChildPin(raw: String?): String? = normalizeChildPinImpl(raw)
        internal fun generatePinSalt(): String = generatePinSaltImpl()
        internal fun hashChildPin(pin: String, salt: String): String = hashChildPinImpl(pin, salt)
        internal fun verifyPinHash(pin: String, salt: String, expectedHash: String): Boolean = verifyPinHashImpl(pin, salt, expectedHash)
        internal fun childSummaryJson(id: String, name: String, createdAt: Long, active: Boolean, hasPin: Boolean): JSONObject =
            childSummaryJsonImpl(id, name, createdAt, active, hasPin)

        internal fun isEphemeralKvKey(key: String): Boolean = isEphemeralKvKeyImpl(key)
        internal fun normalizeSyncScopes(scopes: Collection<String>): Set<String> = normalizeSyncScopesImpl(scopes)
        internal fun isConfigSyncKvKey(key: String): Boolean = isConfigSyncKvKeyImpl(key)
        internal fun buildScopedSyncPayload(source: JSONObject, scopes: Set<String>): JSONObject =
            buildScopedSyncPayloadImpl(source, scopes)
        internal fun sanitizeKvValueForLocalOnly(key: String, value: String, localOnly: Boolean = LocalOnlyGuard.enabled): String =
            sanitizeKvValueForLocalOnlyImpl(key, value, localOnly)
        internal fun sanitizeBankAutomationForLocalOnly(raw: JSONObject, localOnly: Boolean = LocalOnlyGuard.enabled): JSONObject =
            sanitizeBankAutomationForLocalOnlyImpl(raw, localOnly)

        internal fun inSpacedScope(wrongOnly: Boolean, lastCorrect: Int, answered: Int, correct: Int): Boolean =
            inSpacedScopeImpl(wrongOnly, lastCorrect, answered, correct)
        internal fun resolveTimeZone(rawZoneId: String): TimeZone = resolveTimeZoneImpl(rawZoneId)
        internal fun startOfDayMillis(nowMs: Long, zone: TimeZone): Long = startOfDayMillisImpl(nowMs, zone)

        internal fun parseExtraQueue(raw: String): List<String> = parseExtraQueueImpl(raw)
        internal fun mergeExtraQueue(existing: List<String>, additions: List<String>): Pair<List<String>, Int> =
            mergeExtraQueueImpl(existing, additions)

        internal fun normalizeGradeSubjects(raw: JSONObject?): JSONObject = normalizeGradeSubjectsImpl(raw)
        internal fun parseGradeSubjects(raw: JSONObject?): Map<String, List<String>> = parseGradeSubjectsImpl(raw)
        internal fun matchesGradeSubjects(
            level: String,
            subject: String,
            gradeSubjects: Map<String, List<String>>,
            selectedLevel: String = ""
        ): Boolean = matchesGradeSubjectsImpl(level, subject, gradeSubjects, selectedLevel)

        internal fun buildScopeFilter(
            subjects: List<String> = emptyList(),
            levels: List<String> = emptyList(),
            selectedLevel: String = "",
            banks: List<String> = emptyList(),
            gradeSubjects: Map<String, List<String>> = emptyMap(),
            explicitSubjectFilter: Boolean = false
        ): SqlFilter = buildScopeFilterImpl(subjects, levels, selectedLevel, banks, gradeSubjects, explicitSubjectFilter)
    }

    internal val appContext: Context = context.applicationContext ?: context

    /** 测试库使用独立目录，避免回归测试清理媒体时伤到家庭题库的图片。 */
    val mediaDir: File = File(
        appContext.filesDir,
        if (databaseName == "studyontv.db") "media"
        else "media_" + databaseName.replace(Regex("[^A-Za-z0-9_.-]"), "_")
    )

    /** 按孩子缓存归一化后的配置 JSON 字符串，避免每秒 tick 反复查库、跑正则和解析周计划。 */
    internal val configCache = ConcurrentHashMap<String, String>()
    @Volatile internal var cachedChild: String? = null
    internal val childLock = Any()
    /** 批改其他孩子的主观题时在当前线程临时绑定到该孩子，避免改动全局 active_child 影响电视前台。 */
    internal val childOverride = ThreadLocal<String?>()

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        try { db.enableWriteAheadLogging() } catch (_: Exception) { }
    }

    override fun onCreate(db: SQLiteDatabase) = createStoreSchema(db)

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = upgradeStoreSchema(db, oldVersion, newVersion)

    override fun close() {
        super.close()
        configCache.clear()
        synchronized(childLock) { cachedChild = null }
    }

    internal fun scoped(key: String, child: String = activeChild()): String {
        if (key in SHARED_KEYS) return key
        return if (child == DEFAULT_CHILD) key else "child:$child:$key"
    }

    internal fun rawGet(key: String, default: String = ""): String =
        readableDatabase.rawQuery("SELECT v FROM kv WHERE k=?", arrayOf(key)).use {
            if (it.moveToFirst()) it.getString(0) ?: default else default
        }

    internal fun rawSet(key: String, value: String) {
        writableDatabase.insertWithOnConflict("kv", null, ContentValues().apply {
            put("k", key); put("v", value)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        if (key == "config" || key.endsWith(":config")) {
            configCache.clear()
        }
    }

    fun get(key: String, default: String = ""): String = rawGet(scoped(key), default)
    fun set(key: String, value: String) = rawSet(scoped(key), value)
    fun adminToken(): String = get("admin_token").ifEmpty { UUID.randomUUID().toString().also { set("admin_token", it) } }

    fun presented(id: String, child: String = activeChild()) = presentedImpl(id, child)
    fun answered(id: String, correct: Boolean, child: String = activeChild()) = answeredImpl(id, correct, child)
    fun idsInBank(bank: String): List<String> = idsInBankImpl(bank)
    fun deleteBank(bank: String): Int = deleteQuestions(idsInBank(bank))
    fun allStems(): List<String> = allStemsImpl()
    fun questionExists(id: String): Boolean = questionExistsImpl(id)

    fun progressState(id: String, child: String = activeChild()): SpacedRepetition.State = progressStateImpl(id, child)
    fun previewIntervals(id: String, child: String = activeChild()): JSONObject = SpacedRepetition.previewLabels(progressState(id, child))
    fun recordProgress(id: String, quality: Int) = recordProgressImpl(id, quality)
    @JvmOverloads fun repetitionStats(child: String = activeChild()): JSONObject = repetitionStatsImpl(child)

    fun saveReview(value: JSONObject) = saveReviewImpl(value)
    fun noteReview(value: JSONObject) = noteReviewImpl(value)
    fun review(id: String): JSONObject? = reviewImpl(id)
    fun reviews(state: String, child: String? = null): JSONArray = reviewsImpl(state, child)

    fun childExists(id: String): Boolean = childExistsImpl(id)
    fun <T> withChild(childId: String, block: () -> T): T = withChildImpl(childId, block)
    fun activeChild(): String = activeChildImpl()
    fun children(): JSONArray = childrenImpl()
    fun createChild(raw: String, pin: String? = null): JSONObject = createChildImpl(raw, pin)
    fun updateChild(id: String, raw: String, pin: String? = null): JSONObject = updateChildImpl(id, raw, pin)
    fun renameChild(id: String, raw: String): JSONObject = updateChild(id, raw, null)
    fun childHasPin(childId: String): Boolean = childHasPinImpl(childId)
    fun verifyChildPin(childId: String, pin: String): Boolean = verifyChildPinImpl(childId, pin)
    fun deleteChild(id: String): Boolean = deleteChildImpl(id)
    fun switchChild(id: String) = switchChildImpl(id)

    @JvmOverloads fun config(child: String = activeChild()): JSONObject = configImpl(child)
    fun saveConfig(input: JSONObject): JSONObject = saveConfigImpl(input)

    fun pickQuestion(
        subjects: List<String> = emptyList(), levels: List<String> = emptyList(), selectedLevel: String = "",
        banks: List<String> = emptyList(), spaced: Boolean = false, wrongFirst: Boolean = false,
        coverageFirst: Boolean = false, newFirst: Boolean = false, excludeId: String? = null,
        onlyWrong: Boolean = false, missedOnly: Boolean = false, child: String = activeChild(),
        gradeSubjects: Map<String, List<String>> = emptyMap(), explicitSubjectFilter: Boolean = false
    ): JSONObject? = pickQuestionImpl(
        subjects, levels, selectedLevel, banks, spaced, wrongFirst, coverageFirst, newFirst,
        excludeId, onlyWrong, missedOnly, child, gradeSubjects, explicitSubjectFilter
    )

    fun pickDeck(
        count: Int, subjects: List<String> = emptyList(), levels: List<String> = emptyList(),
        selectedLevel: String = "", banks: List<String> = emptyList(), spaced: Boolean = false,
        wrongFirst: Boolean = false, coverageFirst: Boolean = false, newFirst: Boolean = false,
        excludeId: String? = null, onlyWrong: Boolean = false, missedOnly: Boolean = false,
        child: String = activeChild(), gradeSubjects: Map<String, List<String>> = emptyMap(),
        explicitSubjectFilter: Boolean = false
    ): JSONArray = pickDeckImpl(
        count, subjects, levels, selectedLevel, banks, spaced, wrongFirst, coverageFirst, newFirst,
        excludeId, onlyWrong, missedOnly, child, gradeSubjects, explicitSubjectFilter
    )

    fun distinctLevels(
        allowedLevels: List<String> = emptyList(), subjects: List<String> = emptyList(),
        banks: List<String> = emptyList(), gradeSubjects: Map<String, List<String>> = emptyMap(),
        explicitSubjectFilter: Boolean = false
    ): List<String> = distinctLevelsImpl(allowedLevels, subjects, banks, gradeSubjects, explicitSubjectFilter)

    fun questionById(id: String, enabledOnly: Boolean = true, child: String = activeChild()): JSONObject? =
        questionByIdImpl(id, enabledOnly, child)

    fun extraQueue(child: String = activeChild()): List<String> = extraQueueImpl(child)
    fun addToExtraQueue(ids: List<String>, child: String = activeChild()): Int = addToExtraQueueImpl(ids, child)
    fun popExtraQueue(id: String, child: String = activeChild()): Boolean = popExtraQueueImpl(id, child)
    fun clearExtraQueue(child: String = activeChild()) = clearExtraQueueImpl(child)

    fun listWrongQuestions(
        subject: String? = null, level: String? = null, bank: String? = null,
        q: String? = null, child: String = activeChild()
    ): JSONObject = listWrongQuestionsImpl(subject, level, bank, q, child)

    fun markWrongMastered(ids: List<String> = emptyList(), all: Boolean = false, child: String = activeChild()): Int =
        markWrongMasteredImpl(ids, all, child)

    fun wrongQuestionsSample(subject: String? = null, limit: Int = 8, child: String = activeChild()): List<JSONObject> =
        wrongQuestionsSampleImpl(subject, limit, child)

    fun questions(
        subject: String? = null, level: String? = null, limit: Int = 200, spaced: Boolean = false,
        enabledOnly: Boolean = true, sortBy: String = "random", wrongFirst: Boolean = false,
        coverageFirst: Boolean = false, newFirst: Boolean = false, child: String = activeChild()
    ): JSONArray = questionsImpl(subject, level, limit, spaced, enabledOnly, sortBy, wrongFirst, coverageFirst, newFirst, child)

    fun questionPage(
        subject: String? = null, level: String? = null, bank: String? = null, search: String? = null,
        type: String? = null, difficulty: Int? = null, wrongOnly: Boolean = false, enabledOnly: Boolean = true,
        sortBy: String = "newest", limit: Int = 50, offset: Int = 0, inlineMedia: Boolean = false,
        child: String = activeChild()
    ): JSONObject = questionPageImpl(
        subject, level, bank, search, type, difficulty, wrongOnly, enabledOnly, sortBy, limit, offset, inlineMedia, child
    )

    fun questionMeta(child: String = activeChild()): JSONObject = questionMetaImpl(child)
    fun countQuestions(): Int = countQuestionsImpl()
    fun coverage(sample: Int = 20, child: String = activeChild(), gradeSubjects: Map<String, List<String>> = emptyMap()): JSONObject =
        coverageImpl(sample, child, gradeSubjects)
    fun studySummary(child: String = activeChild()): JSONObject = studySummaryImpl(child)
    fun learningAnalytics(days: Int = 14, child: String = activeChild()): JSONObject = learningAnalyticsImpl(days, child)

    fun upsertQuestion(item: JSONObject): String = upsertQuestionImpl(item)
    fun pruneMedia(): Int = pruneMediaImpl()
    fun importQuestions(items: JSONArray, skipDuplicates: Boolean = false): JSONObject = importQuestionsImpl(items, skipDuplicates)
    fun clearQuestions(): Int = clearQuestionsImpl()
    fun deleteQuestions(ids: List<String>): Int = deleteQuestionsImpl(ids)
    fun deleteQuestion(id: String): Boolean = deleteQuestions(listOf(id)) > 0
    fun setEnabled(ids: List<String>, enabled: Boolean): Int = setEnabledImpl(ids, enabled)

    fun exportBackup(includeQuestions: Boolean = true): JSONObject = exportBackupImpl(includeQuestions)
    fun exportScopedSync(scopes: Set<String>): JSONObject = exportScopedSyncImpl(scopes)
    fun applyScopedSync(payload: JSONObject, scopes: Set<String>, mode: String = "merge"): JSONObject =
        applyScopedSyncImpl(payload, scopes, mode)
    fun restoreBackup(backup: JSONObject, mode: String = "replace", scopes: Set<String>? = null): JSONObject =
        restoreBackupImpl(backup, mode, scopes)

    fun logEvent(type: String, payload: JSONObject) = logEventImpl(type, payload)
    fun recentEvents(limit: Int = 50): JSONArray = recentEventsImpl(limit)
    fun stats(start: Long, end: Long): JSONObject = statsFor(start, end, activeChild())
    fun todayStats(): JSONObject = todayStatsImpl()
    fun roundsDoneToday(): Int = roundsDoneTodayFor(activeChild())
    internal fun roundsInDay(dayStart: Long, child: String = activeChild()): Int = roundsInDayImpl(dayStart, child)
    fun historyDays(days: Int = 7): JSONArray = historyDaysImpl(days)
    fun timeZoneFor(child: String = activeChild()): TimeZone = timeZoneForImpl(child)
    fun calendarFor(child: String = activeChild()): Calendar = calendarForImpl(child)
    @JvmOverloads fun startOfToday(child: String = activeChild()): Long = startOfTodayAt(clockReading().millis, child)
    internal fun startOfTodayAt(millis: Long, child: String = activeChild()): Long = startOfDayMillis(millis, timeZoneFor(child))
}
