package de.balto.laserexcavator.screen;

import de.balto.laserexcavator.block.blockentities.ExcavatorBlockEntity;
import de.balto.laserexcavator.block.excavator.ExcavatorScanState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;

public class ExcavatorScreen extends AbstractContainerScreen<ExcavatorMenu> {
    private static final int FRAME = 0xFF111820;
    private static final int PANEL_BG = 0xEE1B2430;
    private static final int PANEL_INNER = 0xEE27323F;
    private static final int PANEL_HEADER = 0xFF303B49;
    private static final int SLOT_INNER = 0xFF343F4D;

    private static final int TEXT = 0xFFF1EBDD;
    private static final int MUTED = 0xFFADB5C1;
    private static final int ACCENT = 0xFF76D7FF;
    private static final int GOLD = 0xFFFFD47A;
    private static final int GOOD = 0xFF7CFF9A;
    private static final int WARNING = 0xFFFFC96B;
    private static final int ERROR = 0xFFFF7A7A;
    private static final int ENERGY_EMPTY = 0xFF35171B;
    private static final int ENERGY_FILL = 0xFFE34F55;
    private static final int ENERGY_HIGHLIGHT = 0xFFFF8589;
    private static final int FUEL_BAR_EMPTY = 0xFF2A3038;
    private static final int FUEL_BAR_FILL = 0xFFE28A35;
    private static final int FUEL_BAR_HIGHLIGHT = 0xFFFFB45C;

    private static final int GUI_WIDTH = 205;
    private static final int GUI_HEIGHT = 269;

    private static final float TITLE_SCALE = 0.85F;
    private static final float LABEL_SCALE = 0.80F;
    private static final float STATUS_SCALE = 0.82F;

    private static final int GAP = 5;
    private static final int SLOT_STEP = 17;

    private static final int TITLE_BAR_Y = GAP;
    private static final int TITLE_BAR_HEIGHT = 13;
    private static final int TITLE_Y = 8;
    private static final int CONTROL_Y = 27;
    private static final int AXIS_LABEL_Y = 32;
    private static final int CONTROL_GROUP_WIDTH = 61;
    private static final int CONTROL_GROUP_GAP = 6;
    private static final int CONTROL_START_X = GAP;
    private static final int FIELD_WIDTH = 48;
    private static final int FIELD_X_OFFSET = 12;

    private static final int ACTION_X = GAP;
    private static final int ACTION_Y = 50;
    private static final int ACTION_WIDTH = 195;
    private static final int ACTION_BUTTON_WIDTH = (ACTION_WIDTH - GAP) / 2;
    private static final int ACTION_HEIGHT = 16;
    private static final int PROGRESS_Y = 71;
    private static final int STATUS_Y = 80;

    private static final int OUTPUT_PANEL_X = GAP;
    private static final int OUTPUT_PANEL_Y = 90;
    private static final int OUTPUT_PANEL_BOTTOM = 162;
    private static final int OUTPUT_GRID_X = OUTPUT_PANEL_X + GAP + 1;
    private static final int OUTPUT_GRID_Y = 104;
    private static final int OUTPUT_GRID_RIGHT = OUTPUT_GRID_X + ExcavatorBlockEntity.OUTPUT_COLUMNS * SLOT_STEP;
    private static final int OUTPUT_PANEL_RIGHT = OUTPUT_GRID_RIGHT + GAP;

    private static final int ENERGY_BAR_X = 48;
    private static final int ENERGY_BAR_Y = OUTPUT_PANEL_Y + 5;
    private static final int ENERGY_BAR_W = OUTPUT_GRID_RIGHT - ENERGY_BAR_X;
    private static final int ENERGY_BAR_H = 5;

    private static final int PLAYER_PANEL_X = GAP;
    private static final int PLAYER_PANEL_Y = 167;
    private static final int PLAYER_PANEL_RIGHT = OUTPUT_PANEL_RIGHT;
    private static final int PLAYER_PANEL_BOTTOM = GUI_HEIGHT - GAP;
    private static final int PLAYER_GRID_X = OUTPUT_GRID_X;
    private static final int PLAYER_GRID_Y = 183;
    private static final int PLAYER_HOTBAR_Y = 240;

    private static final int MOD_PANEL_X = OUTPUT_PANEL_RIGHT + GAP;
    private static final int MOD_PANEL_RIGHT = GUI_WIDTH - GAP;
    private static final int MOD_PANEL_Y_NO_FUEL = OUTPUT_PANEL_Y;
    private static final int MOD_PANEL_Y_WITH_FUEL = 122;
    private static final int MOD_PANEL_BOTTOM = PLAYER_PANEL_BOTTOM;
    private static final int UPGRADE_X = MOD_PANEL_X + 5;
    private static final int UPGRADE_Y_NO_FUEL = 107;
    private static final int UPGRADE_Y_WITH_FUEL = 140;

    private static final int FUEL_PANEL_X = MOD_PANEL_X;
    private static final int FUEL_PANEL_RIGHT = MOD_PANEL_RIGHT;
    private static final int FUEL_PANEL_Y = OUTPUT_PANEL_Y;
    private static final int FUEL_PANEL_BOTTOM = 121;
    private static final int FUEL_X = FUEL_PANEL_X + 5;
    private static final int FUEL_Y = 95;
    private static final int FUEL_BAR_Y = FUEL_Y + 18;
    private static final int FUEL_BAR_W = 18;
    private static final int FUEL_BAR_H = 5;

