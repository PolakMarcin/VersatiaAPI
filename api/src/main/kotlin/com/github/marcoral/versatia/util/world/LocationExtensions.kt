package com.github.marcoral.versatia.util.world

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.bukkit.Location
import org.bukkit.Particle

/** [points] locations evenly spaced on a horizontal circle of [radius] around this location. */
fun Location.getCircleAround(radius: Double, points: Int = 100) = List(points) { i ->
    val angle = 2 * PI * i / points
    Location(world, x + radius * cos(angle), y, z + radius * sin(angle))
}

/** Spawns a particle at this location; does nothing when the world is not loaded. */
fun <T> Location.spawnParticle(
    particle: Particle,
    count: Int = 1,
    offsetX: Double = 0.0,
    offsetY: Double = 0.0,
    offsetZ: Double = 0.0,
    extra: Double = 1.0,
    data: T? = null,
    force: Boolean = false,
) {
    world?.spawnParticle(particle, this, count, offsetX, offsetY, offsetZ, extra, data, force)
}
