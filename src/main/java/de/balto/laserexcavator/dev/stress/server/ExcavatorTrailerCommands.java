package de.balto.laserexcavator.dev.stress.server;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import de.balto.laserexcavator.LaserExcavator;
import de.balto.laserexcavator.block.ModBlocks;
import de.balto.laserexcavator.block.blockentities.ExcavatorBlockEntity;
import de.balto.laserexcavator.block.excavator.ExcavatorBlock;
import de.balto.laserexcavator.block.excavator.ExcavatorScanState;
import de.balto.laserexcavator.config.LaserExcavatorConfig;
import de.balto.laserexcavator.item.ModItems;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.items.ItemStackHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@EventBusSubscriber(modid = LaserExcavator.MODID)
public final class ExcavatorTrailerCommands {
    private static final int EXCAVATION_WIDTH = 32;
    private static final int EXCAVATION_HEIGHT = 256;
    private static final int EXCAVATION_LENGTH = 32;
    private static final int MACHINE_SPACING = EXCAVATION_WIDTH + 1;
    private static final int SETUP_FORWARD_OFFSET = 20;

    private static final int FOUNDATION_BLOCKS_PER_TICK = 1024;
    private static final int MACHINES_PER_TICK = 16;
    private static final int MACHINE_START_CHECKS_PER_TICK = 64;
    private static final int MAINTENANCE_PERIOD_TICKS = 20;

    private static TrailerBuild activeBuild;
    private static TrailerRun activeRun;
    private static TrailerSetup activeSetup;