    private final List<EditBox> configurationFields = new ArrayList<>();
    private EditBox widthField;
    private EditBox heightField;
    private EditBox lengthField;
    private Button scanButton;
    private Button excavationButton;
    private Button filterButton;
    private boolean filterPanelOpen = false;

    private static final int FILTER_BUTTON_MARGIN = 2;
    private static final int FILTER_BUTTON_W = 44;
    private static final int FILTER_BUTTON_H = TITLE_BAR_HEIGHT - FILTER_BUTTON_MARGIN * 2;
    private static final int FILTER_BUTTON_X = GUI_WIDTH - GAP - FILTER_BUTTON_MARGIN - FILTER_BUTTON_W;
    private static final int FILTER_BUTTON_Y = TITLE_BAR_Y + FILTER_BUTTON_MARGIN;

    private static final int FILTER_PANEL_W = 146;
    private static final int FILTER_PANEL_H = 112;
    private static final int FILTER_GRID_X = 10;
    private static final int FILTER_GRID_Y = 32;
    private static final int FILTER_GRID_STEP = 19;
    private static final int FILTER_ACTION_X = 95;
    // The filter editor reuses the main screen palette so it reads as a modal
    // extension of the excavator UI rather than a visually separate interface.
    private static final int FILTER_ACCENT = 0xFF4FA9CC;
    private static final int FILTER_SLOT_HOVER = 0xFF3A4857;
    private static final int FILTER_DISABLED_SLOT = 0xFF202832;
    private static final int FILTER_DIVIDER = 0xFF3A4654;
    private static final int FILTER_SHADOW = 0x88000000;

    private static final int FILTER_COPY_BUTTON_X = FILTER_ACTION_X;
    private static final int FILTER_COPY_BUTTON_Y = 34;
    private static final int FILTER_COPY_BUTTON_W = 41;
    private static final int FILTER_COPY_BUTTON_H = 14;
    private static final int FILTER_PASTE_BUTTON_X = FILTER_ACTION_X;
    private static final int FILTER_PASTE_BUTTON_Y = 54;
    private static final int FILTER_PASTE_BUTTON_W = 41;
    private static final int FILTER_PASTE_BUTTON_H = 14;

    private static final Block[] COPIED_FILTER = new Block[ExcavatorBlockEntity.MAX_FILTER_SLOTS];
    private static int copiedFilterCapacity = 0;

    public ExcavatorScreen(ExcavatorMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = GUI_WIDTH;
        imageHeight = GUI_HEIGHT;
        titleLabelX = 10;
        titleLabelY = 8;
        inventoryLabelY = 1000;
    }

    public ExcavatorMenu getExcavatorMenu() {
        return menu;
    }

    @Override
    protected void init() {
        imageWidth = GUI_WIDTH;
        imageHeight = GUI_HEIGHT;
        super.init();

        configurationFields.clear();
        widthField = addDimensionField(CONTROL_START_X, menu.getSelectionWidth());
        heightField = addDimensionField(CONTROL_START_X + CONTROL_GROUP_WIDTH + CONTROL_GROUP_GAP, menu.getSelectionHeight());
        lengthField = addDimensionField(CONTROL_START_X + (CONTROL_GROUP_WIDTH + CONTROL_GROUP_GAP) * 2, menu.getSelectionLength());

        scanButton = addRenderableWidget(
                Button.builder(Component.literal("Scan Area"), button -> pressMenuButton(ExcavatorMenu.BUTTON_START_SCAN))
                        .bounds(leftPos + ACTION_X, topPos + ACTION_Y, ACTION_BUTTON_WIDTH, ACTION_HEIGHT)
                        .build()
        );

        excavationButton = addRenderableWidget(
                Button.builder(Component.literal("Start Excavation"), button -> pressMenuButton(ExcavatorMenu.BUTTON_TOGGLE_EXCAVATION))
                        .bounds(leftPos + ACTION_X + ACTION_BUTTON_WIDTH + GAP, topPos + ACTION_Y, ACTION_BUTTON_WIDTH, ACTION_HEIGHT)
                        .build()
        );

        filterButton = addRenderableWidget(
                Button.builder(Component.literal("Filter"), button -> filterPanelOpen = !filterPanelOpen)
                        .bounds(leftPos + FILTER_BUTTON_X, topPos + FILTER_BUTTON_Y, FILTER_BUTTON_W, FILTER_BUTTON_H)
                        .build()
        );

        updateControls();
    }

    private EditBox addDimensionField(int groupX, int value) {
        EditBox field = new EditBox(font,
                leftPos + groupX + FIELD_X_OFFSET,
                topPos + CONTROL_Y,
                FIELD_WIDTH,
                18,
                Component.empty());
        field.setValue(Integer.toString(value));
        field.setFilter(this::isDigitsOnly);
        field.setMaxLength(4);
        field.setTextColor(TEXT);
        field.setTextColorUneditable(MUTED);
        field.setBordered(true);
        configurationFields.add(addRenderableWidget(field));
        return field;
    }

    private boolean isDigitsOnly(String value) {
        return value.chars().allMatch(Character::isDigit);
    }

    private boolean isParsableNumber(String value) {
        return !value.isBlank() && value.chars().allMatch(Character::isDigit);
    }

