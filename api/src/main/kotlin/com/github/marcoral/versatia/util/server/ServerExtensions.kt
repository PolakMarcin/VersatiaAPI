package com.github.marcoral.versatia.util.server

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.entity.Player

fun isPrimaryThread() = Bukkit.getServer().isPrimaryThread

fun checkPrimaryThread() = check(isPrimaryThread()) { "This code must run on the primary thread" }
fun checkNotPrimaryThread() = check(!isPrimaryThread()) { "This code must not run on the primary thread" }

fun bukkitPrintln(component: Component) = Bukkit.getConsoleSender().sendMessage(component)

/** Broadcasts to every online player and the console; returns the number of recipients. */
fun bukkitBroadcast(component: Component) = Bukkit.getServer().broadcast(component)

inline fun forEachOnlinePlayer(action: (Player) -> Unit) = Bukkit.getOnlinePlayers().forEach(action)

/** The provider registered for [T] in Bukkit's `ServicesManager`, or null when none is. */
inline fun <reified T : Any> loadServiceOrNull(): T? = Bukkit.getServicesManager().load(T::class.java)

/** The provider registered for [T] in Bukkit's `ServicesManager`; a [ServiceNotRegisteredException] names the service when none is. */
inline fun <reified T : Any> loadService(): T = loadServiceOrNull<T>() ?: throw ServiceNotRegisteredException(T::class.java)

class ServiceNotRegisteredException(service: Class<*>) :
    IllegalStateException("No provider of ${service.name} is registered. Is the plugin providing it installed, enabled and listed under depend: or softdepend:?")
