package com.github.marcoral.versatia.util.world

import kotlin.test.Test
import org.bukkit.Location
import org.bukkit.Particle

class LocationExtensionsTest {
    @Test
    fun `spawnParticle needs no type argument and does nothing without a world`() {
        Location(null, 0.0, 0.0, 0.0).spawnParticle(Particle.FLAME)
        Location(null, 0.0, 0.0, 0.0).spawnParticle(Particle.DUST, count = 3, data = null)
    }
}
