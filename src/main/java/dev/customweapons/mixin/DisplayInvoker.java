package dev.customweapons.mixin;

import com.mojang.math.Transformation;
import net.minecraft.util.Brightness;
import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The display entity's setters are private; these are the ones the effects need. */
@Mixin(Display.class)
public interface DisplayInvoker {

    @Invoker("setTransformation")
    void customweapons$setTransformation(Transformation transformation);

    @Invoker("setTransformationInterpolationDuration")
    void customweapons$setTransformationInterpolationDuration(int ticks);

    @Invoker("setTransformationInterpolationDelay")
    void customweapons$setTransformationInterpolationDelay(int ticks);

    @Invoker("setPosRotInterpolationDuration")
    void customweapons$setPosRotInterpolationDuration(int ticks);

    @Invoker("setBrightnessOverride")
    void customweapons$setBrightnessOverride(Brightness brightness);

    @Invoker("setViewRange")
    void customweapons$setViewRange(float range);

    @Invoker("setShadowRadius")
    void customweapons$setShadowRadius(float radius);

    @Invoker("setGlowColorOverride")
    void customweapons$setGlowColorOverride(int colour);
}
