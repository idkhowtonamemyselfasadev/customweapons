package dev.customweapons;

import net.minecraft.world.item.Item;

/** One line of an altar's price: an item and how many of it. */
public record ItemCost(Item item, int count) {
}
