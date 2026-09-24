package de.balto.laserexcavator.dev.stress.server;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import de.balto.laserexcavator.LaserExcavator;
import de.balto.laserexcavator.block.ModBlocks;
import de.balto.laserexcavator.block.blockentities.ExcavatorBlockEntity;
import de.balto.laserexcavator.block.excavator.ExcavatorBlock;
import de.balto.laserexcavator.block.excavator.ExcavatorScanState;
import de.balto.laserexcavator.item.ModItems;
import de.balto.laserexcavator.config.LaserExcavatorConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.items.ItemStackHandler;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;

/**
 * Development-only commands that build and maintain the 720-excavator benchmark layouts.
 * External AE2, Oritech and Mekanism content is resolved through registries/reflection so
 * the benchmark does not add hard compile-time dependencies on those mods.
 */
@EventBusSubscriber(modid = LaserExcavator.MODID)
public final class ExcavatorStressGridCommands {
    private static final int GRID_SIZE = 3;

    private static final int RECTANGLE_LENGTH = 320;
    private static final int RECTANGLE_GAP_PATH_WIDTH = 3;
    private static final int RECTANGLE_ROW_BAND_GAP = 64;

    private static final int ROW_COUNT = 4;
    private static final int ROW_WIDTH = 5;
    private static final int ROW_GAP = 64;
    private static final int RECTANGLE_WIDTH = ROW_COUNT * ROW_WIDTH + (ROW_COUNT - 1) * ROW_GAP;

    private static final int TOTAL_LENGTH =
            GRID_SIZE * RECTANGLE_LENGTH + (GRID_SIZE - 1) * RECTANGLE_GAP_PATH_WIDTH;
    private static final int TOTAL_WIDTH =
            GRID_SIZE * RECTANGLE_WIDTH + (GRID_SIZE - 1) * RECTANGLE_ROW_BAND_GAP;

    private static final int EXCAVATION_WIDTH = 32;
    private static final int EXCAVATION_HEIGHT = 256;
    private static final int EXCAVATION_LENGTH = 32;
    private static final int EXCAVATORS_PER_SIDE = RECTANGLE_LENGTH / EXCAVATION_WIDTH;
    private static final int EXCAVATORS_PER_ROW = EXCAVATORS_PER_SIDE * 2;
    private static final int ROW_NETWORK_COUNT = GRID_SIZE * GRID_SIZE * ROW_COUNT;
    private static final int TOTAL_EXCAVATORS = ROW_NETWORK_COUNT * EXCAVATORS_PER_ROW;

    private static final int STONE_BLOCKS_PER_TICK = 256;
    private static final int EXCAVATORS_PER_TICK = 8;
    // AE2 cable-bus block entities and Oritech network nodes are much heavier
    // than stone, so intentionally keep this conservative.
    private static final int NETWORK_PLACEMENTS_PER_TICK = 32;
    private static final int NETWORK_SETTLE_TICKS = 40;
    private static final int MACHINE_START_CHECKS_PER_TICK = 64;
    // Isolated benchmark support is staggered across one second so the helper
    // itself does not become a meaningful part of the stress-test workload.
    private static final int ISOLATED_MAINTENANCE_PERIOD_TICKS = 20;

    private static final String AE2_CABLE_BUS = "ae2:cable_bus";
    private static final String AE2_CONTROLLER = "ae2:controller";
    private static final String AE2_DRIVE = "ae2:drive";
    private static final String AE2_IMPORT_BUS = "ae2:import_bus";
    private static final String AE2_SPEED_CARD = "ae2:speed_card";
    private static final String AE2_256K_ITEM_CELL = "ae2:item_storage_cell_256k";
    private static final String AE2_CREATIVE_ENERGY_CELL = "ae2:creative_energy_cell";
    private static final String ORITECH_SUPERCONDUCTOR = "oritech:superconductor";
    private static final String MEKANISM_EXTRAS_INFINITE_ENERGY_CUBE = "mekanism_extras:infinite_energy_cube";

    private static final String[] AE2_COLORS = {
            "white", "orange", "magenta", "light_blue",
            "yellow", "lime", "pink", "gray",
            "light_gray", "cyan", "purple", "blue",
            "brown", "green", "red", "black"
    };

    private static final Map<String, Block> BLOCK_CACHE = new HashMap<>();
    private static final Map<String, Item> ITEM_CACHE = new HashMap<>();
    private static final Map<MethodKey, Method> METHOD_CACHE = new HashMap<>();
    private static final Map<Class<?>, Method> ORITECH_ADD_CONNECTION_METHOD_CACHE = new HashMap<>();

    private static BuildTask activeBuild;
    private static StressRun activeRun;

