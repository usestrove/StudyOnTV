package com.studyontv.tv

import android.os.SystemClock
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone

class LearningEngine(
    internal val store: LocalStore,
    internal val usageDayStart: (Long) -> Long = { store.startOfTodayAt(it) }
) {
    companion object {
        internal val SECRET_QUESTION_FIELDS = listOf("k", "answers", "correctIndices", "tip", "modelAnswer", "rubric")
        /** 单次扣时安全封顶（60 分钟），避免 OEM 后台节流吞掉真实活跃时长 */
        internal const val MAX_SINGLE_CONSUME_MS = 60 * 60 * 1000L

        internal data class UsageDeduction(
            val freeQuotaConsumed: Long,
            val bootQuotaConsumed: Long,
            val creditConsumed: Long
        ) {
            val totalConsumed: Long get() = freeQuotaConsumed + bootQuotaConsumed + creditConsumed
        }

        internal fun planUsageDeduction(
            requestedMs: Long,
            availableMs: Long,
            freeQuotaMs: Long,
            bootQuotaMs: Long,
            creditMs: Long,
            maxSingleMs: Long = MAX_SINGLE_CONSUME_MS
        ): UsageDeduction = planUsageDeductionImpl(requestedMs, availableMs, freeQuotaMs, bootQuotaMs, creditMs, maxSingleMs)

        internal data class FlashcardRatePlan(
            val quality: Int,
            val correct: Boolean,
            val alreadyRated: Boolean,
            val finished: Boolean,
            val updatedRatedIds: List<String>,
            val updatedCorrectIds: List<String>,
            val deckSize: Int
        )

        internal fun planFlashcardRate(round: JSONObject?, roundId: String, qid: String, quality: Int): FlashcardRatePlan =
            planFlashcardRateImpl(round, roundId, qid, quality)

        internal fun isBaseDone(round: JSONObject): Boolean = isBaseDoneImpl(round)

        internal fun isVoluntary(round: JSONObject, supervisionEnabled: Boolean): Boolean =
            isVoluntaryImpl(round, supervisionEnabled)

        internal fun isExtraEnabled(round: JSONObject, supervisionEnabled: Boolean): Boolean =
            isExtraEnabledImpl(round, supervisionEnabled)

        internal fun isExtraStage(round: JSONObject, extraPool: Long, supervisionEnabled: Boolean): Boolean =
            isExtraStageImpl(round, extraPool, supervisionEnabled)

        internal fun selectNextExtraQueueId(
            queueIds: List<String>,
            lastId: String?,
            isValid: (String) -> Boolean
        ): Pair<String?, List<String>> = selectNextExtraQueueIdImpl(queueIds, lastId, isValid)

        /** 清空加练池时对进行中轮次的收敛动作，防止电视端卡在「加练中 0/0」。 */
        internal enum class ClearExtraRoundAction { NONE, SETTLE_PASS, SETTLE_FAIL, CLEAR_EXTRA_FLAG }

        internal fun planClearExtraActiveRound(round: JSONObject?, supervisionEnabled: Boolean): ClearExtraRoundAction =
            planClearExtraActiveRoundImpl(round, supervisionEnabled)

        internal fun hasUsableWatchQuota(remainingMs: Long, freeQuotaMs: Long): Boolean =
            hasUsableWatchQuotaImpl(remainingMs, freeQuotaMs)

        internal fun computeCanExit(
            supervision: Boolean,
            reason: String,
            remainingMs: Long,
            freeQuotaMs: Long,
            free: Boolean
        ): Boolean = computeCanExitImpl(supervision, reason, remainingMs, freeQuotaMs, free)

        internal fun findNextAvailableSlotMillis(
            slots: List<String>,
            nowMs: Long,
            tomorrow: Boolean,
            timeZone: TimeZone,
            dayIndexOf: (Calendar) -> Int
        ): Long = findNextAvailableSlotMillisImpl(slots, nowMs, tomorrow, timeZone, dayIndexOf)

        internal fun formatAnswerDisplay(question: JSONObject): String = formatAnswerDisplayImpl(question)

        internal fun collapseCjkSpaces(text: String): String = collapseCjkSpacesImpl(text)

        internal fun normalizeTextExact(value: String): String = normalizeTextExactImpl(value)

        internal fun normalizeTextIgnoringPunctuation(value: String): String =
            normalizeTextIgnoringPunctuationImpl(value)

        internal fun matchesTextAnswer(actual: String, expected: String): Boolean =
            matchesTextAnswerImpl(actual, expected)
    }

    @Volatile var exitSnapshot = LearningExitSnapshot(false, 0)
        private set

    internal fun updateExitSnapshot(snapshot: LearningExitSnapshot) {
        exitSnapshot = snapshot
    }

    internal fun number(key: String) = store.get(key, "0").toLongOrNull() ?: 0L
    internal fun put(key: String, value: Long) = store.set(key, value.toString())
    internal val time = TimePolicy(store, wallTime = { store.clockReading().millis }) { store.bootCount() }
    internal val videos = VideoLessons(store)
    internal var lastLoggedRollbackDay: Long? = null
    internal var pendingUsageMs = 0L

    /** 测试可以固定时钟（例如验证跨午夜窗口）；生产环境统一由 DeviceClock 取时。 */
    internal var now: () -> Calendar = { DeviceClock.calendar(store) }

    /** 导航快照刷新失败不应把已提交的业务操作变成错误。 */
    internal fun publishNavigation() {
        try {
            if (!store.writableDatabase.inTransaction()) status()
        } catch (e: Exception) {
            exitSnapshot = LearningExitSnapshot(false, SystemClock.elapsedRealtime())
            android.util.Log.w("StudyOnTV", "Navigation snapshot unavailable", e)
        }
    }

    @Synchronized
    fun switchChild(id: String) {
        flushUsage()
        store.switchChild(id)
        status()
    }

    @Synchronized
    fun deleteChild(id: String): Boolean {
        flushUsage()
        val deleted = store.deleteChild(id)
        status()
        return deleted
    }

    @Synchronized fun awake(awake: Boolean) { day(); time.awake(store.config(), awake); publishNavigation() }

    @Synchronized fun saveConfig(input: JSONObject): JSONObject = saveConfigImpl(input)

    @Synchronized fun override(): JSONObject? = overrideImpl()

    @Synchronized
    fun remote(action: String, minutes: Int, source: String, untilShutdown: Boolean = false): JSONObject =
        remoteImpl(action, minutes, source, untilShutdown)

    @Synchronized fun addWrongToExtraPool(ids: List<String>): JSONObject = addWrongToExtraPoolImpl(ids)

    @Synchronized fun clearExtraPool(): JSONObject = clearExtraPoolImpl()

    @Synchronized fun status(): JSONObject = statusImpl()

    @Synchronized fun consume(milliseconds: Long) = consumeImpl(milliseconds)

    /** 扣时并直接返回最新状态：消除 GateService 单 tick 内 publishNavigation() + status() 的重复查库 */
    @Synchronized fun consumeAndStatus(milliseconds: Long): JSONObject = consumeAndStatusImpl(milliseconds)

    @Synchronized fun consumeRecovered(milliseconds: Long) = consumeRecoveredImpl(milliseconds)

    @Synchronized fun flushUsage() = flushUsageImpl()

    @Synchronized fun deleteQuestions(ids: List<String>): Int = deleteQuestionsImpl(ids)

    @Synchronized fun setQuestionsEnabled(ids: List<String>, enabled: Boolean): Int = setQuestionsEnabledImpl(ids, enabled)

    @Synchronized fun onExternalQuestionsRemoved(ids: Set<String>): Boolean = onExternalQuestionsRemovedImpl(ids)

    @Synchronized
    fun start(level: String? = null, fromGate: Boolean = false): JSONObject =
        start(JSONObject().apply {
            if (level != null) put("level", level)
            if (fromGate) put("fromGate", true)
        })

    @Synchronized fun start(options: JSONObject): JSONObject = startImpl(options)

    @Synchronized fun memorized(roundId: String = ""): JSONObject = memorizedImpl(roundId)

    @Synchronized fun rateFlashcard(roundId: String, qid: String, quality: Int): JSONObject =
        rateFlashcardImpl(roundId, qid, quality)

    @Synchronized fun clearQuestions(bank: String? = null): Int = clearQuestionsImpl(bank)

    @Synchronized fun videoProgress(id: String, playing: Boolean): JSONObject = videoProgressImpl(id, playing)

    @Synchronized fun completeVideo(id: String): JSONObject = completeVideoImpl(id)

    @Synchronized fun continueFeedback(): JSONObject = continueFeedbackImpl()

    @Synchronized fun acknowledgeReview(id: String): JSONObject = acknowledgeReviewImpl(id)

    @Synchronized fun skipPending(): JSONObject = skipPendingImpl()

    @Synchronized fun grade(reviewId: String, score: Double, feedback: String, source: String): JSONObject =
        gradeImpl(reviewId, score, feedback, source)

    @Synchronized
    fun answer(input: JSONObject, verdict: Boolean? = null, quality: Int? = null): JSONObject =
        answerImpl(input, verdict, quality)
}
