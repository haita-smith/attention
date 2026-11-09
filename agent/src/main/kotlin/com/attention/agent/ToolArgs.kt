package com.attention.agent

import com.attention.domain.GoalCadence
import java.time.LocalDate
import java.time.format.DateTimeParseException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 周期取值的单一事实来源：schema 描述与报错共用，永不与 [GoalCadence] 漂移。 */
internal val CADENCE_CHOICES = GoalCadence.entries.joinToString("/") { it.name }

/** 读取可选字符串参数：非字符串或 null 一律视为缺省。 */
internal fun optionalStringArg(args: JsonObject, key: String): String? =
    (args[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

/** 读取必填字符串参数；缺失即抛，由循环转成错误观测。 */
internal fun requiredStringArg(args: JsonObject, key: String): String =
    optionalStringArg(args, key) ?: throw IllegalArgumentException("$key 必填")

/** 读取可选的 YYYY-MM-DD 参数；格式不对直接抛，由循环转成错误观测教给推理器。 */
internal fun optionalDayArg(args: JsonObject, key: String): LocalDate? =
    optionalStringArg(args, key)?.let { parseDayArg(key, it) }

/** 读取必填的 YYYY-MM-DD 参数；缺失或格式不对都直接抛。 */
internal fun requiredDayArg(args: JsonObject, key: String): LocalDate =
    parseDayArg(key, requiredStringArg(args, key))

/** 解析周期参数：取值必须与 [GoalCadence] 枚举名完全一致。 */
internal fun cadenceArg(args: JsonObject): GoalCadence {
    val raw = requiredStringArg(args, "cadence")
    return GoalCadence.entries.firstOrNull { it.name == raw }
        ?: throw IllegalArgumentException("cadence 必须是 $CADENCE_CHOICES,实际「$raw」")
}

/**
 * 意图级变更工具共用的 action 判别入口:action 字段必填且取值必须落在给定的动作
 * 全集内,缺失与未知取值同为解析失败,在风险判定与派发之前就抛出,由循环折算成
 * 错误观测回填。动作全集由各工具的 ACTION_RISK 键集给定(单一事实源),报错文案
 * 据此生成,与 schema 描述同源,永不漂移。
 */
internal fun actionArg(args: JsonObject, validActions: Set<String>): String {
    val choices = validActions.joinToString("/")
    val raw = optionalStringArg(args, "action") ?: throw IllegalArgumentException("action 必填:$choices")
    require(raw in validActions) { "action 必须是 $choices,实际「$raw」" }
    return raw
}

/** 读取可选的字符串数组参数:缺失或 null 视为缺省,非数组与非字符串元素都是形状错误。 */
internal fun optionalStringListArg(args: JsonObject, field: String): List<String>? {
    val element = args[field] ?: return null
    if (element is JsonNull) return null
    require(element is JsonArray) { "$field 必须是字符串数组" }
    return element.map { item ->
        require(item is JsonPrimitive && item !is JsonNull) { "$field 必须是字符串数组" }
        item.content
    }
}

/** 读取必填的正整数参数(分钟数一类以字符串形态传入的量词):缺失、非整数与零负值都在派发之前抛出。 */
internal fun requiredPositiveIntArg(args: JsonObject, field: String): Int {
    val raw = requiredStringArg(args, field)
    val value = raw.toIntOrNull() ?: throw IllegalArgumentException("$field 必须是正整数,实际「$raw」")
    require(value > 0) { "$field 必须是正整数,实际 $value" }
    return value
}

/** 日期参数的唯一解析入口：所有工具共用同一份格式报错，不各说各话。 */
private fun parseDayArg(key: String, raw: String): LocalDate = try {
    LocalDate.parse(raw)
} catch (invalid: DateTimeParseException) {
    throw IllegalArgumentException("$key 需要 YYYY-MM-DD 格式,实际「$raw」", invalid)
}
