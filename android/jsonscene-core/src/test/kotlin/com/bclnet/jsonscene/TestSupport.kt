package com.bclnet.jsonscene

import com.bclnet.jsonui.JsonDocument
import com.bclnet.jsonui.JsonFragments
import com.bclnet.jsonui.fromValue
import com.bclnet.jsonui.parseJson
import java.io.File

object TestSupport {
    /** The repository's examples directory, found from the module directory Gradle runs tests in. */
    val examples: File = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "examples") }.first { File(it, "scene-bush.json").exists() }

    /** Resolves `$ref` fragments (bodies, minds) against the example files. */
    fun resolver() = JsonFragments { url -> parseJson(File(url).readText()) }

    fun example(name: String): SceneDocument = SceneDocument.of(document(name).root)
    fun document(name: String): JsonDocument = File(examples, name).let { JsonDocument.fromValue(parseJson(it.readText()), it.toURI(), resolver()) }
}
