package com.github.marcoral.versatia.util.inventory

import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack

/**
 * `PlayerInventory` indices of the armor and off-hand slots, as `PlayerInventory.getItem(Int)` and
 * `InventoryClickEvent.slot` use them; not raw slots (`InventoryClickEvent.rawSlot`), which number
 * the whole view and put the armor at 5-8 and the off hand at 45 in the player's own inventory.
 */
object PlayerInventorySlots {
    const val BOOTS = 36
    const val LEGGINGS = 37
    const val CHESTPLATE = 38
    const val HELMET = 39
    const val OFF_HAND = 40
}

/** Maps a `PlayerInventory` index (see [PlayerInventorySlots]) to the equipment slot it holds, or null for storage and hotbar slots. */
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
