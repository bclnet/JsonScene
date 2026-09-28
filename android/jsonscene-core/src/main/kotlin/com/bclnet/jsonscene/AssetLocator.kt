/*
 * AssetLocator.kt
 * JsonScene
 *
 * Resolves model and sound references: absolute URLs as they are, relative
 * ones against the document URL, and `bundle:name.ext` through the app.
 */
package com.bclnet.jsonscene

import java.net.URI

class AssetLocator(val base: URI? = null, /** Resolver for `bundle:` references, set by the app (e.g. an assets path). */ val bundle: ((String) -> URI?)? = null) {
    fun resolve(reference: String): URI? {
        val trimmed = reference.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("bundle:")) return bundle?.invoke(trimmed.removePrefix("bundle:"))
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        if (uri.scheme != null) return uri
        if (trimmed.startsWith("/")) return URI("file", null, trimmed, null)
        return base?.resolve(uri)
    }
}
