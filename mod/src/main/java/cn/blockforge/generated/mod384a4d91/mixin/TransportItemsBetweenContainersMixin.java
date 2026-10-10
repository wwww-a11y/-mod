package cn.blockforge.generated.mod384a4d91.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.behavior.TransportItemsBetweenContainers;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.HashSet;
import java.util.Set;

/**
 * 铜傀儡"分类搬运"行为（TransportItemsBetweenContainers）的搬运量、节奏与性能调整：
 *
 * 1. 一次搬运的种类和数量（5 个货槽）：
 *    - 原版：货物只占主手一个槽位、且一次最多 16 个 —— 一趟只能搬 1 种 ≤16 个。
 *    - 现在：傀儡用 主手 + 副手 + 胸甲 + 护腿 + 靴子 共 5 个装备槽位当"货槽"，
 *      每个槽拿一整组（上限为该物品自身堆叠上限，普通物品 64），同一物品类型的
 *      货物不会重复占槽：一趟最多搬 5 种 × 整组（普通物品 320 个，约原版 20 倍）。
 *    - 为什么不显示在傀儡身上：铜傀儡的渲染器只画 手物品 / 头部 / 天线 三处，
 *      胸甲、护腿、靴子槽位对傀儡模型完全隐形，货物只看得见两只手拎的东西。
 *    - 为什么安全：这些槽位都是原版实体装备槽，存档、读档、死亡掉落、变成铜像
 *      时的掉落全部走原版通道（每个装过货的槽都 setGuaranteedDrop，死亡必掉，
 *      铜傀儡死亡和变雕像都会调用 dropPreservedEquipment 清掉）。
 *    - 配套的三个判定也改成"看全部货槽"：
 *      isPickingUpItems（5 槽全空才去取货）、
 *      hasItemMatchingHandItem（目标箱子有 5 槽里任意一种同类物品即可放）、
 *      putDownItem（5 个槽依次放入目标箱子，装不下的继续留在身上，下轮接着放）。
 *
 * 2. 到箱子前的 3 秒发呆：
 *    - 原版流程：傀儡走到箱子后进入"交互"状态，内部时钟 ticksSinceReachingTarget
 *      从 1 开始走 —— 第 1 tick 开箱盖并切换交互状态、第 9 tick 播拿/放音效、
 *      第 60 tick（TARGET_INTERACTION_TIME，3 秒）关箱盖、真正拿货/放货、离开。
 *    - 现在：每 tick 在 onReachedTarget 入口检查该时钟，走到第 10 tick 时把时钟
 *      拨到 TARGET_INTERACTION_TIME - 1，让原版下一 tick 自然进入第 60 tick 的
 *      收尾分支（关箱盖、清理 openedChestPos、交接物品、恢复 IDLE 全部照原路走，
 *      不漏状态）。一次交互从 3 秒压缩到约 0.55 秒，开箱盖动画和音效仍然播过。
 *
 * 3. 搬运速度：构造参数 speedModifier 乘以系数，傀儡往返箱子更快。
 *
 * 4. 减卡顿：
 *    - hasValidTravellingPath（重新校验到目标箱子的路径，内部会跑寻路）
 *      原版在走路途中每个 tick 都可能调用；这里把结果按目标坐标缓存若干 tick，
 *      路径失效最多晚 0.4 秒才被发觉，但寻路开销降到一个零头。
 *    - isAnotherMobInteractingWithTarget（在目标周围扫描其他实体）
 *      同样做短时缓存，减少每 tick 的实体扫描。
 *    缓存字段加在行为实例上，每个傀儡的大脑各自持有一份，互不干扰。
 */
@Mixin(TransportItemsBetweenContainers.class)
public abstract class TransportItemsBetweenContainersMixin {

    /** 搬运途中移动速度倍率（只影响分类搬运，不影响平时溜达） */
    @Unique
    private static final float blockforge$SPEED_MULTIPLIER = 1.5f;

    /** 路径校验结果缓存时长（tick） */
    @Unique
    private static final int blockforge$PATH_CACHE_TICKS = 8;

    /** "目标是否被别人占用"缓存时长（tick） */
    @Unique
    private static final int blockforge$OCCUPY_CACHE_TICKS = 4;

