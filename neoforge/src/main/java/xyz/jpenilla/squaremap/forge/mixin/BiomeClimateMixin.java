package xyz.jpenilla.squaremap.forge.mixin;

import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import xyz.jpenilla.squaremap.common.util.BiomeClimateAccess;

@Mixin(Biome.class)
abstract class BiomeClimateMixin implements BiomeClimateAccess {
    @Override
    public float squaremap$downfall() {
        return ((Biome) (Object) this).getModifiedClimateSettings().downfall();
    }
}
