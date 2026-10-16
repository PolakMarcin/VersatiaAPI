package com.github.marcoral.versatia.ksp

/** Reads the `main:` entry of a `plugin.yml` without a YAML library: only the top-level key counts, as for Bukkit. */
internal object PluginYml {
    private val mainLine = Regex("""^main:\s*(?:"([\w.$]+)"|'([\w.$]+)'|([\w.$]+))(?:\s+#.*)?$""")

    /** The class the top-level `main:` key names, or null without one; top-level keys sit at the indentation of the first key. */
    fun mainClassOf(content: String): String? {
        val lines = content.lineSequence().filter { it.isNotBlank() && !it.trimStart().startsWith('#') && it.trim() != "---" }.toList()
        val root = lines.firstOrNull()?.indentation() ?: return null
        return lines.filter { it.indentation() == root }
            .firstNotNullOfOrNull { line -> mainLine.matchEntire(line.substring(root).trimEnd())?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() } }
    }

    private fun String.indentation() = takeWhile { it == ' ' }.length
}
