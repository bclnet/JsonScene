/*
 * JsonStrict.kt
 * JsonScene
 *
 * Strict accessors: JsonUI's `stringValue` renders any scalar or container as
 * text, which is right for labels but wrong when parsing a schema where a
 * string and an object mean different things.
 */
package com.bclnet.jsonscene

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/** The string only when the value is a JSON string. */
val JsonElement.text: String? get() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/** The number only when the value is a JSON number. */
val JsonElement.numberValue: Double?
    get() = (this as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString }?.content?.toDoubleOrNull()

/** The integer only when the value is an integral JSON number. */
val JsonElement.integerValue: Int? get() = numberValue?.takeIf { it == Math.rint(it) && Math.abs(it) < 1e15 }?.toInt()

/** The bool only when the value is a JSON bool. */
val JsonElement.flag: Boolean? get() = (this as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString }?.content?.toBooleanStrictOrNull()
