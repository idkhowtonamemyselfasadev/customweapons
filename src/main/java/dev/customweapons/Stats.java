package dev.customweapons;

import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.component.ItemAttributeModifiers;

/** Builds the attribute component for a melee weapon. */
public final class Stats {

    /** A player swings with 1.0 damage and 4.0 speed before any weapon is held. */
    private static final double BASE_ATTACK_DAMAGE = 1.0;
    private static final double BASE_ATTACK_SPEED = 4.0;

    private Stats() {
    }

    /**
     * @param totalDamage the number shown on the item tooltip, not the modifier value
     * @param totalSpeed  attacks per second, likewise
     */
    public static ItemAttributeModifiers melee(String weaponId, double totalDamage, double totalSpeed) {
        // Setting this component replaces the base item's whole default set, so both
        // modifiers have to be listed - leaving out attack speed would leave the weapon at
        // the bare-hand 4.0.
        return ItemAttributeModifiers.builder()
                .add(Attributes.ATTACK_DAMAGE,
                        new AttributeModifier(
                                Identifier.parse("customweapons:" + weaponId + "_attack_damage"),
                                totalDamage - BASE_ATTACK_DAMAGE,
                                AttributeModifier.Operation.ADD_VALUE),
                        EquipmentSlotGroup.MAINHAND)
                .add(Attributes.ATTACK_SPEED,
                        new AttributeModifier(
                                Identifier.parse("customweapons:" + weaponId + "_attack_speed"),
                                totalSpeed - BASE_ATTACK_SPEED,
                                AttributeModifier.Operation.ADD_VALUE),
                        EquipmentSlotGroup.MAINHAND)
                .build();
    }
}
