package org.kotlinlsp.actions.hover

class DocFormatter {
    companion object {
        private val CODE_REGEX = Regex("""\{@code\s+([^}]+)}""")
        private val THROWS_REGEX = Regex("""@(throws|exception)\s+(\w+)\s*(.*)""")
        private val PARAMS_REGEX = Regex("""@(param)\s+(\w+)\s*(.*)""")
        private val PROPERTIES_REGEX = Regex("""@(property)\s+(\w+)\s*(.*)""")
        private val P_TAG_REGEX = Regex("</?p[^>]*>", RegexOption.IGNORE_CASE)


        private val SIMPLE_REPLACEMENTS = mapOf(
            "@return" to "**Returns:**",
            "@see" to "**See also:**",
            "@author" to "**Authors:**",
            "@since" to "**Since:**",
            "@constructor" to "**Constructors:**",
            "@suppress" to "**Suppress:**",
            "@sample" to "**Sample:**",
        )
    }

    private val cache = mutableMapOf<String, String>()

    fun formatDoc(doc: String): String {
        // Quick cache check
        cache[doc]?.let { return it }

        val cleaned = doc.removePrefix("/**").removeSuffix("*/")

        val result = buildString(cleaned.length) {
            cleaned.lines().forEach { line ->
                val trimmed = line.trim()
                val content = if (trimmed.startsWith('*')) {
                    trimmed.substring(1).trimStart()
                } else {
                    trimmed
                }
                if (content.isNotEmpty() || this.isNotEmpty()) {
                    if (this.isNotEmpty()) append('\n')
                    append(content)
                }
            }
        }

        val formatted = result
            .let { text -> SIMPLE_REPLACEMENTS.entries.fold(text) { acc, (old, new) -> acc.replace(old, new) } }
            .let { CODE_REGEX.replace(it) { "`${it.groupValues[1].trim()}`" } }
            .let { THROWS_REGEX.replace(it) { m ->
                val exName = m.groupValues[2]
                val rest = m.groupValues[3].trim()
                val desc = if (rest.isNotEmpty()) " - $rest" else ""
                "**Throws**: [$exName]$desc"
            }}
            .let { PARAMS_REGEX.replace(it) { m ->
                val exName = m.groupValues[2]
                val rest = m.groupValues[3].trim()
                val desc = if (rest.isNotEmpty()) " - $rest" else ""
                "**Params**: [$exName]$desc"
            }}
            .let { PROPERTIES_REGEX.replace(it) { m ->
                val exName = m.groupValues[2]
                val rest = m.groupValues[3].trim()
                val desc = if (rest.isNotEmpty()) " - $rest" else ""
                "**Properties**: [$exName]$desc"
            }}
            .let { P_TAG_REGEX.replace(it, "") }

        if (cache.size < 1000) {
            cache[doc] = formatted
        }

        return formatted
    }
}