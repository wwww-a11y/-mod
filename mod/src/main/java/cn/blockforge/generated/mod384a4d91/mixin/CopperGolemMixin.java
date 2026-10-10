package cn.blockforge.generated.mod384a4d91.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;

/**
 * 加快铜傀儡的分类节奏。
 *
 * 原版行为 TransportItemsBetweenContainers 在"暂时找不到可收/可放的容器"后，
 * 会把记忆 TRANSPORT_ITEMS_COOLDOWN_TICKS 设成一个较长的冷却 tick 数，
 * 冷却期间傀儡完全不找下一个箱子，表现为"分完一箱发呆很久"。
 * 这里每 tick 把该冷却压到一个很小的上限，傀儡就能立刻接下一单。
 */
@Mixin(CopperGolem.class)
public abstract class CopperGolemMixin {

    /** 冷却上限（tick）。8 tick = 0.4 秒，比原版短得多，但不会让空闲傀儡每个 tick 都全图扫描。 */
    @Unique
    private static final int blockforge$MIN_SORT_COOLDOWN = 8;

    @Inject(method = "customServerAiStep", at = @At("HEAD"))
    private void blockforge$shortenSortCooldown(ServerLevel serverLevel, CallbackInfo ci) {
        CopperGolem golem = (CopperGolem) (Object) this;
        var brain = golem.getBrain();
        Optional<?> cooldown = brain.getMemory(MemoryModuleType.TRANSPORT_ITEMS_COOLDOWN_TICKS);
        if (cooldown.orElse(null) instanceof Integer ticks
                && ticks > (Integer) blockforge$MIN_SORT_COOLDOWN) {
            brain.setMemory(MemoryModuleType.TRANSPORT_ITEMS_COOLDOWN_TICKS,
                    blockforge$MIN_SORT_COOLDOWN);
        }
    }
}
