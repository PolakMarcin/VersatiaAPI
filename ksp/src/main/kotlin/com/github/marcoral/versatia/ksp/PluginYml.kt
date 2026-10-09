package com.github.marcoral.versatia.ksp

internal object PluginYml {
    private val mainLine = Regex("""^main:\s*['"]?([\w.$]+)['"]?$""")

    fun mainClassOf(content: String) = content.lineSequence()
        .map { it.trim() }
        .firstNotNullOfOrNull { mainLine.matchEntire(it)?.groupValues?.get(1) }
}
