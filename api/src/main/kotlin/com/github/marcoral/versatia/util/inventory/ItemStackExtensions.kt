package com.github.marcoral.versatia.util.inventory

import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta

/** True for null, any kind of air (`AIR`, `CAVE_AIR`, `VOID_AIR`) and empty stacks. */
@OptIn(ExperimentalContracts::class)
fun ItemStack?.isNullOrAir(): Boolean {
    contract { returns(false) implies (this@isNullOrAir != null) }
    return this == null || type.isAir || amount <= 0
}

/** Removes the client's default item lore styling (italic, purple) by forcing non-italic white where no style is set. */
fun List<Component>.withoutDefaultItemFormat() = map {
    it.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE).colorIfAbsent(NamedTextColor.WHITE)
}

fun ItemStack.loreWithoutDefaultFormat(lore: List<Component>?) = lore(lore?.withoutDefaultItemFormat())

fun ItemMeta.loreWithoutDefaultFormat(lore: List<Component>?) = lore(lore?.withoutDefaultItemFormat())