    private void sendSelectionValue(EditBox field, String text) {
        int dataIndex = field == widthField ? ExcavatorMenu.DATA_WIDTH
                : field == heightField ? ExcavatorMenu.DATA_HEIGHT
                : ExcavatorMenu.DATA_LENGTH;

        int requested;
        try {
            requested = Integer.parseInt(text);
        } catch (NumberFormatException ignored) {
            return;
        }

        int currentValue = dataIndex == ExcavatorMenu.DATA_WIDTH ? menu.getSelectionWidth()
                : dataIndex == ExcavatorMenu.DATA_HEIGHT ? menu.getSelectionHeight()
                : menu.getSelectionLength();
        if (requested == currentValue) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.gameMode == null) return;

        int buttonId = ExcavatorMenu.encodeSetSelectionButton(dataIndex, requested);
        menu.clickMenuButton(minecraft.player, buttonId);
        minecraft.gameMode.handleInventoryButtonClick(menu.containerId, buttonId);
        syncFieldsFromMenu();
        updateControls();
    }

    private void pressMenuButton(int id) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.gameMode == null) return;

        commitFocusedField();
        menu.clickMenuButton(minecraft.player, id);
        minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id);
        updateControls();
    }

    private void commitFocusedField() {
        for (EditBox field : configurationFields) {
            if (field.isFocused() && isParsableNumber(field.getValue())) {
                sendSelectionValue(field, field.getValue());
            }
        }
    }

    private void syncFieldsFromMenu() {
        syncField(widthField, menu.getSelectionWidth());
        syncField(heightField, menu.getSelectionHeight());
        syncField(lengthField, menu.getSelectionLength());
    }

    private static void syncField(EditBox field, int value) {
        if (field.isFocused()) return;
        String expected = Integer.toString(value);
        if (!expected.equals(field.getValue())) {
            field.setValue(expected);
        }
    }

    private void updateControls() {
        ExcavatorScanState state = menu.getScanState();
        boolean overheating = menu.isOverheating();
        boolean busy = state == ExcavatorScanState.SCANNING
                || state == ExcavatorScanState.EXCAVATING
                || state == ExcavatorScanState.STORAGE_FULL
                || menu.getPendingItemCount() > 0;

        for (EditBox field : configurationFields) {
            field.setEditable(!busy);
            field.setTextColor(busy ? MUTED : TEXT);
        }

        scanButton.active = !overheating
                && state != ExcavatorScanState.SCANNING
                && state != ExcavatorScanState.EXCAVATING
                && state != ExcavatorScanState.STORAGE_FULL
                && menu.getPendingItemCount() == 0;

        if (overheating) {
            scanButton.setMessage(Component.literal("Overheated"));
        } else if (menu.getPendingItemCount() > 0
                && state != ExcavatorScanState.SCANNING
                && state != ExcavatorScanState.EXCAVATING
                && state != ExcavatorScanState.STORAGE_FULL) {
            scanButton.setMessage(Component.literal("Delivering..."));
        } else {
            scanButton.setMessage(Component.literal(switch (state) {
                case IDLE -> "Scan";
                case SCANNING -> "Scanning...";
                case READY -> "Rescan";
                case EXCAVATING, STORAGE_FULL -> "Locked";
                case COMPLETE -> "Scan Again";
            }));
        }

        filterButton.visible = menu.getFilterCapacity() > 0;
        filterButton.active = filterButton.visible && !busy;
        if (!filterButton.visible) filterPanelOpen = false;

        excavationButton.active = state == ExcavatorScanState.EXCAVATING
                || state == ExcavatorScanState.STORAGE_FULL
                || (state == ExcavatorScanState.READY && !overheating);
        excavationButton.setMessage(Component.literal(
                overheating && state != ExcavatorScanState.EXCAVATING && state != ExcavatorScanState.STORAGE_FULL
                        ? "Overheated"
                        : switch (state) {
                            case EXCAVATING, STORAGE_FULL -> "Pause";
                            default -> "Excavate";
                        }
        ));
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        syncFieldsFromMenu();
        updateControls();
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;

        graphics.fill(x, y, x + imageWidth, y + imageHeight, FRAME);
        graphics.fill(x + 2, y + 2, x + imageWidth - 2, y + imageHeight - 2, PANEL_BG);

        graphics.fill(x + GAP, y + TITLE_BAR_Y, x + imageWidth - GAP, y + TITLE_BAR_Y + TITLE_BAR_HEIGHT, PANEL_HEADER);

        drawProgress(graphics, x + ACTION_X, y + PROGRESS_Y, ACTION_WIDTH, 5, menu.getScanProgress());

        graphics.fill(x + OUTPUT_PANEL_X, y + OUTPUT_PANEL_Y, x + OUTPUT_PANEL_RIGHT, y + OUTPUT_PANEL_BOTTOM, FRAME);
        graphics.fill(x + OUTPUT_PANEL_X + 2, y + OUTPUT_PANEL_Y + 2, x + OUTPUT_PANEL_RIGHT - 2, y + OUTPUT_PANEL_BOTTOM - 2, PANEL_INNER);
        drawEnergyBar(graphics, x + ENERGY_BAR_X, y + ENERGY_BAR_Y, ENERGY_BAR_W, ENERGY_BAR_H);
        drawSlotGrid(graphics, x + OUTPUT_GRID_X, y + OUTPUT_GRID_Y,
                ExcavatorBlockEntity.OUTPUT_COLUMNS, ExcavatorBlockEntity.OUTPUT_ROWS);

        graphics.fill(x + PLAYER_PANEL_X, y + PLAYER_PANEL_Y, x + PLAYER_PANEL_RIGHT, y + PLAYER_PANEL_BOTTOM, FRAME);
        graphics.fill(x + PLAYER_PANEL_X + 2, y + PLAYER_PANEL_Y + 2, x + PLAYER_PANEL_RIGHT - 2, y + PLAYER_PANEL_BOTTOM - 2, PANEL_INNER);
        drawSlotGrid(graphics, x + PLAYER_GRID_X, y + PLAYER_GRID_Y, 9, 3);
        drawSlotGrid(graphics, x + PLAYER_GRID_X, y + PLAYER_HOTBAR_Y, 9, 1);

        boolean fuelEnabled = menu.isFuelSlotEnabled();
        int modPanelY = fuelEnabled ? MOD_PANEL_Y_WITH_FUEL : MOD_PANEL_Y_NO_FUEL;
        int upgradeY = fuelEnabled ? UPGRADE_Y_WITH_FUEL : UPGRADE_Y_NO_FUEL;

        if (fuelEnabled) {
            graphics.fill(x + FUEL_PANEL_X, y + FUEL_PANEL_Y, x + FUEL_PANEL_RIGHT, y + FUEL_PANEL_BOTTOM, FRAME);
            graphics.fill(x + FUEL_PANEL_X + 2, y + FUEL_PANEL_Y + 2,
                    x + FUEL_PANEL_RIGHT - 2, y + FUEL_PANEL_BOTTOM - 2, PANEL_INNER);
            drawSlot(graphics, x + FUEL_X, y + FUEL_Y);
            drawFuelProgressBar(graphics, x + FUEL_X - 1, y + FUEL_BAR_Y);
        }

        graphics.fill(x + MOD_PANEL_X, y + modPanelY, x + MOD_PANEL_RIGHT, y + MOD_PANEL_BOTTOM, FRAME);
        graphics.fill(x + MOD_PANEL_X + 2, y + modPanelY + 2, x + MOD_PANEL_RIGHT - 2, y + MOD_PANEL_BOTTOM - 2, PANEL_INNER);
        graphics.fill(x + MOD_PANEL_X + 2, y + modPanelY + 2, x + MOD_PANEL_RIGHT - 2, y + modPanelY + 14, PANEL_HEADER);

        for (int slot = 0; slot < menu.getUpgradeSlotCount(); slot++) {
            drawSlot(graphics, x + UPGRADE_X, y + upgradeY + slot * SLOT_STEP);
        }

    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        drawScaledString(graphics, getTitle().getString(), GAP + 5, TITLE_Y, TEXT, TITLE_SCALE);

        drawScaledString(graphics, "X:", CONTROL_START_X + 1, AXIS_LABEL_Y, TEXT, LABEL_SCALE);
        drawScaledString(graphics, "Y:", CONTROL_START_X + CONTROL_GROUP_WIDTH + CONTROL_GROUP_GAP + 1, AXIS_LABEL_Y, TEXT, LABEL_SCALE);
        drawScaledString(graphics, "Z:", CONTROL_START_X + (CONTROL_GROUP_WIDTH + CONTROL_GROUP_GAP) * 2 + 1, AXIS_LABEL_Y, TEXT, LABEL_SCALE);

        int statusMaxWidth = Math.round((imageWidth - 28) / STATUS_SCALE);
        String status = font.plainSubstrByWidth(statusText(), statusMaxWidth);
        drawCenteredScaledString(graphics, status, imageWidth / 2.0F, STATUS_Y, statusColor(), STATUS_SCALE);

        drawScaledString(graphics, "OUTPUT", OUTPUT_GRID_X, OUTPUT_PANEL_Y + 5, TEXT, LABEL_SCALE);
        int modPanelY = menu.isFuelSlotEnabled() ? MOD_PANEL_Y_WITH_FUEL : MOD_PANEL_Y_NO_FUEL;
        drawCenteredScaledString(graphics, "MOD", (MOD_PANEL_X + MOD_PANEL_RIGHT) / 2.0F, modPanelY + 5, GOLD, LABEL_SCALE);
        drawScaledString(graphics, "INVENTORY", PLAYER_GRID_X, PLAYER_PANEL_Y + 5, TEXT, LABEL_SCALE);
    }

    private void drawSlot(GuiGraphics graphics, int x, int y) {
        int drawX = x - 1;
        int drawY = y - 1;
        graphics.fill(drawX, drawY, drawX + 18, drawY + 18, FRAME);
        graphics.fill(drawX + 1, drawY + 1, drawX + 17, drawY + 17, SLOT_INNER);
    }

    private void drawFuelProgressBar(GuiGraphics graphics, int x, int y) {
        graphics.fill(x, y, x + FUEL_BAR_W, y + FUEL_BAR_H, FRAME);
        graphics.fill(x + 1, y + 1, x + FUEL_BAR_W - 1, y + FUEL_BAR_H - 1, FUEL_BAR_EMPTY);

        int innerWidth = FUEL_BAR_W - 2;
        int fill = Math.max(0, Math.min(innerWidth, Math.round(innerWidth * menu.getFuelBurnFraction())));
        if (fill > 0) {
            graphics.fill(x + 1, y + 1, x + 1 + fill, y + FUEL_BAR_H - 1, FUEL_BAR_FILL);
            graphics.fill(x + 1, y + 1, x + 1 + fill, y + 2, FUEL_BAR_HIGHLIGHT);
        }
    }

    private void drawSlotGrid(GuiGraphics graphics, int x, int y, int columns, int rows) {
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < columns; col++) {
                drawSlot(graphics, x + col * SLOT_STEP, y + row * SLOT_STEP);
            }
        }
    }

    private void drawProgress(GuiGraphics graphics, int x, int y, int width, int height, float progress) {
        graphics.fill(x, y, x + width, y + height, FRAME);
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, PANEL_INNER);
        int fill = Math.max(0, Math.round((width - 2) * progress));
        if (fill > 0) {
            graphics.fill(x + 1, y + 1, x + 1 + fill, y + height - 1, ACCENT);
        }
    }

    private void drawEnergyBar(GuiGraphics graphics, int x, int y, int width, int height) {
        graphics.fill(x, y, x + width, y + height, FRAME);
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, ENERGY_EMPTY);

        int innerWidth = Math.max(0, width - 2);
        int fill = Math.max(0, Math.round(innerWidth * menu.getEnergyFraction()));
        if (fill > 0) {
            graphics.fill(x + 1, y + 1, x + 1 + fill, y + height - 1, ENERGY_FILL);
            if (height >= 4) {
                graphics.fill(x + 1, y + 1, x + 1 + fill, y + 2, ENERGY_HIGHLIGHT);
            }
        }
    }

    private String statusText() {
        ExcavatorScanState state = menu.getScanState();
        int pending = menu.getPendingItemCount();

        if (menu.isOverheating()) {
            return "OVERHEATING  |  Cooling required";
        }

        return switch (state) {
            case IDLE -> "Configure area and scan";
            case SCANNING -> "Scanning " + Math.round(menu.getScanProgress() * 100.0F) + "%  "
                    + menu.getScannedColumns() + "/" + menu.getTotalColumns();
            case READY -> "Ready  |  field Y " + menu.getForceFieldY() + transitText(pending);
            case EXCAVATING -> menu.isWaitingForEnergy()
                    ? "Waiting for energy  |  " + menu.getBlocksExcavated() + " removed" + transitText(pending)
                    : "Excavating  |  " + menu.getBlocksExcavated() + " removed" + transitText(pending);
            case STORAGE_FULL -> "Output full  |  waiting" + transitText(pending);
            case COMPLETE -> pending > 0
                    ? "Finishing delivery" + transitText(pending)
                    : "Complete  |  " + menu.getBlocksExcavated() + " removed";
        };
    }

    private int statusColor() {
        if (menu.isOverheating() || menu.isWaitingForEnergy()) return ERROR;
        return switch (menu.getScanState()) {
            case IDLE -> MUTED;
            case SCANNING -> WARNING;
            case READY, COMPLETE -> GOOD;
            case EXCAVATING -> ACCENT;
            case STORAGE_FULL -> ERROR;
        };
    }

    private static String transitText(int pending) {
        return pending > 0 ? "  |  " + pending + " transit" : "";
    }

    private void drawScaledString(
            GuiGraphics graphics,
            String text,
            float x,
            float y,
            int color,
            float scale
    ) {
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0);
        graphics.pose().scale(scale, scale, 1.0F);
        graphics.drawString(font, text, 0, 0, color, false);
        graphics.pose().popPose();
    }

    private void drawCenteredScaledString(
            GuiGraphics graphics,
            String text,
            float centerX,
            float y,
            int color,
            float scale
    ) {
        float x = centerX - font.width(text) * scale * 0.5F;
        drawScaledString(graphics, text, x, y, color, scale);
    }

    private void renderEnergyTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = leftPos + ENERGY_BAR_X;
        int y = topPos + ENERGY_BAR_Y;
        if (!isInside(mouseX, mouseY, x, y, ENERGY_BAR_W, ENERGY_BAR_H)) return;

        graphics.renderComponentTooltip(
                font,
                List.of(
                        Component.literal("Energy").withStyle(ChatFormatting.RED),
                        Component.literal(formatEnergy(menu.getEnergyStored()) + " / "
                                + formatEnergy(menu.getEnergyCapacity()) + " FE").withStyle(ChatFormatting.GRAY),
                        Component.literal("Current draw: " + formatEnergy(menu.getEnergyUsagePerTick()) + " FE/t")
                                .withStyle(menu.getEnergyUsagePerTick() > 0 ? ChatFormatting.RED : ChatFormatting.DARK_GRAY),
                        Component.literal("Per block: " + formatEnergy(menu.getEnergyPerBlock()) + " FE")
                                .withStyle(ChatFormatting.DARK_GRAY)
                ),
                mouseX,
                mouseY
        );
    }

    private void renderEmptySlotTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        if (hoveredSlot == null || hoveredSlot.hasItem()) return;

        List<Component> tooltip = null;
        if (menu.isFuelSlotEnabled() && hoveredSlot.x == FUEL_X && hoveredSlot.y == FUEL_Y) {
            tooltip = List.of(
                    Component.literal("Fuel").withStyle(ChatFormatting.GOLD),
                    Component.literal("Burnable items generate internal FE.").withStyle(ChatFormatting.GRAY)
            );
        } else if (hoveredSlot.x == UPGRADE_X) {
            int upgradeY = menu.isFuelSlotEnabled() ? UPGRADE_Y_WITH_FUEL : UPGRADE_Y_NO_FUEL;
            int offset = hoveredSlot.y - upgradeY;
            if (offset >= 0 && offset % SLOT_STEP == 0 && offset / SLOT_STEP < menu.getUpgradeSlotCount()) {
                tooltip = List.of(
                        Component.literal("Upgrade").withStyle(ChatFormatting.GOLD),
                        Component.literal("Insert an excavator upgrade.").withStyle(ChatFormatting.GRAY)
                );
            }
        } else if (hoveredSlot.x >= OUTPUT_GRID_X && hoveredSlot.x < OUTPUT_GRID_RIGHT
                && hoveredSlot.y >= OUTPUT_GRID_Y
                && hoveredSlot.y < OUTPUT_GRID_Y + ExcavatorBlockEntity.OUTPUT_ROWS * SLOT_STEP) {
            int xOffset = hoveredSlot.x - OUTPUT_GRID_X;
            int yOffset = hoveredSlot.y - OUTPUT_GRID_Y;
            if (xOffset % SLOT_STEP == 0 && yOffset % SLOT_STEP == 0) {
                tooltip = List.of(
                        Component.literal("Output").withStyle(ChatFormatting.GOLD),
                        Component.literal("Excavated items are stored here.").withStyle(ChatFormatting.GRAY)
                );
            }
        }

        if (tooltip != null) {
            graphics.renderComponentTooltip(font, tooltip, mouseX, mouseY);
        }
    }

    private static String formatEnergy(int value) {
        return String.format(java.util.Locale.ROOT, "%,d", Math.max(0, value));
    }

    private int filterPanelX() {
        return leftPos + (imageWidth - FILTER_PANEL_W) / 2;
    }

    private int filterPanelY() {
        // The editor ends just above the player inventory. While open it behaves
        // as a modal screen and blocks interaction with the underlying UI.
        return topPos + 52;
    }

    private void renderFilterPanel(GuiGraphics graphics, int mouseX, int mouseY) {
        if (!filterPanelOpen || menu.getFilterCapacity() <= 0) return;

        int x = filterPanelX();
        int y = filterPanelY();
        int capacity = menu.getFilterCapacity();

        graphics.pose().pushPose();
        graphics.pose().translate(0, 0, 500);

        // Match the main OUTPUT / MOD / INVENTORY panel hierarchy: dark frame,
        // lighter inner panel and the same header tone. Cyan is only an accent.
        graphics.fill(x + 4, y + 4, x + FILTER_PANEL_W + 4, y + FILTER_PANEL_H + 4, FILTER_SHADOW);
        drawPanel(graphics, x, y, x + FILTER_PANEL_W, y + FILTER_PANEL_H);
        drawPanelHeader(graphics, x, y, x + FILTER_PANEL_W, y + 20);
        graphics.fill(x + 2, y + 20, x + FILTER_PANEL_W - 2, y + 21, FILTER_ACCENT);

        graphics.drawString(font, "Block Filter", x + 8, y + 7, TEXT, false);
        drawScaledString(graphics, capacity + "/" + ExcavatorBlockEntity.MAX_FILTER_SLOTS,
                x + FILTER_PANEL_W - 28, y + 7, MUTED, 0.68F);

        drawCenteredScaledString(graphics, "FILTER SLOTS",
                x + FILTER_GRID_X + 34.5F, y + 24, TEXT, 0.62F);
        drawCenteredScaledString(graphics, "ACTIONS",
                x + FILTER_ACTION_X + FILTER_COPY_BUTTON_W / 2.0F, y + 24, TEXT, 0.62F);
        graphics.fill(x + 89, y + 26, x + 90, y + FILTER_PANEL_H - 7, FILTER_DIVIDER);

        for (int slot = 0; slot < ExcavatorBlockEntity.MAX_FILTER_SLOTS; slot++) {
            int sx = x + FILTER_GRID_X + (slot % 4) * FILTER_GRID_STEP;
            int sy = y + FILTER_GRID_Y + (slot / 4) * FILTER_GRID_STEP;
            boolean active = slot < capacity;
            boolean hovered = active && isInside(mouseX, mouseY, sx, sy, 16, 16);

            if (active) {
                drawFilterSlot(graphics, sx, sy, hovered);
            } else {
                graphics.fill(sx - 1, sy - 1, sx + 17, sy + 17, FRAME);
                graphics.fill(sx, sy, sx + 16, sy + 16, FILTER_DISABLED_SLOT);
                graphics.fill(sx + 3, sy + 7, sx + 13, sy + 9, FRAME);
                continue;
            }

            Block block = menu.getFilterBlock(slot);
            if (block != null) {
                ItemStack stack = block.asItem().getDefaultInstance();
                if (!stack.isEmpty()) graphics.renderItem(stack, sx, sy);
            }
        }

        boolean canEdit = !menu.isUpgradeConfigurationLocked();
        boolean copyHovered = isInside(mouseX, mouseY,
                x + FILTER_COPY_BUTTON_X, y + FILTER_COPY_BUTTON_Y,
                FILTER_COPY_BUTTON_W, FILTER_COPY_BUTTON_H);
        boolean pasteHovered = isInside(mouseX, mouseY,
                x + FILTER_PASTE_BUTTON_X, y + FILTER_PASTE_BUTTON_Y,
                FILTER_PASTE_BUTTON_W, FILTER_PASTE_BUTTON_H);

        drawFilterActionButton(
                graphics,
                x + FILTER_COPY_BUTTON_X,
                y + FILTER_COPY_BUTTON_Y,
                FILTER_COPY_BUTTON_W,
                FILTER_COPY_BUTTON_H,
                "Copy",
                true,
                copyHovered
        );
        drawFilterActionButton(
                graphics,
                x + FILTER_PASTE_BUTTON_X,
                y + FILTER_PASTE_BUTTON_Y,
                FILTER_PASTE_BUTTON_W,
                FILTER_PASTE_BUTTON_H,
                "Paste",
                canEdit && copiedFilterCapacity > 0,
                pasteHovered
        );

        drawCenteredScaledString(graphics, "Hold a block",
                x + FILTER_ACTION_X + FILTER_COPY_BUTTON_W / 2.0F, y + 78, MUTED, 0.58F);
        drawCenteredScaledString(graphics, "and click a slot",
                x + FILTER_ACTION_X + FILTER_COPY_BUTTON_W / 2.0F, y + 86, MUTED, 0.58F);

        if (!canEdit) {
            drawCenteredScaledString(graphics, "Locked while running",
                    x + FILTER_PANEL_W / 2.0F, y + FILTER_PANEL_H - 7, WARNING, 0.58F);
        }

        graphics.pose().popPose();
    }

    private void renderFilterPanelTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        if (!filterPanelOpen) return;

        int x = filterPanelX();
        int y = filterPanelY();
        int capacity = menu.getFilterCapacity();
        for (int slot = 0; slot < capacity; slot++) {
            int sx = x + FILTER_GRID_X + (slot % 4) * FILTER_GRID_STEP;
            int sy = y + FILTER_GRID_Y + (slot / 4) * FILTER_GRID_STEP;
            if (!isInside(mouseX, mouseY, sx, sy, 16, 16)) continue;

            Block block = menu.getFilterBlock(slot);
            graphics.pose().pushPose();
            graphics.pose().translate(0.0F, 0.0F, 900.0F);
            if (block == null) {
                graphics.renderComponentTooltip(
                        font,
                        List.of(
                                Component.literal("Filter slot").withStyle(ChatFormatting.GOLD),
                                Component.literal("Hold a block and click to filter it.").withStyle(ChatFormatting.GRAY)
                        ),
                        mouseX,
                        mouseY
                );
            } else {
                ItemStack stack = block.asItem().getDefaultInstance();
                if (!stack.isEmpty()) {
                    graphics.renderTooltip(font, stack, mouseX, mouseY);
                }
            }
            graphics.pose().popPose();
            return;
        }
    }

    private void drawFilterActionButton(
            GuiGraphics graphics,
            int x,
            int y,
            int width,
            int height,
            String label,
            boolean active,
            boolean hovered
    ) {
        int border = active && hovered ? GOLD : FRAME;
        int background = active
                ? (hovered ? FILTER_SLOT_HOVER : PANEL_HEADER)
                : FILTER_DISABLED_SLOT;
        int textColor = active ? TEXT : 0xFF69727D;

        graphics.fill(x - 1, y - 1, x + width + 1, y + height + 1, border);
        graphics.fill(x, y, x + width, y + height, background);
        if (active && !hovered) {
            graphics.fill(x, y, x + width, y + 1, FILTER_ACCENT);
        }
        drawCenteredScaledString(graphics, label, x + width / 2.0F, y + 4.0F, textColor, 0.68F);
    }

    private void drawFilterSlot(GuiGraphics graphics, int x, int y, boolean hovered) {
        int border = hovered ? GOLD : FRAME;
        int background = hovered ? FILTER_SLOT_HOVER : SLOT_INNER;
        graphics.fill(x - 1, y - 1, x + 17, y + 17, border);
        graphics.fill(x, y, x + 16, y + 16, background);
    }

    private void drawPanel(GuiGraphics graphics, int left, int top, int right, int bottom) {
        graphics.fill(left, top, right, bottom, FRAME);
        graphics.fill(left + 2, top + 2, right - 2, bottom - 2, PANEL_INNER);
    }

    private void drawPanelHeader(GuiGraphics graphics, int left, int top, int right, int bottom) {
        graphics.fill(left + 2, top + 2, right - 2, bottom, PANEL_HEADER);
    }

    private static boolean isInside(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    private void copyCurrentFilter() {
        int capacity = menu.getFilterCapacity();
        for (int slot = 0; slot < ExcavatorBlockEntity.MAX_FILTER_SLOTS; slot++) {
            COPIED_FILTER[slot] = slot < capacity ? menu.getFilterBlock(slot) : null;
        }
        copiedFilterCapacity = capacity;
    }

    private void pasteCopiedFilter() {
        if (copiedFilterCapacity <= 0 || menu.isUpgradeConfigurationLocked()) return;

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.gameMode == null) return;

        commitFocusedField();

        int targetCapacity = menu.getFilterCapacity();
        for (int slot = 0; slot < targetCapacity; slot++) {
            Block block = slot < copiedFilterCapacity ? COPIED_FILTER[slot] : null;
            int buttonId = ExcavatorMenu.encodeFilterBlockButton(slot, block);
            menu.clickMenuButton(minecraft.player, buttonId);
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, buttonId);
        }

        updateControls();
    }

    private boolean handleFilterPanelClick(double mouseX, double mouseY, int button) {
        if (!filterPanelOpen) return false;
        int x = filterPanelX();
        int y = filterPanelY();
        // Keep the filter editor modal for the machine controls, but leave the
        // player's inventory slots interactive. Picking up a block from the
        // inventory is required to configure a filter slot.
        if (mouseX < x || mouseX >= x + FILTER_PANEL_W || mouseY < y || mouseY >= y + FILTER_PANEL_H) {
            if (isOverPlayerInventorySlot(mouseX, mouseY)) {
                return false;
            }
            if (isInside(mouseX, mouseY,
                    leftPos + FILTER_BUTTON_X, topPos + FILTER_BUTTON_Y,
                    FILTER_BUTTON_W, FILTER_BUTTON_H)) {
                filterPanelOpen = false;
            }
            return true;
        }
        if (button != 0) return true;

        if (isInside(mouseX, mouseY,
                x + FILTER_COPY_BUTTON_X, y + FILTER_COPY_BUTTON_Y,
                FILTER_COPY_BUTTON_W, FILTER_COPY_BUTTON_H)) {
            copyCurrentFilter();
            return true;
        }

        if (isInside(mouseX, mouseY,
                x + FILTER_PASTE_BUTTON_X, y + FILTER_PASTE_BUTTON_Y,
                FILTER_PASTE_BUTTON_W, FILTER_PASTE_BUTTON_H)) {
            pasteCopiedFilter();
            return true;
        }

        int capacity = menu.getFilterCapacity();
        for (int slot = 0; slot < capacity; slot++) {
            int sx = x + FILTER_GRID_X + (slot % 4) * FILTER_GRID_STEP;
            int sy = y + FILTER_GRID_Y + (slot / 4) * FILTER_GRID_STEP;
            if (mouseX >= sx && mouseX < sx + 16 && mouseY >= sy && mouseY < sy + 16) {
                pressMenuButton(ExcavatorMenu.BUTTON_FILTER_SLOT_BASE + slot);
                return true;
            }
        }
        return true;
    }

    private boolean isOverPlayerInventorySlot(double mouseX, double mouseY) {
        int gridX = leftPos + PLAYER_GRID_X;
        int gridY = topPos + PLAYER_GRID_Y;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                if (isInside(mouseX, mouseY,
                        gridX + col * SLOT_STEP, gridY + row * SLOT_STEP,
                        16, 16)) {
                    return true;
                }
            }
        }

        int hotbarY = topPos + PLAYER_HOTBAR_Y;
        for (int col = 0; col < 9; col++) {
            if (isInside(mouseX, mouseY,
                    gridX + col * SLOT_STEP, hotbarY,
                    16, 16)) {
                return true;
            }
        }
        return false;
    }

    private boolean isOverUpgradeSlot(double mouseX, double mouseY) {
        int x = leftPos + UPGRADE_X;
        int y = topPos + (menu.isFuelSlotEnabled() ? UPGRADE_Y_WITH_FUEL : UPGRADE_Y_NO_FUEL);
        for (int slot = 0; slot < menu.getUpgradeSlotCount(); slot++) {
            int sy = y + slot * SLOT_STEP;
            if (mouseX >= x - 1 && mouseX < x + 17 && mouseY >= sy - 1 && mouseY < sy + 17) return true;
        }
        return false;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (handleFilterPanelClick(mouseX, mouseY, button)) return true;
        boolean result = super.mouseClicked(mouseX, mouseY, button);
        for (EditBox field : configurationFields) {
            if (!field.isFocused() && isParsableNumber(field.getValue())) {
                sendSelectionValue(field, field.getValue());
            }
        }
        return result;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (filterPanelOpen) {
            if (keyCode == 256) {
                filterPanelOpen = false;
            }
            return true;
        }

        boolean handled = super.keyPressed(keyCode, scanCode, modifiers);
        if (keyCode == 257 || keyCode == 335 || keyCode == 258) {
            commitFocusedField();
            return true;
        }
        return handled;
    }

    @Override
    public void removed() {
        commitFocusedField();
        super.removed();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        if (!filterPanelOpen) {
            renderTooltip(graphics, mouseX, mouseY);
            renderEmptySlotTooltip(graphics, mouseX, mouseY);
            renderEnergyTooltip(graphics, mouseX, mouseY);
        } else {
            renderFilterPanel(graphics, mouseX, mouseY);

            // Base-screen tooltips are rendered only outside the modal itself and
            // after it, so they cannot be covered by the filter screen.
            if (!isInside(mouseX, mouseY, filterPanelX(), filterPanelY(), FILTER_PANEL_W, FILTER_PANEL_H)) {
                graphics.pose().pushPose();
                graphics.pose().translate(0.0F, 0.0F, 900.0F);
                renderTooltip(graphics, mouseX, mouseY);
                renderEmptySlotTooltip(graphics, mouseX, mouseY);
                graphics.pose().popPose();
            }
            renderFilterPanelTooltip(graphics, mouseX, mouseY);
        }

        ItemStack carried = menu.getCarried();
        if (filterPanelOpen && !carried.isEmpty()) {
            graphics.pose().pushPose();
            graphics.pose().translate(0.0F, 0.0F, 850.0F);
            graphics.renderItem(carried, mouseX - 8, mouseY - 8);
            graphics.renderItemDecorations(font, carried, mouseX - 8, mouseY - 8);
            graphics.pose().popPose();
        }

        Component conflict = carried.isEmpty() ? null : menu.getUpgradeConflictMessage(carried);
        if (conflict != null && isOverUpgradeSlot(mouseX, mouseY)) {
            graphics.pose().pushPose();
            graphics.pose().translate(0.0F, 0.0F, 950.0F);
            graphics.renderTooltip(font, conflict.copy().withStyle(ChatFormatting.RED), mouseX, mouseY);
            graphics.pose().popPose();
        }
    }
}
