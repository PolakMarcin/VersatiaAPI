package com.github.marcoral.versatia

import java.util.UUID
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Server
import org.bukkit.entity.Player

/* VERSATIA */

/** The running Versatia plugins by friendly name. A bean or an `@AutoInvoke` function takes [PluginRegistry] as a parameter instead. */
val VersatiaEngine.Companion.registry get() = instance.registry

/**
 * Runs the reload event of the plugin registered as [friendlyName], and then of the plugins using its beans, as
 * `/versatia reload <name>` does; see [VersatiaEngine.reload]. A plugin reloads itself with [VersatiaPlugin.versatiaReload].
 */
fun VersatiaEngine.Companion.reload(friendlyName: String) = with(instance) { reload(registry.getOrThrow(friendlyName)) }

/* PLAYERS AND WORLDS */

val VersatiaEngine.Companion.onlinePlayers: Collection<Player> get() = Bukkit.getOnlinePlayers()

/** The online player with exactly this name, ignoring case; unlike `Bukkit.getPlayer(String)`, never one whose name merely starts with it. */
fun VersatiaEngine.Companion.playerOrNull(name: String) = Bukkit.getPlayerExact(name)

fun VersatiaEngine.Companion.playerOrNull(id: UUID) = Bukkit.getPlayer(id)

/** The player behind [id], online or not. Never null and never a web lookup, unlike the name-based `Bukkit.getOfflinePlayer(String)`. */
fun VersatiaEngine.Companion.offlinePlayer(id: UUID) = Bukkit.getOfflinePlayer(id)

fun VersatiaEngine.Companion.worldOrNull(name: String) = Bukkit.getWorld(name)

/* MESSAGES AND COMMANDS */

/** Sends [message] to everyone holding [permission], by default every player and the console; returns the number of recipients. */
fun VersatiaEngine.Companion.broadcast(message: Component, permission: String = Server.BROADCAST_CHANNEL_USERS) = Bukkit.broadcast(message, permission)

/**
 * Runs [commandLine], without the slash, as the console; false when no command handles it. Off the primary thread it throws
 * an `IllegalStateException` instead of running anything. A player runs one with `player.performCommand`.
 */
fun VersatiaEngine.Companion.dispatchConsoleCommand(commandLine: String): Boolean {
    checkPrimaryThread("dispatchConsoleCommand(\"$commandLine\")")
    return Bukkit.dispatchCommand(Bukkit.getConsoleSender(), commandLine)
}

/* SERVICES */

/** The provider registered for [T] in Bukkit's `ServicesManager`, or null when none is. */
inline fun <reified T : Any> VersatiaEngine.Companion.loadServiceOrNull() = Bukkit.getServicesManager().load(T::class.java)

/** The provider registered for [T] in Bukkit's `ServicesManager`; a [ServiceNotRegisteredException] names the service when none is. */
inline fun <reified T : Any> VersatiaEngine.Companion.loadService() = loadServiceOrNull<T>() ?: throw ServiceNotRegisteredException(T::class.java)

/* THREADS */

fun VersatiaEngine.Companion.isPrimaryThread() = Bukkit.isPrimaryThread()

/** Throws an `IllegalStateException` naming [action] and how to move it when called off the server's main thread. */
fun VersatiaEngine.Companion.checkPrimaryThread(action: String = "This code") =
    check(isPrimaryThread()) { "$action must run on the server's main thread; schedule it with plugin.runTask { ... }" }

/** Throws an `IllegalStateException` naming [action] and how to move it when called on the server's main thread. */
fun VersatiaEngine.Companion.checkNotPrimaryThread(action: String = "This code") =
    check(!isPrimaryThread()) { "$action must not run on the server's main thread; schedule it with plugin.runTaskAsynchronously { ... }" }