    private ExcavatorTrailerCommands() {}

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("excavatortrailer")
                        .requires(source -> LaserExcavatorConfig.stressTestCommandsEnabled() && source.hasPermission(2))
                        .then(Commands.literal("setup")
                                .then(Commands.literal("1")
                                        .executes(context -> startSetup(context.getSource(), Layout.ONE)))
                                .then(Commands.literal("10")
                                        .executes(context -> startSetup(context.getSource(), Layout.TEN)))
                                .then(Commands.literal("100")
                                        .executes(context -> startSetup(context.getSource(), Layout.HUNDRED)))
                                .then(Commands.literal("720")
                                        .executes(context -> startSetup(context.getSource(), Layout.SEVEN_TWENTY)))
                                .then(Commands.literal("clear")
                                        .executes(context -> clearSetup(context.getSource()))))
                        .then(Commands.literal("status")
                                .executes(context -> status(context.getSource())))
                        .then(Commands.literal("cancel")
                                .executes(context -> cancelBuild(context.getSource())))
        );
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();

        TrailerBuild build = activeBuild;
        if (build != null) {
            if (!LaserExcavatorConfig.stressTestCommandsEnabled() || build.level.getServer() != server) {
                activeBuild = null;
            } else {
                build.tick();
                if (build.isDone()) {
                    activeBuild = null;
                    activeRun = build.run;
                    activeSetup = build.setup;
                    build.run.activate();

                    ServerPlayer owner = server.getPlayerList().getPlayer(build.owner);
                    if (owner != null) {
                        owner.sendSystemMessage(Component.literal(
                                "Grid setup ready: " + build.setup.count + " excavator"
                                        + (build.setup.count == 1 ? "" : "s")
                                        + " (" + build.setup.columns + "x" + build.setup.rows + ")."
                        ));
                    }
                }
            }
        }

        TrailerRun run = activeRun;
        if (run != null) {
            if (!LaserExcavatorConfig.stressTestCommandsEnabled() || run.level.getServer() != server) {
                run.disableResourceBypass();
                activeRun = null;
            } else {
                run.tick();
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        activeBuild = null;
        activeRun = null;
        activeSetup = null;
    }

    private static int startSetup(CommandSourceStack source, Layout layout) throws CommandSyntaxException {
        if (activeBuild != null) {
            source.sendFailure(Component.literal(
                    "A grid setup is still being built. Use /excavatortrailer status or cancel first."
            ));
            return 0;
        }

        ServerPlayer player = source.getPlayerOrException();
        ServerLevel level = source.getLevel();
        clearManagedMachines(level);

        Direction playerFacing = player.getDirection();
        BlockPos playerPos = player.blockPosition();
        BlockPos center = playerPos.relative(playerFacing, SETUP_FORWARD_OFFSET);
        int foundationY = playerPos.getY() - 1;
        int machineY = foundationY + 1;

        TrailerSetup setup = createSetup(layout, center.getX(), center.getZ(), machineY);
        List<FoundationRow> foundation = createFoundation(setup, foundationY);
        List<MachineSpec> machines = createMachines(setup);
        TrailerRun run = new TrailerRun(level, machines.size());

        activeRun = null;
        activeSetup = null;
        activeBuild = new TrailerBuild(level, player.getUUID(), setup, foundation, machines, run);

        source.sendSuccess(
                () -> Component.literal(
                        "Building grid setup " + layout.count + " (" + layout.columns + "x" + layout.rows + ") "
                                + "around " + setup.centerX + ", " + setup.centerZ + ". "
                                + "Machines use the isolated stress-test configuration: 32x256x32, Speed V, "
                                + "Efficiency V and Area III."
                ),
                true
        );
        return 1;
    }

    private static int clearSetup(CommandSourceStack source) {
        int removed = clearManagedMachines(source.getLevel());
        activeBuild = null;
        activeRun = null;
        activeSetup = null;
        source.sendSuccess(
                () -> Component.literal("Cleared " + removed + " managed grid excavator(s). Foundation lines were left in place."),
                true
        );
        return 1;
    }

    private static int cancelBuild(CommandSourceStack source) {
        if (activeBuild == null) {
            source.sendSuccess(() -> Component.literal("No grid setup build is active."), false);
            return 1;
        }
        activeBuild = null;
        source.sendSuccess(
                () -> Component.literal("Cancelled grid setup generation. Already placed blocks were left in place."),
                true
        );
        return 1;
    }

    private static int status(CommandSourceStack source) {
        TrailerBuild build = activeBuild;
        if (build != null) {
            source.sendSuccess(
                    () -> Component.literal(
                            "Grid build: " + build.setup.count + " machines, foundation "
                                    + build.foundationDone + "/" + build.totalFoundationBlocks
                                    + ", excavators " + build.machineIndex + "/" + build.machines.size() + "."
                    ),
                    false
            );
            return 1;
        }

        TrailerRun run = activeRun;
        TrailerSetup setup = activeSetup;
        if (run != null && setup != null) {
            RunStats stats = run.collectStats();
            source.sendSuccess(
                    () -> Component.literal(
                            "Grid setup: " + setup.count + " (" + setup.columns + "x" + setup.rows + "), "
                                    + "loaded=" + stats.loaded
                                    + ", scanning=" + stats.scanning
                                    + ", ready=" + stats.ready
                                    + ", excavating=" + stats.excavating
                                    + ", storage-full=" + stats.storageFull
                                    + ", complete=" + stats.complete
                                    + ", startup-pending=" + run.startupPending.size() + "."
                    ),
                    false
            );
            return 1;
        }

        source.sendSuccess(() -> Component.literal("No grid setup is active."), false);
        return 1;
    }

    private static int clearManagedMachines(ServerLevel level) {
        int removed = 0;
        TrailerRun run = activeRun;
        if (run != null && run.level == level) {
            for (BlockPos pos : run.machines) {
                if (discardMachineBlock(level, pos)) removed++;
            }
        }

        TrailerBuild build = activeBuild;
        if (build != null && build.level == level) {
            for (int i = 0; i < build.machineIndex && i < build.machines.size(); i++) {
                if (discardMachineBlock(level, build.machines.get(i).pos)) removed++;
            }
        }
        return removed;
    }

    private static boolean discardMachineBlock(ServerLevel level, BlockPos pos) {
        if (!level.getBlockState(pos).is(ModBlocks.EXCAVATOR.get())) return false;

        if (level.getBlockEntity(pos) instanceof ExcavatorBlockEntity excavator) {
            clearInventory(excavator.getOutputInventory());
            clearInventory(excavator.getUpgradeInventory());
            clearInventory(excavator.getFuelInventory());
        }
        return level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
    }

    private static void clearInventory(ItemStackHandler inventory) {
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            if (!inventory.getStackInSlot(slot).isEmpty()) {
                inventory.setStackInSlot(slot, ItemStack.EMPTY);
            }
        }
    }

    private static TrailerSetup createSetup(Layout layout, int anchorX, int anchorZ, int machineY) {
        int firstX = anchorX - (layout.columns / 2) * MACHINE_SPACING;
        int firstZ = anchorZ - (layout.rows / 2) * MACHINE_SPACING;
        int lastX = firstX + (layout.columns - 1) * MACHINE_SPACING;
        int lastZ = firstZ + (layout.rows - 1) * MACHINE_SPACING;
        double visualCenterX = (firstX + lastX) * 0.5D;
        double visualCenterZ = (firstZ + lastZ) * 0.5D;
        return new TrailerSetup(
                layout.count,
                layout.columns,
                layout.rows,
                visualCenterX,
                visualCenterZ,
                machineY,
                firstX,
                lastX,
                firstZ,
                lastZ
        );
    }

    private static List<FoundationRow> createFoundation(TrailerSetup setup, int y) {
        ArrayList<FoundationRow> rows = new ArrayList<>(setup.rows);
        int minX = setup.firstX - EXCAVATION_WIDTH / 2;
        int maxX = setup.lastX + EXCAVATION_WIDTH / 2 - 1;
        for (int row = 0; row < setup.rows; row++) {
            int z = setup.firstZ + row * MACHINE_SPACING;
            rows.add(new FoundationRow(minX, maxX, y, z));
        }
        return rows;
    }

    private static List<MachineSpec> createMachines(TrailerSetup setup) {
        ArrayList<MachineSpec> machines = new ArrayList<>(setup.count);
        for (int row = 0; row < setup.rows; row++) {
            int z = setup.firstZ + row * MACHINE_SPACING;
            for (int column = 0; column < setup.columns; column++) {
                int x = setup.firstX + column * MACHINE_SPACING;
                machines.add(new MachineSpec(new BlockPos(x, setup.machineY, z), Direction.NORTH));
            }
        }
        if (machines.size() != setup.count) {
            throw new IllegalStateException("Expected " + setup.count + " grid excavators, got " + machines.size());
        }
        return machines;
    }

    private static final class TrailerBuild {
        private final ServerLevel level;
        private final UUID owner;
        private final TrailerSetup setup;
        private final List<FoundationRow> foundation;
        private final List<MachineSpec> machines;
        private final TrailerRun run;
        private final BlockState stone = Blocks.STONE.defaultBlockState();
        private final long totalFoundationBlocks;

        private int foundationRowIndex;
        private int foundationX;
        private long foundationDone;
        private int machineIndex;
        private boolean done;

        private TrailerBuild(
                ServerLevel level,
                UUID owner,
                TrailerSetup setup,
                List<FoundationRow> foundation,
                List<MachineSpec> machines,
                TrailerRun run
        ) {
            this.level = level;
            this.owner = owner;
            this.setup = setup;
            this.foundation = foundation;
            this.machines = machines;
            this.run = run;
            this.foundationX = foundation.isEmpty() ? 0 : foundation.getFirst().minX;

            long total = 0L;
            for (FoundationRow row : foundation) total += row.maxX - row.minX + 1L;
            this.totalFoundationBlocks = total;
        }

        private void tick() {
            if (foundationRowIndex < foundation.size()) {
                tickFoundation(FOUNDATION_BLOCKS_PER_TICK);
                return;
            }
            if (machineIndex < machines.size()) {
                tickMachines(MACHINES_PER_TICK);
                return;
            }
            done = true;
        }

        private void tickFoundation(int budget) {
            while (budget > 0 && foundationRowIndex < foundation.size()) {
                FoundationRow row = foundation.get(foundationRowIndex);
                while (budget > 0 && foundationX <= row.maxX) {
                    BlockPos pos = new BlockPos(foundationX, row.y, row.z);
                    level.setBlock(pos, stone, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                    foundationX++;
                    foundationDone++;
                    budget--;
                }
                if (foundationX > row.maxX) {
                    foundationRowIndex++;
                    if (foundationRowIndex < foundation.size()) {
                        foundationX = foundation.get(foundationRowIndex).minX;
                    }
                }
            }
        }

        private void tickMachines(int budget) {
            while (budget-- > 0 && machineIndex < machines.size()) {
                MachineSpec spec = machines.get(machineIndex++);
                if (placeAndConfigure(spec)) run.add(spec.pos);
            }
        }

        private boolean placeAndConfigure(MachineSpec spec) {
            BlockState state = ModBlocks.EXCAVATOR.get()
                    .defaultBlockState()
                    .setValue(ExcavatorBlock.FACING, spec.facing);
            if (!level.setBlock(spec.pos, state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE)) return false;
            if (!(level.getBlockEntity(spec.pos) instanceof ExcavatorBlockEntity excavator)) return false;

            ItemStackHandler upgrades = excavator.getUpgradeInventory();
            upgrades.setStackInSlot(0, new ItemStack(ModItems.SPEED_UPGRADE_TIER_5.get()));
            upgrades.setStackInSlot(1, new ItemStack(ModItems.ENERGY_EFFICIENCY_UPGRADE_TIER_5.get()));
            upgrades.setStackInSlot(2, new ItemStack(ModItems.AREA_UPGRADE_128.get()));

            excavator.setSelectionWidth(EXCAVATION_WIDTH);
            excavator.setSelectionHeight(EXCAVATION_HEIGHT);
            excavator.setSelectionLength(EXCAVATION_LENGTH);
            return true;
        }

        private boolean isDone() {
            return done;
        }
    }

    private static final class TrailerRun {
        private final ServerLevel level;
        private final ArrayList<BlockPos> machines;
        private final ArrayList<BlockPos> startupPending;
        private int startupCursor;
        private int maintenanceCursor;
        private boolean active;

        private TrailerRun(ServerLevel level, int expectedMachines) {
            this.level = level;
            this.machines = new ArrayList<>(expectedMachines);
            this.startupPending = new ArrayList<>(expectedMachines);
        }

        private void add(BlockPos pos) {
            BlockPos immutable = pos.immutable();
            machines.add(immutable);
            startupPending.add(immutable);
        }

        private void activate() {
            active = true;
        }

        private void tick() {
            if (!active) return;
            tickMaintenance();

            if (startupPending.isEmpty()) return;
            int checks = Math.min(MACHINE_START_CHECKS_PER_TICK, startupPending.size());
            while (checks-- > 0 && !startupPending.isEmpty()) {
                if (startupCursor >= startupPending.size()) startupCursor = 0;
                BlockPos pos = startupPending.get(startupCursor);
                if (!level.hasChunkAt(pos)) {
                    startupCursor++;
                    continue;
                }
                if (!(level.getBlockEntity(pos) instanceof ExcavatorBlockEntity excavator)) {
                    startupPending.remove(startupCursor);
                    continue;
                }

                serviceMachine(excavator);
                ExcavatorScanState state = excavator.getScanState();
                if (state == ExcavatorScanState.IDLE) {
                    excavator.beginScan();
                    startupCursor++;
                } else if (state == ExcavatorScanState.READY) {
                    excavator.beginExcavation();
                    startupPending.remove(startupCursor);
                } else if (state == ExcavatorScanState.EXCAVATING
                        || state == ExcavatorScanState.STORAGE_FULL
                        || state == ExcavatorScanState.COMPLETE) {
                    startupPending.remove(startupCursor);
                } else {
                    startupCursor++;
                }
            }
        }

        private void tickMaintenance() {
            if (machines.isEmpty()) return;
            int budget = Math.max(1, (machines.size() + MAINTENANCE_PERIOD_TICKS - 1) / MAINTENANCE_PERIOD_TICKS);
            while (budget-- > 0 && !machines.isEmpty()) {
                if (maintenanceCursor >= machines.size()) maintenanceCursor = 0;
                BlockPos pos = machines.get(maintenanceCursor++);
                if (!level.hasChunkAt(pos)) continue;
                if (level.getBlockEntity(pos) instanceof ExcavatorBlockEntity excavator) {
                    serviceMachine(excavator);
                }
            }
        }

        private void serviceMachine(ExcavatorBlockEntity excavator) {
            excavator.debugSetBenchmarkResourceBypass(true);
        }

        private void disableResourceBypass() {
            for (BlockPos pos : machines) {
                if (!level.hasChunkAt(pos)) continue;
                if (level.getBlockEntity(pos) instanceof ExcavatorBlockEntity excavator) {
                    excavator.debugSetBenchmarkResourceBypass(false);
                }
            }
        }

        private RunStats collectStats() {
            int loaded = 0;
            int scanning = 0;
            int ready = 0;
            int excavating = 0;
            int storageFull = 0;
            int complete = 0;

            for (BlockPos pos : machines) {
                if (!level.hasChunkAt(pos)) continue;
                if (!(level.getBlockEntity(pos) instanceof ExcavatorBlockEntity excavator)) continue;
                loaded++;
                switch (excavator.getScanState()) {
                    case SCANNING -> scanning++;
                    case READY -> ready++;
                    case EXCAVATING -> excavating++;
                    case STORAGE_FULL -> storageFull++;
                    case COMPLETE -> complete++;
                    default -> {}
                }
            }
            return new RunStats(loaded, scanning, ready, excavating, storageFull, complete);
        }
    }

    private enum Layout {
        ONE(1, 1, 1),
        TEN(10, 5, 2),
        HUNDRED(100, 10, 10),
        SEVEN_TWENTY(720, 30, 24);

        private final int count;
        private final int columns;
        private final int rows;

        Layout(int count, int columns, int rows) {
            this.count = count;
            this.columns = columns;
            this.rows = rows;
        }
    }

    private record TrailerSetup(
            int count,
            int columns,
            int rows,
            double centerX,
            double centerZ,
            int machineY,
            int firstX,
            int lastX,
            int firstZ,
            int lastZ
    ) {}

    private record FoundationRow(int minX, int maxX, int y, int z) {}

    private record MachineSpec(BlockPos pos, Direction facing) {}

    private record RunStats(
            int loaded,
            int scanning,
            int ready,
            int excavating,
            int storageFull,
            int complete
    ) {}
}
