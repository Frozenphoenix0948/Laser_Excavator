package de.balto.laserexcavator.block.excavator;

import com.mojang.serialization.MapCodec;
import de.balto.laserexcavator.block.blockentities.ExcavatorBlockEntity;
import de.balto.laserexcavator.block.blockentities.ModBlockEntities;
import de.balto.laserexcavator.config.LaserExcavatorConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class ExcavatorBlock extends BaseEntityBlock {
    public static final MapCodec<ExcavatorBlock> CODEC = simpleCodec(ExcavatorBlock::new);
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;

    public ExcavatorBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, net.minecraft.core.Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    public void appendHoverText(
            ItemStack stack,
            Item.TooltipContext context,
            List<Component> tooltipComponents,
            TooltipFlag tooltipFlag
    ) {
        super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);

        tooltipComponents.add(description("Base performance without upgrades."));
        tooltipComponents.add(value("Mining interval", LaserExcavatorConfig.speedInterval(0) + " ticks"));
        tooltipComponents.add(value(
                "Energy use",
                LaserExcavatorConfig.energyPerBlock(0) + " FE/block ("
                        + LaserExcavatorConfig.energyPerTick(0, 0) + " FE/t)"
        ));
    }

    private static Component description(String text) {
        return Component.literal(text).withStyle(ChatFormatting.GRAY);
    }

    private static Component value(String label, String value) {
        return Component.literal(label + ": ")
                .withStyle(ChatFormatting.DARK_GRAY)
                .append(Component.literal(value).withStyle(ChatFormatting.AQUA));
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        // The machine points in the direction the player was looking when it was placed.
        // The excavation selection therefore extends away from the player.
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection());
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ExcavatorBlockEntity(pos, state);
    }


    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level,
            BlockState state,
            BlockEntityType<T> blockEntityType
    ) {
        return level.isClientSide
                ? null
                : createTickerHelper(
                        blockEntityType,
                        ModBlockEntities.EXCAVATOR.get(),
                        ExcavatorBlockEntity::serverTick
                );
    }

    @Override
    protected void onRemove(
            BlockState state,
            Level level,
            BlockPos pos,
            BlockState newState,
            boolean movedByPiston
    ) {
        if (!level.isClientSide && !state.is(newState.getBlock())) {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity instanceof ExcavatorBlockEntity excavator) {
                dropAndClear(level, pos, excavator.getOutputInventory());
                dropAndClear(level, pos, excavator.getUpgradeInventory());
                dropAndClear(level, pos, excavator.getFuelInventory());
            }
        }

        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    private static void dropAndClear(Level level, BlockPos pos, ItemStackHandler inventory) {
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            var stack = inventory.getStackInSlot(slot);
            if (stack.isEmpty()) continue;

            Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), stack.copy());
            inventory.setStackInSlot(slot, ItemStack.EMPTY);
        }
    }

    @Override
    protected @Nullable MenuProvider getMenuProvider(BlockState state, Level level, BlockPos pos) {
        return null;
    }

    @Override
    protected InteractionResult useWithoutItem(
            BlockState state,
            Level level,
            BlockPos pos,
            Player player,
            BlockHitResult hitResult
    ) {
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            BlockEntity blockEntity = level.getBlockEntity(pos);

            if (blockEntity instanceof ExcavatorBlockEntity excavator) {
                serverPlayer.openMenu(excavator, buffer -> {
                    buffer.writeBlockPos(pos);
                    buffer.writeVarInt(excavator.getUpgradeSlotCount());
                    buffer.writeBoolean(excavator.isFuelSlotEnabled());
                });
            }
        }

        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