    /** 到箱子后正常站立的 tick 数：到第 10 tick 把原版 60 tick 的交互时钟快进，总站立约 0.55 秒 */
    @Unique
    private static final int blockforge$INTERACT_FAST_FORWARD_AT = 10;

    /**
     * 货槽清单：双手 + 胸甲/护腿/靴子三个对傀儡模型不可见的装备槽。
     * 不用头盔槽（CustomHeadLayer 会把它画在头上），也不用天线槽（SADDLE，
     * 是玩家给傀儡装饰花用的）。
     */
    @Unique
    private static final EquipmentSlot[] blockforge$CARGO_SLOTS = {
            EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND,
            EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    /** 原版"到箱子后已经站了几 tick"的私有计时字段（快进用） */
    @Shadow
    private int ticksSinceReachingTarget;

    @Unique
    private int blockforge$lastPathCheckTick = Integer.MIN_VALUE;

    @Unique
    private BlockPos blockforge$pathCachePos;

    @Unique
    private boolean blockforge$pathCacheResult;

    @Unique
    private long blockforge$lastOccupyCheckTime = Long.MIN_VALUE;

    @Unique
    private BlockPos blockforge$occupyCachePos;

    @Unique
    private boolean blockforge$occupyCacheResult;

    /** 行为结束后清记忆 / 保留目标继续放货，两个原版方法在替换逻辑里要用到 */
    @Shadow
    protected void clearMemoriesAfterMatchingTargetFound(PathfinderMob mob) {
        throw new UnsupportedOperationException("shadow");
    }

    @Shadow
    protected void stopTargetingCurrentTarget(PathfinderMob mob) {
        throw new UnsupportedOperationException("shadow");
    }

    @ModifyVariable(method = "<init>", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private static float blockforge$boostCarrySpeed(float speedModifier) {
        return speedModifier * blockforge$SPEED_MULTIPLIER;
    }

    // ------------------------------------------------------------------
    // 到箱子前的发呆压缩：快进原版的 60 tick 交互时钟
    // ------------------------------------------------------------------

    /**
     * 傀儡进入"已在箱子前"状态后每 tick 调用一次。原版在这里自增
     * ticksSinceReachingTarget，并靠 "第 60 tick 关箱盖 / >=60 交接物品" 驱动节奏。
     * 我们在入口把时钟拨到 59，原版下一 tick 自增后正好命中它的收尾分支：
     * 箱盖关闭、openedChestPos 清理、拿货/放货、恢复 IDLE 都走原代码，不跳步。
     */
    @Inject(method = "onReachedTarget", at = @At("HEAD"))
    private void blockforge$fastForwardInteractionClock(
            TransportItemsBetweenContainers.TransportItemTarget target,
            Level level, PathfinderMob mob, CallbackInfo ci) {
        if (this.ticksSinceReachingTarget >= blockforge$INTERACT_FAST_FORWARD_AT) {
            this.ticksSinceReachingTarget = TransportItemsBetweenContainers.TARGET_INTERACTION_TIME - 1;
        }
    }

    // ------------------------------------------------------------------
    // 一次搬运 5 种 × 整组：取货 / 放货 / 状态判定
    // ------------------------------------------------------------------

    /**
     * "该去取货还是该去放货"的判定：原版只看主手。
     * 这里改成 5 个货槽全空才回到取货状态，否则继续找放货的箱子。
     */
    @Inject(method = "isPickingUpItems", at = @At("HEAD"), cancellable = true)
    private static void blockforge$allCargoEmptyMeansPicking(PathfinderMob mob,
                                                             CallbackInfoReturnable<Boolean> cir) {
        for (EquipmentSlot slot : blockforge$CARGO_SLOTS) {
            if (!mob.getItemBySlot(slot).isEmpty()) {
                cir.setReturnValue(false);
                return;
            }
        }
        cir.setReturnValue(true);
    }

    /**
     * "目标箱子能不能放"的种类匹配：原版只比对主手物品。
     * 这里箱子里只要有 5 个货槽里任意一种同类物品就可以放
     * （空箱子仍然由调用方 matchesLeavingItemsRequirement 直接放行）。
     */
    @Inject(method = "hasItemMatchingHandItem", at = @At("HEAD"), cancellable = true)
    private static void blockforge$matchAnyCargoSlot(PathfinderMob mob, Container container,
                                                     CallbackInfoReturnable<Boolean> cir) {
        Set<Item> carried = new HashSet<>();
        for (EquipmentSlot slot : blockforge$CARGO_SLOTS) {
            ItemStack stack = mob.getItemBySlot(slot);
            if (!stack.isEmpty()) {
                carried.add(stack.getItem());
            }
        }
        if (carried.isEmpty()) {
            cir.setReturnValue(false);
            return;
        }
        for (ItemStack stack : container) {
            if (!stack.isEmpty() && carried.contains(stack.getItem())) {
                cir.setReturnValue(true);
                return;
            }
        }
        cir.setReturnValue(false);
    }

    /**
     * 取货：替换原版"只拿第一个槽位、最多 16 个"的逻辑。
     * 依次给 5 个货槽各拿"一种"物品（同种物品跨槽位合并、拿满整组），
     * 已拿过的物品类型不再重复拿，剩下的留给下一趟。
     */
    @Inject(method = "pickUpItems", at = @At("HEAD"), cancellable = true)
    private void blockforge$pickUpFiveKinds(PathfinderMob mob, Container source, CallbackInfo ci) {
        ci.cancel();

        Set<Item> takenKinds = new HashSet<>();
        for (EquipmentSlot slot : blockforge$CARGO_SLOTS) {
            ItemStack taken = blockforge$takeOneKind(source, takenKinds);
            mob.setItemSlot(slot, taken);
            if (!taken.isEmpty()) {
                mob.setGuaranteedDrop(slot);
                takenKinds.add(taken.getItem());
            }
        }

        source.setChanged();
        this.clearMemoriesAfterMatchingTargetFound(mob);
    }

    /**
     * 放货：5 个货槽依次放入目标箱子（合并同类堆叠 / 放进空槽位）。
     * 箱子装不下的部分留在身上，并保留当前目标稍后继续放，
     * 和原版"手里有剩余就 stopTargetingCurrentTarget"的节奏一致。
     */
    @Inject(method = "putDownItem", at = @At("HEAD"), cancellable = true)
    private void blockforge$putDownAllCargo(PathfinderMob mob, Container destination, CallbackInfo ci) {
        ci.cancel();

        boolean anyLeft = false;
        for (EquipmentSlot slot : blockforge$CARGO_SLOTS) {
            ItemStack left = blockforge$insertCargoStack(mob.getItemBySlot(slot), destination);
            mob.setItemSlot(slot, left);
            if (!left.isEmpty()) {
                anyLeft = true;
            }
        }

        destination.setChanged();
        if (anyLeft) {
            this.stopTargetingCurrentTarget(mob);
        } else {
            this.clearMemoriesAfterMatchingTargetFound(mob);
        }
    }

    /**
     * 从源箱子里取"一种"物品：优先第一个物品类型不在排除集合里的非空槽位，
     * 然后把同种（物品和组件都相同，保证能安全并成一组）的其余槽位也并进这一组，
     * 直到达到该物品自身的堆叠上限（普通物品 64，蛋 16，工具 1，各自拿满）。
     */
    @Unique
    private static ItemStack blockforge$takeOneKind(Container source, Set<Item> excludedKinds) {
        ItemStack result = ItemStack.EMPTY;
        for (int i = 0; i < source.getContainerSize(); i++) {
            ItemStack stack = source.getItem(i);
            if (stack.isEmpty() || excludedKinds.contains(stack.getItem())) {
                continue;
            }
            if (result.isEmpty()) {
                int cap = stack.getMaxStackSize();
                result = source.removeItem(i, Math.min(stack.getCount(), Math.max(1, cap)));
            } else if (result.getCount() < result.getMaxStackSize()
                    && ItemStack.isSameItemSameComponents(result, stack)) {
                int take = Math.min(stack.getCount(), result.getMaxStackSize() - result.getCount());
                if (take > 0) {
                    result.grow(source.removeItem(i, take).getCount());
                }
            }
            if (!result.isEmpty() && result.getCount() >= result.getMaxStackSize()) {
                break;
            }
        }
        return result;
    }

    /**
     * 把身上的一整组物品放进目标箱子：先和同种堆叠合并，找不到就放进第一个空槽位。
     * 逻辑对齐原版 addItemsToContainer（原版一次交互里也是"合并 + 填槽"连续处理），
     * 但修掉了原版在手里数量小于空位时把数量减成负数的写法，按实际移动量扣减。
     * 返回身上剩下的（箱子满了就是一整组原样返回）。
     */
    @Unique
    private static ItemStack blockforge$insertCargoStack(ItemStack cargo, Container destination) {
        if (cargo.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack leftover = cargo.copy();
        for (int i = 0; i < destination.getContainerSize() && !leftover.isEmpty(); i++) {
            ItemStack slotStack = destination.getItem(i);
            if (slotStack.isEmpty()) {
                destination.setItem(i, leftover);
                return ItemStack.EMPTY;
            }
            if (ItemStack.isSameItemSameComponents(leftover, slotStack)
                    && slotStack.getCount() < slotStack.getMaxStackSize()) {
                int room = Math.min(slotStack.getMaxStackSize() - slotStack.getCount(),
                        leftover.getCount());
                slotStack.grow(room);
                destination.setItem(i, slotStack);
                leftover.shrink(room);
            }
        }
        return leftover;
    }

    // ------------------------------------------------------------------
    // 减卡顿：路径校验与占用检查的短时缓存
    // ------------------------------------------------------------------

    /** start 有两个重载（PathfinderMob 与桥接的 LivingEntity），必须写全描述符 */
    @Inject(method = "start(Lnet/minecraft/server/level/ServerLevel;"
            + "Lnet/minecraft/world/entity/PathfinderMob;J)V", at = @At("HEAD"))
    private void blockforge$resetCaches(ServerLevel serverLevel, PathfinderMob mob,
                                       long l, CallbackInfo ci) {
        this.blockforge$lastPathCheckTick = Integer.MIN_VALUE;
        this.blockforge$pathCachePos = null;
        this.blockforge$lastOccupyCheckTime = Long.MIN_VALUE;
        this.blockforge$occupyCachePos = null;
    }

    @Inject(method = "hasValidTravellingPath", at = @At("HEAD"), cancellable = true)
    private void blockforge$reusePathResult(Level level,
                                            TransportItemsBetweenContainers.TransportItemTarget target,
                                            PathfinderMob mob,
                                            CallbackInfoReturnable<Boolean> cir) {
        int now = mob.tickCount;
        if (this.blockforge$pathCachePos != null
                && this.blockforge$pathCachePos.equals(target.pos())
                && now - this.blockforge$lastPathCheckTick >= 0
                && now - this.blockforge$lastPathCheckTick < blockforge$PATH_CACHE_TICKS) {
            cir.setReturnValue(this.blockforge$pathCacheResult);
        }
    }

    @Inject(method = "hasValidTravellingPath", at = @At("RETURN"))
    private void blockforge$storePathResult(Level level,
                                            TransportItemsBetweenContainers.TransportItemTarget target,
                                            PathfinderMob mob,
                                            CallbackInfoReturnable<Boolean> cir) {
        this.blockforge$lastPathCheckTick = mob.tickCount;
        this.blockforge$pathCachePos = target.pos();
        this.blockforge$pathCacheResult = cir.getReturnValue();
    }

    /** 该方法签名里没有 mob，用世界时间做缓存键 */
    @Inject(method = "isAnotherMobInteractingWithTarget", at = @At("HEAD"), cancellable = true)
    private void blockforge$reuseOccupyResult(TransportItemsBetweenContainers.TransportItemTarget target,
                                              Level level,
                                              CallbackInfoReturnable<Boolean> cir) {
        long now = level.getDefaultClockTime();
        if (this.blockforge$occupyCachePos != null
                && this.blockforge$occupyCachePos.equals(target.pos())
                && now - this.blockforge$lastOccupyCheckTime >= 0
                && now - this.blockforge$lastOccupyCheckTime < blockforge$OCCUPY_CACHE_TICKS) {
            cir.setReturnValue(this.blockforge$occupyCacheResult);
        }
    }

    @Inject(method = "isAnotherMobInteractingWithTarget", at = @At("RETURN"))
    private void blockforge$storeOccupyResult(TransportItemsBetweenContainers.TransportItemTarget target,
                                              Level level,
                                              CallbackInfoReturnable<Boolean> cir) {
        this.blockforge$lastOccupyCheckTime = level.getDefaultClockTime();
        this.blockforge$occupyCachePos = target.pos();
        this.blockforge$occupyCacheResult = cir.getReturnValue();
    }
}
