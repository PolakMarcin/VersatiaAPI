package com.github.marcoral.versatia.util.type

interface LevelWithExp {
    val level: Int
    val exp: Float
}

data class ImmutableLevelWithExp(override val level: Int, override val exp: Float) : LevelWithExp

class MutableLevelWithExp(override var level: Int, override var exp: Float) : LevelWithExp {
    fun toImmutable() = ImmutableLevelWithExp(level, exp)

    override fun toString() = "MutableLevelWithExp(level=$level, exp=$exp)"
}
