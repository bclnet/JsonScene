package com.bclnet.jsonscene

import com.bclnet.jsonui.JsonDocument
import java.io.File

object TestSupport {
    /** The repository's examples directory, found from the module directory Gradle runs tests in. */
    val examples: File = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "examples") }.first { File(it, "scene-bush.json").exists() }

    fun example(name: String): SceneDocument = SceneDocument.parse(File(examples, name).readText())
    fun document(name: String): JsonDocument = JsonDocument.parse(File(examples, name).readText())
}
