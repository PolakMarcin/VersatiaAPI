package com.github.marcoral.versatia.util.inventory

import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack

/** Raw slot indices of the armor and off-hand slots in a player inventory. */
object PlayerInventorySlots {
    const val BOOTS = 36
    const val LEGGINGS = 37
    const val CHESTPLATE = 38
    const val HELMET = 39
    const val OFF_HAND = 40
}

/** Maps a raw player-inventory slot index to the equipment slot it holds, or null for storage/hotbar slots. */
fun Int.toEquipmentSlotOrNull() = when (this) {
    PlayerInventorySlots.HELMET -> EquipmentSlot.HEAD
    PlayerInventorySlots.CHESTPLATE -> EquipmentSlot.CHEST
    PlayerInventorySlots.LEGGINGS -> EquipmentSlot.LEGS
    PlayerInventorySlots.BOOTS -> EquipmentSlot.FEET
    PlayerInventorySlots.OFF_HAND -> EquipmentSlot.OFF_HAND
    else -> null
}

/** Total amount of items that are [ItemStack.isSimilar] to [item]. */
fun Inventory.getAmountOfSimilarItems(item: ItemStack) = contents.getAmountOfSimilarItems(item)

fun Array<out ItemStack?>.getAmountOfSimilarItems(item: ItemStack) =
    sumOf { if (it != null && it.isSimilar(item)) it.amount else 0 }
