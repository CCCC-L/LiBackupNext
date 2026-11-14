package com.cc.libackupnext.service.json

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 全项目唯一的 Json 实例：所有跨进程请求 / 响应、SharedPreferences 里的 JSON、
 * 以及写进仓库的备份文件都走这里，字段名与现有格式逐字对齐。
 *
 * - [ignoreUnknownKeys]：Rust / GitHub / 老版本快照可能多出字段，不能因此解析失败。
 * - [coerceInputValues]：老数据里字段为 null 时回落到属性默认值。
 * - `encodeDefaults = false` + `explicitNulls = false`：保持原来「可空字段 ?.let { put }」的
 *   稀疏写法。进度帧只有 256 字节，多写几个默认字段就会把帧撑爆。
 *   需要恒定写出的字段（Rust 侧没有 serde default 的必填项）在 DTO 里不设默认值，
 *   或用类级 `@EncodeDefault(ALWAYS)` 显式声明。
 */
val AppJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    encodeDefaults = false
    explicitNulls = false
}

inline fun <reified T> T.toJsonText(): String = AppJson.encodeToString(this)

inline fun <reified T> String.parseJson(): T = AppJson.decodeFromString(this)