    private ExcavatorStressGridCommands() {}

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("excavatorstressgrid")
                        .requires(source -> LaserExcavatorConfig.stressTestCommandsEnabled() && source.hasPermission(2))
                        .executes(context -> start(context.getSource()))
                        .then(Commands.literal("status")
                                .executes(context -> status(context.getSource())))
                        .then(Commands.literal("cancel")
                                .executes(context -> cancel(context.getSource())))
                        .then(Commands.literal("power")
                                .then(Commands.literal("on")
                                        .executes(context -> setStressPower(context.getSource(), true)))
                                .then(Commands.literal("off")
                                        .executes(context -> setStressPower(context.getSource(), false))))
        );

        event.getDispatcher().register(
                Commands.literal("excavatorstressgridisolated")
                        .requires(source -> LaserExcavatorConfig.stressTestCommandsEnabled() && source.hasPermission(2))
                        .executes(context -> startIsolated(context.getSource()))
                        .then(Commands.literal("status")
                                .executes(context -> status(context.getSource())))
                        .then(Commands.literal("cancel")
                                .executes(context -> cancel(context.getSource())))
        );
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        if (activeBuild != null && activeBuild.level.getServer() == event.getServer()) {
            activeBuild = null;
        }
        if (activeRun != null && activeRun.level.getServer() == event.getServer()) {
            activeRun = null;
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (activeBuild == null && activeRun == null) return;
        if (!LaserExcavatorConfig.stressTestCommandsEnabled()) {
            if (activeRun != null) activeRun.disableIsolatedResourceBypass();
            activeBuild = null;
            activeRun = null;
            return;
        }

        MinecraftServer server = event.getServer();

        BuildTask build = activeBuild;
        if (build != null) {
            if (build.level.getServer() != server) {
                activeBuild = null;
            } else {
                build.tick();
                if (build.isDone()) {
                    activeBuild = null;
                    build.run.activate();
                    ServerPlayer player = server.getPlayerList().getPlayer(build.owner);
                    if (player != null) {
                        String completion = "Excavator stress grid finished: "
                                + build.changedBlocks + " stone blocks changed, "
                                + build.placedExcavators + " excavators, "
                                + build.completedNetworkPlacements + " network placements";
                        if (build.run.mode == StressMode.ISOLATED) {
                            completion += ". Isolated benchmark bypass is active: FE consumption, storage-capacity checks, and real item deliveries are disabled while transport visuals remain enabled.";
                        } else {
                            completion += build.networkFailures == 0
                                    ? ". Machines are scanning/starting; use /excavatorstressgrid power on to energize all ten isolated Oritech lines."
                                    : ", " + build.networkFailures + " network placement failures. Check latest.log.";
                        }
                        player.sendSystemMessage(Component.literal(completion));
                    }
                }
            }
        }

        StressRun run = activeRun;
        if (run != null) {
            if (run.level.getServer() != server) {
                activeRun = null;
            } else {
                run.tick();
            }
        }
    }

    private static int start(CommandSourceStack source) throws CommandSyntaxException {
        if (activeBuild != null) {
            source.sendFailure(Component.literal(
                    "An excavator stress-grid build is already running. Use /excavatorstressgrid status or cancel."
            ));
            return 0;
        }
        if (activeRun != null) {
            source.sendFailure(Component.literal(
                    "A generated excavator stress run is still active. Use /excavatorstressgrid cancel first."
            ));
            return 0;
        }

        List<String> missing = validateExternalContent();
        if (!missing.isEmpty()) {
            source.sendFailure(Component.literal(
                    "Cannot build simplified AE2/Oritech stress grid. Missing registry entries: " + String.join(", ", missing)
            ));
            return 0;
        }

        ServerPlayer player = source.getPlayerOrException();
        ServerLevel level = source.getLevel();
        BlockPos stoneCenter = player.blockPosition().below();

        int minX = stoneCenter.getX() - TOTAL_LENGTH / 2;
        int minZ = stoneCenter.getZ() - TOTAL_WIDTH / 2;
        int stoneY = stoneCenter.getY();
        int excavatorY = stoneY + 1;

        List<FillSpan> spans = createStoneSpans(minX, minZ, stoneY);
        List<MachineSpec> machines = createMachineSpecs(minX, minZ, excavatorY);
        NetworkPlan network = createNetworkPlan(minX, minZ, excavatorY);

        StressRun run = new StressRun(
                level,
                player.getUUID(),
                machines.size(),
                network.powerSourcePositions(),
                StressMode.NETWORKED
        );
        activeRun = run;
        activeBuild = new BuildTask(level, player.getUUID(), spans, machines, network, run);

        source.sendSuccess(
                () -> Component.literal(
                        "Started 3x3 excavator stress grid: footprint "
                                + TOTAL_LENGTH + " x " + TOTAL_WIDTH
                                + ", " + activeBuild.totalStoneTargets + " stone positions, "
                                + TOTAL_EXCAVATORS + " excavators, "
                                + ROW_NETWORK_COUNT + " coloured dense-cable branches routed north via separator paths / "
                                + TOTAL_EXCAVATORS + " max-speed import buses, "
                                + network.placements.size() + " incremental cable/power placements, "
                                + network.drives() + " ME Drives / " + network.storageCells() + " 256k cells. "
                                + "Oritech is split into " + network.powerNetworks() + " isolated lines (max "
                                + network.maxPowerNetworkNodes() + " conductor nodes/line). "
                                + "Use /excavatorstressgrid power on to energize all ten at once."
                ),
                true
        );
        return 1;
    }

    private static int startIsolated(CommandSourceStack source) throws CommandSyntaxException {
        if (activeBuild != null) {
            source.sendFailure(Component.literal(
                    "An excavator stress-grid build is already running. Use /excavatorstressgridisolated status or cancel."
            ));
            return 0;
        }
        if (activeRun != null) {
            source.sendFailure(Component.literal(
                    "A generated excavator stress run is still active. Use /excavatorstressgridisolated cancel first."
            ));
            return 0;
        }

        ServerPlayer player = source.getPlayerOrException();
        ServerLevel level = source.getLevel();
        BlockPos stoneCenter = player.blockPosition().below();

        int minX = stoneCenter.getX() - TOTAL_LENGTH / 2;
        int minZ = stoneCenter.getZ() - TOTAL_WIDTH / 2;
        int stoneY = stoneCenter.getY();
        int excavatorY = stoneY + 1;

        List<FillSpan> spans = createStoneSpans(minX, minZ, stoneY);
        List<MachineSpec> machines = createMachineSpecs(minX, minZ, excavatorY);
        NetworkPlan network = emptyNetworkPlan();

        StressRun run = new StressRun(
                level,
                player.getUUID(),
                machines.size(),
                network.powerSourcePositions(),
                StressMode.ISOLATED
        );
        activeRun = run;
        activeBuild = new BuildTask(level, player.getUUID(), spans, machines, network, run);

        source.sendSuccess(
                () -> Component.literal(
                        "Started ISOLATED 3x3 excavator stress grid: footprint "
                                + TOTAL_LENGTH + " x " + TOTAL_WIDTH
                                + ", " + activeBuild.totalStoneTargets + " stone positions, "
                                + TOTAL_EXCAVATORS + " excavators. "
                                + "No AE2, Oritech, Mekanism or conductor infrastructure is generated. "
                                + "The command uses a transient resource bypass: FE consumption, storage checks, and real item deliveries are disabled while visual transports remain active."
                ),
                true
        );
        return 1;
    }

    private static int status(CommandSourceStack source) {
        BuildTask build = activeBuild;
        StressRun run = activeRun;

        if (build == null && run == null) {
            source.sendSuccess(() -> Component.literal("No excavator stress-grid run is active."), false);
            return 1;
        }

        if (build != null) {
            double stonePercent = build.totalStoneTargets == 0
                    ? 100.0D
                    : build.processedStoneTargets * 100.0D / build.totalStoneTargets;
            double networkPercent = build.network.placements.isEmpty()
                    ? 100.0D
                    : build.networkIndex * 100.0D / build.network.placements.size();

            source.sendSuccess(
                    () -> Component.literal(String.format(
                            "Build: stone %.1f%% (%,d / %,d), excavators %,d / %,d, network %.1f%% (%,d / %,d), failures %,d, settle %d ticks.",
                            stonePercent,
                            build.processedStoneTargets,
                            build.totalStoneTargets,
                            build.placedExcavators,
                            build.machines.size(),
                            networkPercent,
                            build.networkIndex,
                            build.network.placements.size(),
                            build.networkFailures,
                            build.settleTicksRemaining
                    )),
                    false
            );
        }

        if (run != null) {
            RunStats stats = run.collectStats();
            source.sendSuccess(
                    () -> Component.literal(
                            "Machines: managed=" + stats.managed
                                    + ", loaded=" + stats.loaded
                                    + ", scanning=" + stats.scanning
                                    + ", ready=" + stats.ready
                                    + ", excavating=" + stats.excavating
                                    + ", storage-full=" + stats.storageFull
                                    + ", complete=" + stats.complete
                                    + ", starter-pending=" + run.pendingStartupCount()
                                    + ", mode=" + (run.mode == StressMode.ISOLATED ? "ISOLATED-AUTO" : "NETWORKED")
                                    + (run.mode == StressMode.ISOLATED
                                    ? ", resource-bypass=ON"
                                    : ", stress-power=" + (run.powerEnabled ? "ON" : "OFF"))
                                    + "."
                    ),
                    false
            );
        }
        return 1;
    }

    private static int cancel(CommandSourceStack source) {
        BuildTask build = activeBuild;
        StressRun run = activeRun;
        if (build == null && run == null) {
            source.sendSuccess(() -> Component.literal("No excavator stress-grid run is active."), false);
            return 1;
        }

        if (run != null) {
            run.disableIsolatedResourceBypass();
        }
        activeBuild = null;
        activeRun = null;

        source.sendSuccess(
                () -> Component.literal(
                        "Stopped excavator stress-grid generation/start support. Existing blocks and networks were left in place."
                ),
                true
        );
        return 1;
    }

    private static int setStressPower(CommandSourceStack source, boolean enabled) {
        if (activeBuild != null) {
            source.sendFailure(Component.literal(
                    "Stress-grid construction is still running. Wait for it to finish before toggling power."
            ));
            return 0;
        }

        StressRun run = activeRun;
        if (run == null) {
            source.sendFailure(Component.literal("No excavator stress-grid run is active."));
            return 0;
        }
        if (run.level != source.getLevel()) {
            source.sendFailure(Component.literal("The active stress grid is in another dimension."));
            return 0;
        }
        if (run.mode == StressMode.ISOLATED) {
            source.sendFailure(Component.literal(
                    "The isolated stress grid bypasses FE consumption and has no external power network."
            ));
            return 0;
        }

        Block energyCube = findBlock(MEKANISM_EXTRAS_INFINITE_ENERGY_CUBE);
        if (energyCube == null) {
            source.sendFailure(Component.literal(
                    "Missing Mekanism Extras Infinite Energy Cube: " + MEKANISM_EXTRAS_INFINITE_ENERGY_CUBE
            ));
            return 0;
        }

        int changed = 0;
        for (BlockPos pos : run.powerSourcePositions) {
            if (!run.level.hasChunkAt(pos)) continue;

            if (enabled) {
                BlockState current = run.level.getBlockState(pos);
                if (current.isAir()) {
                    BlockState cubeState = energyCube.defaultBlockState();
                    if (cubeState.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
                        cubeState = cubeState.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST);
                    } else if (cubeState.hasProperty(BlockStateProperties.FACING)) {
                        cubeState = cubeState.setValue(BlockStateProperties.FACING, Direction.EAST);
                    }

                    if (run.level.setBlock(pos, cubeState, 3)) {
                        changed++;
                        fillMekanismInfiniteEnergyCube(run.level, pos);

                        BlockPos terminalPos = pos.east();
                        BlockState terminalState = run.level.getBlockState(terminalPos);
                        run.level.updateNeighborsAt(pos, energyCube);
                        run.level.updateNeighborsAt(terminalPos, terminalState.getBlock());
                    }
                } else if (current.getBlock() != energyCube) {
                    source.sendFailure(Component.literal(
                            "Power socket is occupied at " + pos.toShortString() + "; not replacing "
                                    + BuiltInRegistries.BLOCK.getKey(current.getBlock())
                    ));
                }
            } else if (run.level.getBlockState(pos).getBlock() == energyCube) {
                if (run.level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3)) {
                    changed++;
                    BlockPos terminalPos = pos.east();
                    BlockState terminalState = run.level.getBlockState(terminalPos);
                    run.level.updateNeighborsAt(pos, Blocks.AIR);
                    run.level.updateNeighborsAt(terminalPos, terminalState.getBlock());
                }
            }
        }

        run.powerEnabled = enabled;
        int socketCount = run.powerSourcePositions.size();
        int finalChanged = changed;
        source.sendSuccess(
                () -> Component.literal(
                        (enabled ? "Enabled" : "Disabled")
                                + " stress-grid power (Mekanism Extras Infinite Energy Cubes -> Oritech): "
                                + finalChanged + " / " + socketCount + " line sources changed."
                ),
                true
        );
        return 1;
    }

    private static void fillMekanismInfiniteEnergyCube(ServerLevel level, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null) {
            LaserExcavator.LOGGER.warn("Mekanism Extras Infinite Energy Cube at {} has no block entity", pos);
            return;
        }

        try {
            Method getEnergyContainer = blockEntity.getClass().getMethod("getEnergyContainer");
            Object container = getEnergyContainer.invoke(blockEntity);
            if (container == null) return;

            Class<?> energyContainerApi = Class.forName("mekanism.api.energy.IEnergyContainer");
            Method getMaxEnergy = energyContainerApi.getMethod("getMaxEnergy");
            Method setEnergy = energyContainerApi.getMethod("setEnergy", long.class);
            long maxEnergy = ((Number) getMaxEnergy.invoke(container)).longValue();
            setEnergy.invoke(container, maxEnergy);

            blockEntity.setChanged();
            BlockState state = level.getBlockState(pos);
            level.sendBlockUpdated(pos, state, state, 3);
        } catch (ReflectiveOperationException exception) {
            LaserExcavator.LOGGER.warn("Could not fill Mekanism Extras Infinite Energy Cube at {}", pos, exception);
        }
    }

    private static List<String> validateExternalContent() {
        ArrayList<String> missing = new ArrayList<>();

        requireBlockForValidation(AE2_CABLE_BUS, missing);
        requireItemForValidation(AE2_IMPORT_BUS, missing);
        requireItemForValidation(AE2_SPEED_CARD, missing);
        requireBlockForValidation(AE2_CONTROLLER, missing);
        requireBlockForValidation(AE2_DRIVE, missing);
        requireItemForValidation(AE2_256K_ITEM_CELL, missing);
        requireBlockForValidation(AE2_CREATIVE_ENERGY_CELL, missing);
        requireBlockForValidation(ORITECH_SUPERCONDUCTOR, missing);
        requireBlockForValidation(MEKANISM_EXTRAS_INFINITE_ENERGY_CUBE, missing);

        for (String color : AE2_COLORS) {
            requireItemForValidation(aeSmartCableId(color), missing);
            requireItemForValidation(aeDenseCableId(color), missing);
        }
        return missing;
    }

    private static void requireBlockForValidation(String id, List<String> missing) {
        if (findBlock(id) == null) missing.add(id);
    }

    private static void requireItemForValidation(String id, List<String> missing) {
        if (findItem(id) == null) missing.add(id);
    }

    private static List<FillSpan> createStoneSpans(int minX, int minZ, int y) {
        ArrayList<FillSpan> spans = new ArrayList<>(44);

        for (int gridZ = 0; gridZ < GRID_SIZE; gridZ++) {
            int rectangleBaseZ = minZ + gridZ * (RECTANGLE_WIDTH + RECTANGLE_ROW_BAND_GAP);

            for (int gridX = 0; gridX < GRID_SIZE; gridX++) {
                int rectangleBaseX = minX + gridX * (RECTANGLE_LENGTH + RECTANGLE_GAP_PATH_WIDTH);

                for (int row = 0; row < ROW_COUNT; row++) {
                    int rowMinZ = rectangleBaseZ + row * (ROW_WIDTH + ROW_GAP);
                    spans.add(new FillSpan(
                            rectangleBaseX,
                            rectangleBaseX + RECTANGLE_LENGTH - 1,
                            y,
                            rowMinZ,
                            rowMinZ + ROW_WIDTH - 1
                    ));
                }
            }
        }

        for (int separator = 0; separator < GRID_SIZE - 1; separator++) {
            int pathMinX = minX
                    + (separator + 1) * RECTANGLE_LENGTH
                    + separator * RECTANGLE_GAP_PATH_WIDTH;
            spans.add(new FillSpan(
                    pathMinX,
                    pathMinX + RECTANGLE_GAP_PATH_WIDTH - 1,
                    y,
                    minZ,
                    minZ + TOTAL_WIDTH - 1
            ));
        }

        return spans;
    }

    private static List<MachineSpec> createMachineSpecs(int minX, int minZ, int y) {
        ArrayList<MachineSpec> machines = new ArrayList<>(TOTAL_EXCAVATORS);

        for (int gridZ = 0; gridZ < GRID_SIZE; gridZ++) {
            int rectangleBaseZ = minZ + gridZ * (RECTANGLE_WIDTH + RECTANGLE_ROW_BAND_GAP);

            for (int gridX = 0; gridX < GRID_SIZE; gridX++) {
                int rectangleBaseX = minX + gridX * (RECTANGLE_LENGTH + RECTANGLE_GAP_PATH_WIDTH);

                for (int row = 0; row < ROW_COUNT; row++) {
                    int rowMinZ = rectangleBaseZ + row * (ROW_WIDTH + ROW_GAP);
                    int northEdgeZ = rowMinZ;
                    int southEdgeZ = rowMinZ + ROW_WIDTH - 1;

                    for (int strip = 0; strip < EXCAVATORS_PER_SIDE; strip++) {
                        int machineX = machineX(rectangleBaseX, strip);

                        machines.add(new MachineSpec(
                                new BlockPos(machineX, y, northEdgeZ),
                                Direction.NORTH
                        ));
                        machines.add(new MachineSpec(
                                new BlockPos(machineX, y, southEdgeZ),
                                Direction.SOUTH
                        ));
                    }
                }
            }
        }

        if (machines.size() != TOTAL_EXCAVATORS) {
            throw new IllegalStateException(
                    "Expected " + TOTAL_EXCAVATORS + " stress excavators, got " + machines.size()
            );
        }
        return machines;
    }

    private static int machineX(int rectangleBaseX, int strip) {
        // Centers each 32-block-wide excavation strip on its machine position.
        return rectangleBaseX + EXCAVATION_WIDTH / 2 + strip * EXCAVATION_WIDTH;
    }

    private static NetworkPlan emptyNetworkPlan() {
        return new NetworkPlan(
                List.of(),
                0,
                0,
                0,
                0,
                0,
                List.of(),
                0,
                0,
                List.of()
        );
    }

    private static NetworkPlan createNetworkPlan(int minX, int minZ, int excavatorY) {
        PlanBuilder builder = new PlanBuilder();
        final int powerLineCount = 10;
        ArrayList<LinkedHashSet<BlockPos>> powerLines = new ArrayList<>(powerLineCount);
        for (int i = 0; i < powerLineCount; i++) powerLines.add(new LinkedHashSet<>());
        ArrayList<BlockPos> powerSourcePositions = new ArrayList<>(powerLineCount);
        int powerY = excavatorY + 1;

        /*
         * ME system safely NORTH of the excavation field
         * ---------------------------
         * A solid controller plate would violate AE2's controller multiblock
         * rules. Use the perimeter of a 7 x 7 square instead: 24 connected
         * controller blocks, one block tall. This is within the 7 x 7 x 7
         * limit and no controller block has two neighbours on more than one
         * axis.
         *
         * The 36 independent 20-channel row branches are kept separate all the
         * way to the controller. Each branch terminates on one unique top or
         * outward controller face, so every row has a full 32-channel feed.
         */
        int centerX = minX + TOTAL_LENGTH / 2;
        int controllerY = excavatorY + 1;
        int controllerMaxZ = minZ - 48;
        int controllerMinZ = controllerMaxZ - 6;
        int controllerMinX = centerX - 3;
        int controllerMaxX = centerX + 3;

        for (int x = controllerMinX; x <= controllerMaxX; x++) {
            for (int z = controllerMinZ; z <= controllerMaxZ; z++) {
                if (x == controllerMinX || x == controllerMaxX
                        || z == controllerMinZ || z == controllerMaxZ) {
                    builder.addBlock(new BlockPos(x, controllerY, z), AE2_CONTROLLER);
                }
            }
        }

        for (int x = controllerMinX; x <= controllerMaxX; x++) {
            builder.addDrive(new BlockPos(x, controllerY - 1, controllerMinZ));
        }

        BlockPos aePowerCell = new BlockPos(centerX, controllerY - 1, controllerMaxZ);
        builder.addBlock(aePowerCell, AE2_CREATIVE_ENERGY_CELL);

        int[] separatorTrackCounts = new int[GRID_SIZE - 1];
        ArrayList<RowNetworkSpec> rows = new ArrayList<>(ROW_NETWORK_COUNT);
        int globalBranchIndex = 0;

        for (int gridZ = 0; gridZ < GRID_SIZE; gridZ++) {
            int rectangleBaseZ = minZ + gridZ * (RECTANGLE_WIDTH + RECTANGLE_ROW_BAND_GAP);

            for (int gridX = 0; gridX < GRID_SIZE; gridX++) {
                int rectangleBaseX = minX + gridX * (RECTANGLE_LENGTH + RECTANGLE_GAP_PATH_WIDTH);

                for (int row = 0; row < ROW_COUNT; row++) {
                    int branchIndex = globalBranchIndex++;
                    int rowMinZ = rectangleBaseZ + row * (ROW_WIDTH + ROW_GAP);
                    int rowCenterZ = rowMinZ + ROW_WIDTH / 2;
                    int aeLeadZ = rowMinZ + 1;
                    int columnRowIndex = gridZ * ROW_COUNT + row;
                    int lineIndex = powerLineIndex(gridX, gridZ, row);
                    Set<BlockPos> powerLine = powerLines.get(lineIndex);

                    int separatorIndex;
                    if (gridX == 0) {
                        separatorIndex = 0;
                    } else if (gridX == GRID_SIZE - 1) {
                        separatorIndex = GRID_SIZE - 2;
                    } else {
                        separatorIndex = columnRowIndex < (GRID_SIZE * ROW_COUNT) / 2 ? 0 : 1;
                    }

                    int trackIndex = separatorTrackCounts[separatorIndex]++;
                    int lane = trackIndex % RECTANGLE_GAP_PATH_WIDTH;
                    int aeRouteY = controllerY + 4 + branchIndex;

                    int separatorStartX = minX
                            + (separatorIndex + 1) * RECTANGLE_LENGTH
                            + separatorIndex * RECTANGLE_GAP_PATH_WIDTH;
                    int separatorLaneX = separatorStartX + lane;

                    int rectangleMinX = rectangleBaseX;
                    int rectangleMaxX = rectangleBaseX + RECTANGLE_LENGTH - 1;
                    boolean separatorIsEast = separatorLaneX > rectangleMaxX;

                    // Keep the vertical AE2 riser two blocks inside the rectangle.
                    // The Oritech snake connectors run on the outer east/west
                    // edges at powerY, so this prevents block-plan collisions.
                    int riserX = separatorIsEast ? rectangleMaxX - 2 : rectangleMinX + 2;

                    int colorIndex = branchIndex % AE2_COLORS.length;
                    String color = AE2_COLORS[colorIndex];
                    String denseId = aeDenseCableId(color);
                    String smartId = aeSmartCableId(color);

                    for (int x = rectangleBaseX; x < rectangleBaseX + RECTANGLE_LENGTH; x++) {
                        builder.addAeCable(new BlockPos(x, excavatorY, rowCenterZ), denseId);
                    }

                    addAeCableLineZ(builder, riserX, excavatorY, rowCenterZ, aeLeadZ, denseId);
                    addAeCableLineY(builder, riserX, aeLeadZ, excavatorY, aeRouteY, denseId);
                    addAeCableLineX(builder, riserX, separatorLaneX, aeRouteY, aeLeadZ, denseId);
                    addAeCableLineZ(builder, separatorLaneX, aeRouteY, minZ, aeLeadZ, denseId);

                    rows.add(new RowNetworkSpec(
                            branchIndex,
                            gridX,
                            gridZ,
                            row,
                            rectangleBaseX,
                            rowMinZ,
                            colorIndex,
                            new BlockPos(separatorLaneX, aeRouteY, minZ),
                            null,
                            aeRouteY
                    ));

                    for (int strip = 0; strip < EXCAVATORS_PER_SIDE; strip++) {
                        int x = machineX(rectangleBaseX, strip);

                        builder.addAeImportTap(
                                new BlockPos(x, excavatorY, rowMinZ + 1),
                                smartId,
                                Direction.NORTH
                        );
                        builder.addAeImportTap(
                                new BlockPos(x, excavatorY, rowMinZ + ROW_WIDTH - 2),
                                smartId,
                                Direction.SOUTH
                        );

                        int northMachineZ = rowMinZ;
                        int southMachineZ = rowMinZ + ROW_WIDTH - 1;
                        int northLeftX = x - 1;
                        int southLeftX = x + 1;

                        addBlockLineZ(
                                builder,
                                powerLine,
                                northLeftX,
                                powerY,
                                rowCenterZ,
                                northMachineZ,
                                ORITECH_SUPERCONDUCTOR
                        );
                        addPower(builder, powerLine, new BlockPos(northLeftX, excavatorY, northMachineZ));

                        addBlockLineZ(
                                builder,
                                powerLine,
                                southLeftX,
                                powerY,
                                rowCenterZ,
                                southMachineZ,
                                ORITECH_SUPERCONDUCTOR
                        );
                        addPower(builder, powerLine, new BlockPos(southLeftX, excavatorY, southMachineZ));
                    }
                }
            }
        }

        if (separatorTrackCounts[0] != 18 || separatorTrackCounts[1] != 18) {
            throw new IllegalStateException(
                    "Expected 18 AE2 branches per separator path, got "
                            + separatorTrackCounts[0] + " and " + separatorTrackCounts[1]
            );
        }
        if (rows.size() != ROW_NETWORK_COUNT) {
            throw new IllegalStateException(
                    "Expected " + ROW_NETWORK_COUNT + " AE2 row branches, got " + rows.size()
            );
        }

        /*
         * Ten isolated Oritech lines. Eight rectangles use one four-row snake
         * each. The center rectangle is split into two independent two-row
         * snakes, producing ten total networks. Alternating east/west row joins
         * make each backbone a single continuous line rather than a rail grid.
         */
        for (int gridZ = 0; gridZ < GRID_SIZE; gridZ++) {
            int rectangleBaseZ = minZ + gridZ * (RECTANGLE_WIDTH + RECTANGLE_ROW_BAND_GAP);
            for (int gridX = 0; gridX < GRID_SIZE; gridX++) {
                int rectangleBaseX = minX + gridX * (RECTANGLE_LENGTH + RECTANGLE_GAP_PATH_WIDTH);
                int rectangleIndex = gridZ * GRID_SIZE + gridX;

                if (rectangleIndex == 4) {
                    addPowerSnakeLine(builder, powerLines.get(4), powerSourcePositions, rectangleBaseX, rectangleBaseZ, powerY, 0, 2);
                    addPowerSnakeLine(builder, powerLines.get(5), powerSourcePositions, rectangleBaseX, rectangleBaseZ, powerY, 2, 4);
                } else {
                    int lineIndex = rectangleIndex < 4 ? rectangleIndex : rectangleIndex + 1;
                    addPowerSnakeLine(builder, powerLines.get(lineIndex), powerSourcePositions, rectangleBaseX, rectangleBaseZ, powerY, 0, 4);
                }
            }
        }

        int maxPowerNetworkNodes = validatePowerNetworkIsolation(powerLines);

        List<BlockPos> targetCandidates = createControllerTargetCandidates(
                controllerMinX,
                controllerMaxX,
                controllerMinZ,
                controllerMaxZ,
                controllerY
        );
        boolean[] targetUsed = new boolean[targetCandidates.size()];
        ArrayList<BlockPos> assignedTargets = new ArrayList<>(ROW_NETWORK_COUNT);

        for (int i = 0; i < rows.size(); i++) {
            RowNetworkSpec row = rows.get(i);
            BlockPos target = chooseControllerTarget(
                    row.colorIndex,
                    targetCandidates,
                    targetUsed,
                    assignedTargets,
                    rows,
                    aePowerCell
            );
            assignedTargets.add(target);
            rows.set(i, row.withTarget(target));
        }

        ArrayList<ReservedColumn> reservedColumns = new ArrayList<>(ROW_NETWORK_COUNT);
        for (RowNetworkSpec row : rows) {
            reservedColumns.add(new ReservedColumn(
                    row.target.getX(),
                    row.target.getZ(),
                    row.colorIndex,
                    row.routeY
            ));
        }

        for (RowNetworkSpec row : rows) {
            String denseId = aeDenseCableId(AE2_COLORS[row.colorIndex]);
            addAeCableLineY(
                    builder,
                    row.target.getX(),
                    row.target.getZ(),
                    row.target.getY(),
                    row.routeY,
                    denseId
            );

            List<BlockPos> horizontalPath = findHorizontalRoute(
                    row,
                    reservedColumns,
                    minX - 48,
                    minX + TOTAL_LENGTH - 1 + 48,
                    controllerMinZ - 48,
                    minZ
            );
            for (BlockPos pos : horizontalPath) {
                builder.addAeCable(pos, denseId);
            }
        }

        LinkedHashSet<BlockPos> allPowerPositions = new LinkedHashSet<>();
        for (Set<BlockPos> linePositions : powerLines) allPowerPositions.addAll(linePositions);

        return new NetworkPlan(
                builder.placements,
                ROW_NETWORK_COUNT,
                TOTAL_EXCAVATORS,
                allPowerPositions.size(),
                7,
                70,
                List.copyOf(allPowerPositions),
                powerLines.size(),
                maxPowerNetworkNodes,
                List.copyOf(powerSourcePositions)
        );
    }

    private static int powerLineIndex(int gridX, int gridZ, int row) {
        int rectangleIndex = gridZ * GRID_SIZE + gridX;
        if (rectangleIndex < 4) return rectangleIndex;
        if (rectangleIndex == 4) return row < 2 ? 4 : 5;
        return rectangleIndex + 1;
    }

    private static void addPowerSnakeLine(
            PlanBuilder builder,
            Set<BlockPos> powerLine,
            List<BlockPos> powerSourcePositions,
            int rectangleBaseX,
            int rectangleBaseZ,
            int powerY,
            int rowStartInclusive,
            int rowEndExclusive
    ) {
        if (rowStartInclusive >= rowEndExclusive) return;

        for (int row = rowStartInclusive; row < rowEndExclusive; row++) {
            int rowCenterZ = rectangleBaseZ + row * (ROW_WIDTH + ROW_GAP) + ROW_WIDTH / 2;
            addBlockLineX(
                    builder,
                    powerLine,
                    rectangleBaseX,
                    rectangleBaseX + RECTANGLE_LENGTH - 1,
                    powerY,
                    rowCenterZ,
                    ORITECH_SUPERCONDUCTOR
            );

            if (row + 1 < rowEndExclusive) {
                int nextRowCenterZ = rectangleBaseZ
                        + (row + 1) * (ROW_WIDTH + ROW_GAP)
                        + ROW_WIDTH / 2;
                boolean joinOnEast = ((row - rowStartInclusive) & 1) == 0;
                int joinX = joinOnEast
                        ? rectangleBaseX + RECTANGLE_LENGTH - 1
                        : rectangleBaseX;
                addBlockLineZ(
                        builder,
                        powerLine,
                        joinX,
                        powerY,
                        rowCenterZ,
                        nextRowCenterZ,
                        ORITECH_SUPERCONDUCTOR
                );
            }
        }

        int firstRowCenterZ = rectangleBaseZ
                + rowStartInclusive * (ROW_WIDTH + ROW_GAP)
                + ROW_WIDTH / 2;
        BlockPos terminal = new BlockPos(rectangleBaseX - 1, powerY, firstRowCenterZ);
        addPower(builder, powerLine, terminal);
        powerSourcePositions.add(new BlockPos(rectangleBaseX - 2, powerY, firstRowCenterZ));
    }

    private static int validatePowerNetworkIsolation(List<? extends Set<BlockPos>> networks) {
        HashMap<BlockPos, Integer> ownerByPos = new HashMap<>();
        int maxNodes = 0;

        for (int network = 0; network < networks.size(); network++) {
            Set<BlockPos> positions = networks.get(network);
            maxNodes = Math.max(maxNodes, positions.size());
            if (positions.size() >= 2048) {
                throw new IllegalStateException(
                        "Oritech power line " + network + " has " + positions.size()
                                + " conductor nodes; must stay below the 2048-node search limit."
                );
            }

            for (BlockPos pos : positions) {
                Integer previous = ownerByPos.putIfAbsent(pos, network);
                if (previous != null && previous != network) {
                    throw new IllegalStateException(
                            "Oritech power-line collision at " + pos + ": " + previous + " vs " + network
                    );
                }
            }
        }

        for (Map.Entry<BlockPos, Integer> entry : ownerByPos.entrySet()) {
            BlockPos pos = entry.getKey();
            int owner = entry.getValue();
            for (Direction direction : Direction.values()) {
                Integer neighbourOwner = ownerByPos.get(pos.relative(direction));
                if (neighbourOwner != null && neighbourOwner != owner) {
                    throw new IllegalStateException(
                            "Oritech power lines " + owner + " and " + neighbourOwner
                                    + " touch at " + pos + " / " + pos.relative(direction)
                    );
                }
            }
        }
        return maxNodes;
    }

    private static boolean isMachineX(int rectangleBaseX, int x) {
        int relative = x - (rectangleBaseX + EXCAVATION_WIDTH / 2);
        return relative >= 0 && relative % EXCAVATION_WIDTH == 0;
    }

    private static List<BlockPos> createControllerTargetCandidates(
            int minX,
            int maxX,
            int minZ,
            int maxZ,
            int y
    ) {
        ArrayList<BlockPos> candidates = new ArrayList<>(52);

        // First prefer top faces of all 24 perimeter controller blocks.
        for (int x = minX; x <= maxX; x++) {
            candidates.add(new BlockPos(x, y + 1, minZ));
            if (maxZ != minZ) candidates.add(new BlockPos(x, y + 1, maxZ));
        }
        for (int z = minZ + 1; z < maxZ; z++) {
            candidates.add(new BlockPos(minX, y + 1, z));
            candidates.add(new BlockPos(maxX, y + 1, z));
        }

        // Then outward horizontal faces. These provide more than enough unique
        // controller faces for all 36 dense row branches.
        for (int x = minX; x <= maxX; x++) {
            candidates.add(new BlockPos(x, y, minZ - 1));
            candidates.add(new BlockPos(x, y, maxZ + 1));
        }
        for (int z = minZ; z <= maxZ; z++) {
            candidates.add(new BlockPos(minX - 1, y, z));
            candidates.add(new BlockPos(maxX + 1, y, z));
        }
        return candidates;
    }

    private static BlockPos chooseControllerTarget(
            int colorIndex,
            List<BlockPos> candidates,
            boolean[] used,
            List<BlockPos> assigned,
            List<RowNetworkSpec> rows,
            BlockPos controllerPowerContact
    ) {
        for (int i = 0; i < candidates.size(); i++) {
            if (used[i]) continue;
            BlockPos candidate = candidates.get(i);
            if (candidate.equals(controllerPowerContact)) continue;

            boolean safe = true;
            for (int previous = 0; previous < assigned.size(); previous++) {
                if (rows.get(previous).colorIndex != colorIndex) continue;
                BlockPos other = assigned.get(previous);
                if (manhattanXZ(candidate.getX(), candidate.getZ(), other.getX(), other.getZ()) <= 1) {
                    safe = false;
                    break;
                }
            }

            if (safe) {
                used[i] = true;
                return candidate;
            }
        }
        throw new IllegalStateException("Could not assign isolated controller face for AE2 colour index " + colorIndex);
    }

    private static List<BlockPos> findHorizontalRoute(
            RowNetworkSpec row,
            List<ReservedColumn> reservedColumns,
            int minX,
            int maxX,
            int minZ,
            int maxZ
    ) {
        int sx = row.source.getX();
        int sz = row.source.getZ();
        int tx = row.target.getX();
        int tz = row.target.getZ();

        long startKey = xzKey(sx, sz);
        long targetKey = xzKey(tx, tz);

        HashSet<Long> blocked = new HashSet<>(reservedColumns.size() * 6);
        for (ReservedColumn reserved : reservedColumns) {
            // Horizontal routing happens at row.routeY. Only reserve another
            // riser's column when that riser physically reaches this height.
            // Reserve another riser's column only while that riser intersects the
            // current routing height, leaving open paths around the controller.
            if (reserved.topY < row.routeY) continue;

            boolean ownSource = reserved.x == sx && reserved.z == sz;
            boolean ownTarget = reserved.x == tx && reserved.z == tz;
            if (ownSource || ownTarget) continue;

            blocked.add(xzKey(reserved.x, reserved.z));
            if (reserved.colorIndex == row.colorIndex) {
                blocked.add(xzKey(reserved.x + 1, reserved.z));
                blocked.add(xzKey(reserved.x - 1, reserved.z));
                blocked.add(xzKey(reserved.x, reserved.z + 1));
                blocked.add(xzKey(reserved.x, reserved.z - 1));
            }
        }
        blocked.remove(startKey);
        blocked.remove(targetKey);

        // Routing needs a valid physical path rather than the shortest path. Greedy
        // best-first ordering favors nodes closest to the target while retaining
        // alternatives in the queue.
        PriorityQueue<PathNode> open = new PriorityQueue<>(Comparator
                .comparingInt(PathNode::h)
                .thenComparingInt(PathNode::g));
        HashMap<Long, Long> parent = new HashMap<>();
        HashSet<Long> discovered = new HashSet<>();
        HashSet<Long> closed = new HashSet<>();

        int initialH = Math.abs(sx - tx) + Math.abs(sz - tz);
        open.add(new PathNode(sx, sz, 0, initialH));
        discovered.add(startKey);

        final int[] dx = {1, -1, 0, 0};
        final int[] dz = {0, 0, 1, -1};

        while (!open.isEmpty()) {
            PathNode current = open.poll();
            long currentKey = xzKey(current.x, current.z);
            if (!closed.add(currentKey)) continue;

            if (currentKey == targetKey) {
                ArrayList<BlockPos> reversed = new ArrayList<>();
                long key = targetKey;
                while (true) {
                    reversed.add(new BlockPos(xFromKey(key), row.routeY, zFromKey(key)));
                    if (key == startKey) break;
                    Long prev = parent.get(key);
                    if (prev == null) {
                        throw new IllegalStateException("Broken AE2 path reconstruction for branch " + row.index);
                    }
                    key = prev;
                }

                ArrayList<BlockPos> result = new ArrayList<>(reversed.size());
                for (int i = reversed.size() - 1; i >= 0; i--) result.add(reversed.get(i));
                return result;
            }

            for (int d = 0; d < 4; d++) {
                int nx = current.x + dx[d];
                int nz = current.z + dz[d];
                if (nx < minX || nx > maxX || nz < minZ || nz > maxZ) continue;

                long nextKey = xzKey(nx, nz);
                if (blocked.contains(nextKey) || closed.contains(nextKey)) continue;

                if (!discovered.add(nextKey)) continue;

                int nextG = current.g + 1;
                parent.put(nextKey, currentKey);
                int h = Math.abs(nx - tx) + Math.abs(nz - tz);
                open.add(new PathNode(nx, nz, nextG, h));
            }
        }

        throw new IllegalStateException("Could not route coloured AE2 dense branch " + row.index);
    }

    private static long xzKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    private static int xFromKey(long key) {
        return (int) (key >> 32);
    }

    private static int zFromKey(long key) {
        return (int) key;
    }

    private static int manhattanXZ(int ax, int az, int bx, int bz) {
        return Math.abs(ax - bx) + Math.abs(az - bz);
    }

    private static String aeSmartCableId(String color) {
        return "ae2:" + color + "_smart_cable";
    }

    private static String aeDenseCableId(String color) {
        return "ae2:" + color + "_smart_dense_cable";
    }

    private static void addAeCableLineX(
            PlanBuilder builder,
            int minX,
            int maxX,
            int y,
            int z,
            String cableId
    ) {
        int from = Math.min(minX, maxX);
        int to = Math.max(minX, maxX);
        for (int x = from; x <= to; x++) {
            builder.addAeCable(new BlockPos(x, y, z), cableId);
        }
    }

    private static void addAeCableLineZ(
            PlanBuilder builder,
            int x,
            int y,
            int minZ,
            int maxZ,
            String cableId
    ) {
        int from = Math.min(minZ, maxZ);
        int to = Math.max(minZ, maxZ);
        for (int z = from; z <= to; z++) {
            builder.addAeCable(new BlockPos(x, y, z), cableId);
        }
    }

    private static void addAeCableLineY(
            PlanBuilder builder,
            int x,
            int z,
            int minY,
            int maxY,
            String cableId
    ) {
        int from = Math.min(minY, maxY);
        int to = Math.max(minY, maxY);
        for (int y = from; y <= to; y++) {
            builder.addAeCable(new BlockPos(x, y, z), cableId);
        }
    }

    private static void addPower(PlanBuilder builder, Set<BlockPos> positions, BlockPos pos) {
        BlockPos immutable = pos.immutable();
        if (positions.add(immutable)) {
            builder.addBlock(immutable, ORITECH_SUPERCONDUCTOR);
        }
    }

    private static void addBlockLineX(
            PlanBuilder builder,
            Set<BlockPos> positions,
            int minX,
            int maxX,
            int y,
            int z,
            String blockId
    ) {
        int from = Math.min(minX, maxX);
        int to = Math.max(minX, maxX);
        for (int x = from; x <= to; x++) {
            BlockPos pos = new BlockPos(x, y, z);
            if (ORITECH_SUPERCONDUCTOR.equals(blockId)) {
                addPower(builder, positions, pos);
            } else if (positions.add(pos)) {
                builder.addBlock(pos, blockId);
            }
        }
    }

    private static void addBlockLineZ(
            PlanBuilder builder,
            Set<BlockPos> positions,
            int x,
            int y,
            int minZ,
            int maxZ,
            String blockId
    ) {
        int from = Math.min(minZ, maxZ);
        int to = Math.max(minZ, maxZ);
        for (int z = from; z <= to; z++) {
            BlockPos pos = new BlockPos(x, y, z);
            if (ORITECH_SUPERCONDUCTOR.equals(blockId)) {
                addPower(builder, positions, pos);
            } else if (positions.add(pos)) {
                builder.addBlock(pos, blockId);
            }
        }
    }

    private static final class BuildTask {
        private final ServerLevel level;
        private final UUID owner;
        private final List<FillSpan> spans;
        private final List<MachineSpec> machines;
        private final NetworkPlan network;
        private final StressRun run;
        private final BlockState stone = Blocks.STONE.defaultBlockState();
        private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        private final long totalStoneTargets;

        private int spanIndex;
        private int machineIndex;
        private int networkIndex;
        private int powerRefreshIndex;
        private int settleTicksRemaining;
        private long processedStoneTargets;
        private long changedBlocks;
        private int placedExcavators;
        private int completedNetworkPlacements;
        private int networkFailures;
        private boolean finished;

        private BuildTask(
                ServerLevel level,
                UUID owner,
                List<FillSpan> spans,
                List<MachineSpec> machines,
                NetworkPlan network,
                StressRun run
        ) {
            this.level = level;
            this.owner = owner;
            this.spans = spans;
            this.machines = machines;
            this.network = network;
            this.run = run;
            this.settleTicksRemaining = run.mode == StressMode.ISOLATED ? 0 : NETWORK_SETTLE_TICKS;

            long total = 0L;
            for (FillSpan span : spans) total += span.size();
            this.totalStoneTargets = total;
        }

        private void tick() {
            if (spanIndex < spans.size()) {
                tickStone(STONE_BLOCKS_PER_TICK);
                return;
            }
            if (machineIndex < machines.size()) {
                tickMachines(EXCAVATORS_PER_TICK);
                return;
            }
            if (networkIndex < network.placements.size()) {
                tickNetwork(NETWORK_PLACEMENTS_PER_TICK);
                return;
            }
            if (powerRefreshIndex < network.superconductorPositions.size()) {
                tickPowerRefresh(NETWORK_PLACEMENTS_PER_TICK * 4);
                return;
            }
            if (settleTicksRemaining > 0) {
                settleTicksRemaining--;
                return;
            }
            finished = true;
        }

        private void tickStone(int budget) {
            while (budget > 0 && spanIndex < spans.size()) {
                FillSpan span = spans.get(spanIndex);
                while (budget > 0 && !span.isDone()) {
                    span.writeCurrent(cursor);
                    if (level.setBlock(cursor, stone, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE)) {
                        changedBlocks++;
                    }
                    processedStoneTargets++;
                    budget--;
                    span.advance();
                }

                if (span.isDone()) spanIndex++;
            }
        }

        private void tickMachines(int budget) {
            while (budget > 0 && machineIndex < machines.size()) {
                MachineSpec spec = machines.get(machineIndex++);
                if (placeAndConfigureExcavator(spec)) {
                    placedExcavators++;
                    run.add(spec.pos);
                }
                budget--;
            }
        }

        private void tickNetwork(int budget) {
            while (budget > 0 && networkIndex < network.placements.size()) {
                NetworkPlacement placement = network.placements.get(networkIndex++);
                try {
                    if (placeNetwork(placement)) {
                        completedNetworkPlacements++;
                    } else {
                        networkFailures++;
                        LaserExcavator.LOGGER.warn(
                                "Stress-grid network placement failed at {} ({}, {})",
                                placement.pos, placement.kind, placement.id
                        );
                    }
                } catch (Exception e) {
                    networkFailures++;
                    LaserExcavator.LOGGER.warn(
                            "Stress-grid network placement threw at {} ({}, {})",
                            placement.pos, placement.kind, placement.id, e
                    );
                }
                budget--;
            }
        }

        private void tickPowerRefresh(int budget) {
            while (budget > 0 && powerRefreshIndex < network.superconductorPositions.size()) {
                refreshSuperconductor(network.superconductorPositions.get(powerRefreshIndex++));
                budget--;
            }
        }

        private void refreshSuperconductor(BlockPos pos) {
            try {
                refreshOritechPipeState(pos);
            } catch (ReflectiveOperationException e) {
                networkFailures++;
                LaserExcavator.LOGGER.warn(
                        "Stress-grid Oritech superconductor refresh failed at {}",
                        pos,
                        e
                );
            }
        }

        private boolean placeAndConfigureExcavator(MachineSpec spec) {
            BlockState excavatorState = ModBlocks.EXCAVATOR.get()
                    .defaultBlockState()
                    .setValue(ExcavatorBlock.FACING, spec.facing);

            if (!level.setBlock(
                    spec.pos,
                    excavatorState,
                    Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE
            )) {
                return false;
            }

            if (!(level.getBlockEntity(spec.pos) instanceof ExcavatorBlockEntity excavator)) {
                return false;
            }

            ItemStackHandler upgrades = excavator.getUpgradeInventory();
            upgrades.setStackInSlot(0, new ItemStack(ModItems.SPEED_UPGRADE_TIER_5.get()));
            upgrades.setStackInSlot(1, new ItemStack(ModItems.ENERGY_EFFICIENCY_UPGRADE_TIER_5.get()));
            upgrades.setStackInSlot(2, new ItemStack(ModItems.AREA_UPGRADE_128.get()));

            excavator.setSelectionWidth(EXCAVATION_WIDTH);
            excavator.setSelectionHeight(EXCAVATION_HEIGHT);
            excavator.setSelectionLength(EXCAVATION_LENGTH);

            // Do not inject FE and do not clear output here. The generated
            // Oritech conductors intentionally have no power source, so after
            // scanning/starting the excavator must wait for externally supplied FE.
            return true;
        }

        private boolean placeNetwork(NetworkPlacement placement) throws ReflectiveOperationException {
            return switch (placement.kind) {
                case BLOCK -> placePlainBlock(placement.pos, placement.id);
                case AE_CABLE -> placeAeCable(placement.pos, placement.id);
                case AE_IMPORT_TAP -> placeAeImportTap(placement.pos, placement.id, placement.side);
                case AE_DRIVE -> placeAeDrive(placement.pos);
            };
        }

        private boolean placePlainBlock(BlockPos pos, String id) throws ReflectiveOperationException {
            Block block = findBlock(id);
            if (block == null) return false;

            if (ORITECH_SUPERCONDUCTOR.equals(id)) {
                /*
                 * Oritech pipes are NOT meant to be placed with plain
                 * defaultBlockState(). Normal player placement goes through
                 * AbstractPipeBlock#getStateForPlacement(), which calls
                 * addConnectionStates(..., true) before the block enters the
                 * world. Without that step two freshly generated neighboring
                 * pipes can both remain NO_CONNECTION forever.
                 */
                BlockState current = level.getBlockState(pos);
                if (!isOritechSuperconductorFamily(current.getBlock())) {
                    BlockState connectedState = calculateOritechConnectionState(
                            block,
                            block.defaultBlockState(),
                            pos,
                            true
                    );
                    if (!level.setBlock(pos, connectedState, Block.UPDATE_ALL)) return false;
                }

                // onPlace may transform a superconductor next to a machine into
                // Oritech's dedicated connection block. Refresh the resulting conductor.
                refreshOritechPipeState(pos);
                return true;
            }

            boolean alreadyPlaced = level.getBlockState(pos).is(block);
            return alreadyPlaced || level.setBlock(pos, block.defaultBlockState(), Block.UPDATE_ALL);
        }

        private void refreshOritechPipeState(BlockPos pos) throws ReflectiveOperationException {
            BlockState state = level.getBlockState(pos);
            Block actualBlock = state.getBlock();
            if (!isOritechSuperconductorFamily(actualBlock)) return;

            BlockState connectedState = calculateOritechConnectionState(
                    actualBlock,
                    state,
                    pos,
                    true
            );
            if (!connectedState.equals(state)) {
                level.setBlockAndUpdate(pos, connectedState);
            }

            // This is Oritech's own pipe-neighbour propagation method. It also
            // updates its internal pipe-network data when a neighbour changes.
            Method updateNeighbors = findMethod(actualBlock.getClass(), "updateNeighbors", 3);
            updateNeighbors.invoke(actualBlock, level, pos, false);
        }

        private BlockState calculateOritechConnectionState(
                Block block,
                BlockState state,
                BlockPos pos,
                boolean createConnections
        ) throws ReflectiveOperationException {
            Class<?> blockType = block.getClass();
            Method addConnections = ORITECH_ADD_CONNECTION_METHOD_CACHE.get(blockType);
            if (addConnections == null) {
                for (Method method : blockType.getMethods()) {
                    if (!method.getName().equals("addConnectionStates") || method.getParameterCount() != 4) continue;
                    Class<?>[] parameterTypes = method.getParameterTypes();
                    if (parameterTypes[3] == boolean.class || parameterTypes[3] == Boolean.TYPE) {
                        addConnections = method;
                        ORITECH_ADD_CONNECTION_METHOD_CACHE.put(blockType, method);
                        break;
                    }
                }
            }
            if (addConnections == null) {
                throw new NoSuchMethodException(blockType.getName() + ".addConnectionStates(..., boolean)");
            }

            Object result = addConnections.invoke(block, state, level, pos, createConnections);
            if (!(result instanceof BlockState connectedState)) {
                throw new IllegalStateException("Oritech addConnectionStates did not return BlockState");
            }
            return connectedState;
        }

        private boolean isOritechSuperconductorFamily(Block block) {
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
            if (id == null || !"oritech".equals(id.getNamespace())) return false;
            String path = id.getPath();
            return path.equals("superconductor")
                    || path.equals("superconductor_connection")
                    || path.equals("framed_superconductor")
                    || path.equals("framed_superconductor_connection");
        }

        private boolean placeAeCable(BlockPos pos, String cablePartId) throws ReflectiveOperationException {
            Object part = ensureAePart(pos, cablePartId, null);
            return part != null;
        }

        private boolean placeAeImportTap(
                BlockPos pos,
                String smartCablePartId,
                Direction importerSide
        ) throws ReflectiveOperationException {
            if (ensureAePart(pos, smartCablePartId, null) == null) return false;
            Object importer = ensureAePart(pos, AE2_IMPORT_BUS, importerSide);
            if (importer == null) return false;
            return installMaxAe2SpeedCards(importer);
        }

        private Object ensureAePart(
                BlockPos pos,
                String partItemId,
                Direction side
        ) throws ReflectiveOperationException {
            Block cableBus = findBlock(AE2_CABLE_BUS);
            Item partItem = findItem(partItemId);
            if (cableBus == null || partItem == null) return null;

            if (!level.getBlockState(pos).is(cableBus)) {
                if (!level.setBlock(pos, cableBus.defaultBlockState(), Block.UPDATE_ALL)) {
                    return null;
                }
            }

            BlockEntity host = level.getBlockEntity(pos);
            if (host == null) return null;

            Method getPart = findMethod(host.getClass(), "getPart", 1);
            Object existing = getPart.invoke(host, side);
            if (existing != null) return existing;

            Method addPart = findMethod(host.getClass(), "addPart", 3);
            return addPart.invoke(host, partItem, side, null);
        }

        private boolean installMaxAe2SpeedCards(Object importer) throws ReflectiveOperationException {
            Item speedCard = findItem(AE2_SPEED_CARD);
            if (speedCard == null) return false;

            // Do not reflect methods from AE2's concrete UpgradeInventory class.
            // That implementation is package-private, so Java's module access checks
            // reject Method.invoke even though its methods themselves are public.
            // Invoke through AE2's exported public API interfaces instead.
            Class<?> upgradeableObjectType = Class.forName("appeng.api.upgrades.IUpgradeableObject");
            if (!upgradeableObjectType.isInstance(importer)) return false;

            Method getUpgrades = findMethod(upgradeableObjectType, "getUpgrades", 0);
            Object upgrades = getUpgrades.invoke(importer);
            if (upgrades == null) return false;

            Class<?> upgradeInventoryType = Class.forName("appeng.api.upgrades.IUpgradeInventory");
            if (!upgradeInventoryType.isInstance(upgrades)) return false;

            Method getMaxInstalled = findMethod(upgradeInventoryType, "getMaxInstalled", 1);
            int max = ((Number) getMaxInstalled.invoke(upgrades, speedCard)).intValue();
            if (max <= 0) return true;

            Method getInstalled = findMethod(upgradeInventoryType, "getInstalledUpgrades", 1);
            int installed = ((Number) getInstalled.invoke(upgrades, speedCard)).intValue();
            int missing = max - installed;
            if (missing <= 0) return true;

            // addItems is inherited by IUpgradeInventory from AE2's public
            // InternalInventory API, and getMethods() also returns inherited methods.
            Method addItems = findMethod(upgradeInventoryType, "addItems", 1);
            Object overflow = addItems.invoke(upgrades, new ItemStack(speedCard, missing));
            return !(overflow instanceof ItemStack stack) || stack.isEmpty();
        }

        private boolean placeAeDrive(BlockPos pos) throws ReflectiveOperationException {
            Block drive = findBlock(AE2_DRIVE);
            Item cell = findItem(AE2_256K_ITEM_CELL);
            if (drive == null || cell == null) return false;

            if (!level.getBlockState(pos).is(drive)) {
                if (!level.setBlock(pos, drive.defaultBlockState(), Block.UPDATE_ALL)) return false;
            }

            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity == null) return false;

            Method getInternalInventory = findMethod(blockEntity.getClass(), "getInternalInventory", 0);
            Object inventory = getInternalInventory.invoke(blockEntity);
            if (inventory == null) return false;

            // Use AE2's exported public inventory interface for invocation.
            // This avoids the same module-access problem as UpgradeInventory if
            // the concrete inventory implementation is package-private.
            Class<?> internalInventoryType = Class.forName("appeng.api.inventories.InternalInventory");
            if (!internalInventoryType.isInstance(inventory)) return false;

            Method size = findMethod(internalInventoryType, "size", 0);
            int slots = ((Number) size.invoke(inventory)).intValue();
            Method getStack = findMethod(internalInventoryType, "getStackInSlot", 1);
            Method addItems = findMethod(internalInventoryType, "addItems", 1);

            for (int slot = 0; slot < slots; slot++) {
                Object existing = getStack.invoke(inventory, slot);
                if (existing instanceof ItemStack stack && !stack.isEmpty()) continue;

                Object overflow = addItems.invoke(inventory, new ItemStack(cell));
                if (overflow instanceof ItemStack stack && !stack.isEmpty()) return false;
            }
            return true;
        }

        private boolean isDone() {
            return finished;
        }
    }

    /**
     * Starts the generated machines. NETWORKED mode leaves power and output
     * handling to the generated external harness. ISOLATED mode deliberately
     * has no external mod infrastructure: a transient per-machine benchmark flag
     * bypasses FE consumption, storage checks, and real item deliveries while
     * keeping loot generation and transport visuals intact.
     */
    private static final class StressRun {
        private final ServerLevel level;
        private final UUID owner;
        private final ArrayList<BlockPos> machines;
        private final ArrayList<BlockPos> startupPending;
        private final List<BlockPos> powerSourcePositions;
        private final StressMode mode;
        private int startupCursor;
        private int maintenanceCursor;
        private boolean active;
        private boolean powerEnabled;

        private StressRun(
                ServerLevel level,
                UUID owner,
                int expectedMachines,
                List<BlockPos> powerSourcePositions,
                StressMode mode
        ) {
            this.level = level;
            this.owner = owner;
            this.machines = new ArrayList<>(expectedMachines);
            this.startupPending = new ArrayList<>(expectedMachines);
            this.powerSourcePositions = List.copyOf(powerSourcePositions);
            this.mode = mode;
        }

        private void add(BlockPos pos) {
            BlockPos immutable = pos.immutable();
            machines.add(immutable);
            startupPending.add(immutable);
        }

        private void activate() {
            active = true;
        }

        private int pendingStartupCount() {
            return startupPending.size();
        }

        private void tick() {
            if (!active) return;

            if (mode == StressMode.ISOLATED && !machines.isEmpty()) {
                tickIsolatedMaintenance();
            }

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

                if (mode == StressMode.ISOLATED) {
                    serviceIsolatedMachine(excavator);
                }

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

        private void tickIsolatedMaintenance() {
            int budget = Math.max(1, (machines.size() + ISOLATED_MAINTENANCE_PERIOD_TICKS - 1)
                    / ISOLATED_MAINTENANCE_PERIOD_TICKS);

            while (budget-- > 0 && !machines.isEmpty()) {
                if (maintenanceCursor >= machines.size()) maintenanceCursor = 0;
                BlockPos pos = machines.get(maintenanceCursor++);
                if (!level.hasChunkAt(pos)) continue;
                if (!(level.getBlockEntity(pos) instanceof ExcavatorBlockEntity excavator)) continue;
                serviceIsolatedMachine(excavator);
            }
        }

        private void serviceIsolatedMachine(ExcavatorBlockEntity excavator) {
            // Reapply after a chunk reload; normal steady-state calls are just an
            // idempotent boolean assignment instead of FE fills + 27-slot scans.
            excavator.debugSetBenchmarkResourceBypass(true);
        }

        private void disableIsolatedResourceBypass() {
            if (mode != StressMode.ISOLATED) return;
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

            return new RunStats(
                    machines.size(),
                    loaded,
                    scanning,
                    ready,
                    excavating,
                    storageFull,
                    complete
            );
        }
    }

    private enum StressMode {
        NETWORKED,
        ISOLATED
    }

    private static Method findMethod(Class<?> type, String name, int parameterCount) throws NoSuchMethodException {
        MethodKey key = new MethodKey(type, name, parameterCount);
        Method cached = METHOD_CACHE.get(key);
        if (cached != null) return cached;

        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == parameterCount) {
                METHOD_CACHE.put(key, method);
                return method;
            }
        }
        throw new NoSuchMethodException(type.getName() + "." + name + "/" + parameterCount);
    }

    private static Block findBlock(String idText) {
        if (BLOCK_CACHE.containsKey(idText)) return BLOCK_CACHE.get(idText);
        ResourceLocation id = ResourceLocation.tryParse(idText);
        Block block = id == null ? null : BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
        if (block == Blocks.AIR && !"minecraft:air".equals(idText)) block = null;
        BLOCK_CACHE.put(idText, block);
        return block;
    }

    private static Item findItem(String idText) {
        if (ITEM_CACHE.containsKey(idText)) return ITEM_CACHE.get(idText);
        ResourceLocation id = ResourceLocation.tryParse(idText);
        Item item = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
        ITEM_CACHE.put(idText, item);
        return item;
    }

    private record RunStats(
            int managed,
            int loaded,
            int scanning,
            int ready,
            int excavating,
            int storageFull,
            int complete
    ) {}

    private record MachineSpec(BlockPos pos, Direction facing) {}

    private record NetworkPlan(
            List<NetworkPlacement> placements,
            int rowNetworks,
            int importBuses,
            int superconductors,
            int drives,
            int storageCells,
            List<BlockPos> superconductorPositions,
            int powerNetworks,
            int maxPowerNetworkNodes,
            List<BlockPos> powerSourcePositions
    ) {}

    private enum NetworkKind {
        BLOCK,
        AE_CABLE,
        AE_IMPORT_TAP,
        AE_DRIVE
    }

    private record NetworkPlacement(
            BlockPos pos,
            NetworkKind kind,
            String id,
            Direction side
    ) {}

    private record RowNetworkSpec(
            int index,
            int gridX,
            int gridZ,
            int row,
            int rectangleBaseX,
            int rowMinZ,
            int colorIndex,
            BlockPos source,
            BlockPos target,
            int routeY
    ) {
        private RowNetworkSpec withTarget(BlockPos target) {
            return new RowNetworkSpec(
                    index, gridX, gridZ, row, rectangleBaseX, rowMinZ,
                    colorIndex, source, target, routeY
            );
        }
    }

    private record ReservedColumn(int x, int z, int colorIndex, int topY) {}

    private record PathNode(int x, int z, int g, int h) {}

    private record MethodKey(Class<?> type, String name, int parameterCount) {}

    /**
     * Deduplicates generated network blocks/parts while preserving deterministic
     * placement order. A position cannot simultaneously host an AE cable-bus
     * part and a normal block.
     */
    private static final class PlanBuilder {
        private final ArrayList<NetworkPlacement> placements = new ArrayList<>();
        private final LinkedHashMap<BlockPos, String> blockPositions = new LinkedHashMap<>();
        private final LinkedHashMap<BlockPos, String> aeCablePositions = new LinkedHashMap<>();

        private void addBlock(BlockPos pos, String blockId) {
            BlockPos key = pos.immutable();
            String aePart = aeCablePositions.get(key);
            if (aePart != null) {
                throw new IllegalStateException(
                        "Network plan collision at " + key + ": block " + blockId + " vs AE part " + aePart
                );
            }

            String existing = blockPositions.putIfAbsent(key, blockId);
            if (existing == null) {
                placements.add(new NetworkPlacement(key, NetworkKind.BLOCK, blockId, null));
            } else if (!existing.equals(blockId)) {
                throw new IllegalStateException(
                        "Network plan block collision at " + key + ": " + existing + " vs " + blockId
                );
            }
        }

        private void addAeCable(BlockPos pos, String cablePartId) {
            BlockPos key = pos.immutable();
            String block = blockPositions.get(key);
            if (block != null) {
                throw new IllegalStateException(
                        "Network plan collision at " + key + ": AE part " + cablePartId + " vs block " + block
                );
            }

            String existing = aeCablePositions.putIfAbsent(key, cablePartId);
            if (existing == null) {
                placements.add(new NetworkPlacement(key, NetworkKind.AE_CABLE, cablePartId, null));
            } else if (!existing.equals(cablePartId)) {
                throw new IllegalStateException(
                        "AE cable colour/type collision at " + key + ": " + existing + " vs " + cablePartId
                );
            }
        }

        private void addAeImportTap(BlockPos pos, String smartCablePartId, Direction side) {
            BlockPos key = pos.immutable();
            String block = blockPositions.get(key);
            if (block != null) {
                throw new IllegalStateException(
                        "Network plan collision at " + key + ": AE import tap vs block " + block
                );
            }
            String existing = aeCablePositions.putIfAbsent(key, smartCablePartId);
            if (existing != null) {
                throw new IllegalStateException("Duplicate AE import-tap host at " + key);
            }
            placements.add(new NetworkPlacement(key, NetworkKind.AE_IMPORT_TAP, smartCablePartId, side));
        }

        private void addDrive(BlockPos pos) {
            BlockPos key = pos.immutable();
            String existing = blockPositions.putIfAbsent(key, AE2_DRIVE);
            if (existing == null) {
                placements.add(new NetworkPlacement(key, NetworkKind.AE_DRIVE, AE2_DRIVE, null));
            } else if (!existing.equals(AE2_DRIVE)) {
                throw new IllegalStateException("ME Drive placement collision at " + key);
            }
        }
    }

    private static final class FillSpan {
        private final int minX;
        private final int maxX;
        private final int y;
        private final int minZ;
        private final int maxZ;
        private final boolean xIsOuter;

        private int outer;
        private int inner;
        private boolean done;

        private FillSpan(int minX, int maxX, int y, int minZ, int maxZ) {
            this.minX = minX;
            this.maxX = maxX;
            this.y = y;
            this.minZ = minZ;
            this.maxZ = maxZ;

            int sizeX = maxX - minX + 1;
            int sizeZ = maxZ - minZ + 1;
            this.xIsOuter = sizeX >= sizeZ;
            this.outer = xIsOuter ? minX : minZ;
            this.inner = xIsOuter ? minZ : minX;
        }

        private long size() {
            return (long) (maxX - minX + 1) * (maxZ - minZ + 1);
        }

        private boolean isDone() {
            return done;
        }

        private void writeCurrent(BlockPos.MutableBlockPos cursor) {
            if (xIsOuter) {
                cursor.set(outer, y, inner);
            } else {
                cursor.set(inner, y, outer);
            }
        }

        private void advance() {
            if (done) return;

            if (xIsOuter) {
                inner++;
                if (inner > maxZ) {
                    inner = minZ;
                    outer++;
                    if (outer > maxX) done = true;
                }
            } else {
                inner++;
                if (inner > maxX) {
                    inner = minX;
                    outer++;
                    if (outer > maxZ) done = true;
                }
            }
        }
    }
}
