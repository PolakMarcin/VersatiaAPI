package com.github.marcoral.versatia.util.config

import java.io.File
import java.nio.file.Path
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.Plugin

val YAML_EXTENSIONS = setOf("yml", "yaml")

/**
 * Loads a YAML file, propagating parse errors. Unlike [YamlConfiguration.loadConfiguration], a broken
 * file is reported to the caller instead of being logged and replaced with an empty configuration.
 */
fun loadYamlOrThrow(file: File) = YamlConfiguration().apply { load(file) }

/** Runs [action] for every `.yml`/`.yaml` file under `<data folder>/<pluginRelativePath>`, recursively, in path order. */
fun Plugin.forEachYamlFile(pluginRelativePath: String, action: (Path, YamlConfiguration) -> Unit) =
    yamlFiles(pluginRelativePath).forEach { action(it.toPath(), loadYamlOrThrow(it)) }

fun <T> Plugin.mapYamlFiles(pluginRelativePath: String, action: (Path, YamlConfiguration) -> T): Map<Path, T> = buildMap {
    forEachYamlFile(pluginRelativePath) { path, config -> put(path, action(path, config)) }
}

fun joinPath(vararg pathElements: String) = pathElements.joinToString(File.separator)

private fun Plugin.yamlFiles(pluginRelativePath: String) = dataFolder.resolve(pluginRelativePath)
    .takeIf { it.isDirectory }?.walk().orEmpty()
    .filter { it.isFile && it.extension.lowercase() in YAML_EXTENSIONS }
    .sortedBy { it.path }
    .toList()
