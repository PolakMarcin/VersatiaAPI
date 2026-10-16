package com.github.marcoral.versatia.util.scheduler

import java.util.concurrent.Callable
import java.util.concurrent.Future
import java.util.function.Consumer
import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask

// The *WithContext variants take a Consumer<BukkitTask>; a separate name is needed because a Kotlin
// lambda is ambiguous between Runnable and Consumer.

fun Plugin.runTask(task: Runnable) = Bukkit.getScheduler().runTask(this, task)
fun Plugin.runTaskWithContext(task: Consumer<in BukkitTask>) = Bukkit.getScheduler().runTask(this, task)

fun Plugin.runTaskAsynchronously(task: Runnable) = Bukkit.getScheduler().runTaskAsynchronously(this, task)
fun Plugin.runTaskAsynchronouslyWithContext(task: Consumer<in BukkitTask>) = Bukkit.getScheduler().runTaskAsynchronously(this, task)

fun Plugin.runTaskLater(delayTicks: Long, task: Runnable) = Bukkit.getScheduler().runTaskLater(this, task, delayTicks)
fun Plugin.runTaskLaterWithContext(delayTicks: Long, task: Consumer<in BukkitTask>) = Bukkit.getScheduler().runTaskLater(this, task, delayTicks)

fun Plugin.runTaskLaterAsynchronously(delayTicks: Long, task: Runnable) = Bukkit.getScheduler().runTaskLaterAsynchronously(this, task, delayTicks)
fun Plugin.runTaskLaterAsynchronouslyWithContext(delayTicks: Long, task: Consumer<in BukkitTask>) = Bukkit.getScheduler().runTaskLaterAsynchronously(this, task, delayTicks)

fun Plugin.runTaskTimer(delayTicks: Long = 0, periodTicks: Long, task: Runnable) = Bukkit.getScheduler().runTaskTimer(this, task, delayTicks, periodTicks)
fun Plugin.runTaskTimerWithContext(delayTicks: Long = 0, periodTicks: Long, task: Consumer<in BukkitTask>) = Bukkit.getScheduler().runTaskTimer(this, task, delayTicks, periodTicks)

fun Plugin.runTaskTimerAsynchronously(delayTicks: Long = 0, periodTicks: Long, task: Runnable) = Bukkit.getScheduler().runTaskTimerAsynchronously(this, task, delayTicks, periodTicks)
fun Plugin.runTaskTimerAsynchronouslyWithContext(delayTicks: Long = 0, periodTicks: Long, task: Consumer<in BukkitTask>) = Bukkit.getScheduler().runTaskTimerAsynchronously(this, task, delayTicks, periodTicks)

fun <T> Plugin.callSyncMethod(task: Callable<T>): Future<T> = Bukkit.getScheduler().callSyncMethod(this, task)
fun Plugin.cancelAllTasks() = Bukkit.getScheduler().cancelTasks(this)
