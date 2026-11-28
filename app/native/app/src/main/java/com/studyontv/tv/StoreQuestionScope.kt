package com.studyontv.tv

import org.json.JSONArray
import org.json.JSONObject

internal fun sqlPlaceholders(count: Int): String = List(count) { "?" }.joinToString(",")

/** LIKE 里的通配符要转义，否则家长搜索「100%」会当成通配符扫全库。 */
internal fun escapeLike(value: String): String =
    value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

/** 规范化 config.gradeSubjects：自动过滤空年级/超长键、科目去重，保留空数组（表示该年级全部科目）。 */
internal fun normalizeGradeSubjectsImpl(raw: JSONObject?): JSONObject {
    val clean = JSONObject()
    if (raw == null) return clean
    val keys = raw.keys()
    var gradeCount = 0
    while (keys.hasNext() && gradeCount < 50) {
        val rawKey = keys.next()
        val grade = rawKey.trim()
        if (grade.isEmpty() || grade.length > 32) continue
        val arr = raw.optJSONArray(rawKey)
        val cleanSubjects = JSONArray()
        val seen = LinkedHashSet<String>()
        if (arr != null) {
            for (i in 0 until minOf(arr.length(), 50)) {
                val sub = arr.optString(i).trim()
                if (sub.isNotEmpty() && sub.length <= 32 && seen.add(sub)) {
                    cleanSubjects.put(sub)
                }
            }
        }
        clean.put(grade, cleanSubjects)
        gradeCount++
    }
    return clean
}

/** 将 config.gradeSubjects 解析为保序 Map<年级, 科目列表>（空列表表示该年级不限科目）。 */
internal fun parseGradeSubjectsImpl(raw: JSONObject?): Map<String, List<String>> {
    val normalized = normalizeGradeSubjectsImpl(raw)
    val out = LinkedHashMap<String, List<String>>()
    val keys = normalized.keys()
    while (keys.hasNext()) {
        val grade = keys.next()
        val arr = normalized.optJSONArray(grade)
        val subjects = ArrayList<String>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val sub = arr.optString(i).trim()
                if (sub.isNotEmpty()) subjects.add(sub)
            }
        }
        out[grade] = subjects
    }
    return out
}

/** 内存态主从范围匹配判断（与 buildScopeFilter SQL 语义严格一致）。 */
internal fun matchesGradeSubjectsImpl(
    level: String,
    subject: String,
    gradeSubjects: Map<String, List<String>>,
    selectedLevel: String
): Boolean {
    if (gradeSubjects.isEmpty()) return true
    if (selectedLevel.isNotEmpty()) {
        if (level != selectedLevel) return false
        val allowed = gradeSubjects[selectedLevel] ?: return false
        return allowed.isEmpty() || subject in allowed
    }
    val allowed = gradeSubjects[level] ?: return false
    return allowed.isEmpty() || subject in allowed
}

/**
 * 构建题目范围 SQL 过滤片段（pickQuestion / pickDeck / distinctLevels / coverage 单一真理源）。
 */
internal fun buildScopeFilterImpl(
    subjects: List<String>,
    levels: List<String>,
    selectedLevel: String,
    banks: List<String>,
    gradeSubjects: Map<String, List<String>>,
    explicitSubjectFilter: Boolean
): LocalStore.Companion.SqlFilter {
    val where = StringBuilder()
    val args = mutableListOf<String>()

    if (gradeSubjects.isNotEmpty()) {
        if (selectedLevel.isNotEmpty()) {
            val allowedSubjects = gradeSubjects[selectedLevel]
            if (allowedSubjects == null) {
                where.append(" AND 1=0")
            } else if (allowedSubjects.isEmpty()) {
                where.append(" AND questions.level=?")
                args.add(selectedLevel)
            } else {
                where.append(" AND (questions.level=? AND questions.subject IN (${sqlPlaceholders(allowedSubjects.size)}))")
                args.add(selectedLevel)
                args.addAll(allowedSubjects)
            }
        } else {
            val branches = mutableListOf<String>()
            for ((grade, allowedSubjects) in gradeSubjects) {
                if (allowedSubjects.isEmpty()) {
                    branches.add("questions.level=?")
                    args.add(grade)
                } else {
                    branches.add("(questions.level=? AND questions.subject IN (${sqlPlaceholders(allowedSubjects.size)}))")
                    args.add(grade)
                    args.addAll(allowedSubjects)
                }
            }
            if (branches.isNotEmpty()) {
                where.append(" AND (${branches.joinToString(" OR ")})")
            }
        }
        if (explicitSubjectFilter && subjects.isNotEmpty()) {
            where.append(" AND questions.subject IN (${sqlPlaceholders(subjects.size)})")
            args.addAll(subjects)
        }
    } else {
        if (subjects.isNotEmpty()) {
            where.append(" AND questions.subject IN (${sqlPlaceholders(subjects.size)})")
            args.addAll(subjects)
        }
        if (levels.isNotEmpty()) {
            where.append(" AND questions.level IN (${sqlPlaceholders(levels.size)})")
            args.addAll(levels)
        }
        if (selectedLevel.isNotEmpty()) {
            where.append(" AND questions.level=?")
            args.add(selectedLevel)
        }
    }

    if (banks.isNotEmpty()) {
        if ("默认题库" in banks) {
            where.append(" AND (questions.bank IN (${sqlPlaceholders(banks.size)}) OR questions.bank='')")
        } else {
            where.append(" AND questions.bank IN (${sqlPlaceholders(banks.size)})")
        }
        args.addAll(banks)
    }
    return LocalStore.Companion.SqlFilter(where.toString(), args)
}

internal fun appendScopeFilters(
    where: StringBuilder,
    args: MutableList<String>,
    subjects: List<String>,
    levels: List<String>,
    selectedLevel: String,
    banks: List<String>,
    gradeSubjects: Map<String, List<String>> = emptyMap(),
    explicitSubjectFilter: Boolean = false
) {
    val filter = LocalStore.buildScopeFilter(
        subjects = subjects,
        levels = levels,
        selectedLevel = selectedLevel,
        banks = banks,
        gradeSubjects = gradeSubjects,
        explicitSubjectFilter = explicitSubjectFilter
    )
    where.append(filter.clause)
    args.addAll(filter.args)
}
