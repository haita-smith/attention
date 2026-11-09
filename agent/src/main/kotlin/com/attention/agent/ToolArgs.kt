package com.attention.agent

import com.attention.domain.GoalCadence
import java.time.LocalDate
import java.time.format.DateTimeParseException
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

/** 日期参数的唯一解析入口：所有工具共用同一份格式报错，不各说各话。 */
private fun parseDayArg(key: String, raw: String): LocalDate = try {
    LocalDate.parse(raw)
} catch (invalid: DateTimeParseException) {
    throw IllegalArgumentException("$key 需要 YYYY-MM-DD 格式,实际「$raw」", invalid)
}
