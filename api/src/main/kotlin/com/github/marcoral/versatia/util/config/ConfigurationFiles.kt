package com.github.marcoral.versatia.util.config

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.file.Path
import org.bukkit.configuration.InvalidConfigurationException
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.Plugin

val YAML_EXTENSIONS = setOf("yml", "yaml")

/**
 * Loads a YAML file, propagating parse errors. Unlike [YamlConfiguration.loadConfiguration], a broken
 * file is reported to the caller instead of being logged and replaced with an empty configuration,
 * and a file that is not UTF-8 is an [InvalidConfigurationException] instead of text with
 * replacement characters.
 */
fun loadYamlOrThrow(file: File) = YamlConfiguration().apply { loadFromString(file.readStrictUtf8()) }

/**
 * Every `.yml`/`.yaml` file under this directory, recursively, ordered by the path relative to it
 * with `/` separators, so the order is the same on every platform; empty when this is not a directory.
 */
fun File.yamlFiles() = takeIf { it.isDirectory }?.walk().orEmpty()
    .filter { it.isFile && it.extension.lowercase() in YAML_EXTENSIONS }
    .sortedBy { it.relativeTo(this).invariantSeparatorsPath }
    .toList()

/** Runs [action] for every `.yml`/`.yaml` file under `<data folder>/<pluginRelativePath>`, recursively, in path order. */
fun Plugin.forEachYamlFile(pluginRelativePath: String, action: (Path, YamlConfiguration) -> Unit) =
    dataFolder.resolve(pluginRelativePath).yamlFiles().forEach { action(it.toPath(), loadYamlOrThrow(it)) }

fun <T> Plugin.mapYamlFiles(pluginRelativePath: String, action: (Path, YamlConfiguration) -> T): Map<Path, T> = buildMap {
    forEachYamlFile(pluginRelativePath) { path, config -> put(path, action(path, config)) }
}

private fun File.readStrictUtf8() = try {
    Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(readBytes())).toString()
} catch (e: CharacterCodingException) {
    throw InvalidConfigurationException("$name is not UTF-8 encoded; save it as UTF-8", e)
}
