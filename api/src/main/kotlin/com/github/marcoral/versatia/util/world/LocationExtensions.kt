package com.github.marcoral.versatia.util.world

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.bukkit.Location
import org.bukkit.Particle

/** [points] locations evenly spaced on a horizontal circle of [radius] around this location, in its world; throws when that world has been unloaded. */
fun Location.getCircleAround(radius: Double, points: Int = 100) = List(points) { i ->
    val angle = 2 * PI * i / points
    Location(world, x + radius * cos(angle), y, z + radius * sin(angle))
}

/** Spawns a particle at this location; does nothing when its world is not set or no longer loaded. [data] is of the particle's `dataType`. */
fun Location.spawnParticle(
    particle: Particle,
    count: Int = 1,
    offsetX: Double = 0.0,
    offsetY: Double = 0.0,
    offsetZ: Double = 0.0,
    extra: Double = 1.0,
    data: Any? = null,
    force: Boolean = false,
) {
    if (isWorldLoaded) world.spawnParticle(particle, this, count, offsetX, offsetY, offsetZ, extra, data, force)
}
