package de.balto.laserexcavator.screen;

import de.balto.laserexcavator.block.blockentities.ExcavatorBlockEntity;
import de.balto.laserexcavator.block.excavator.ExcavatorScanState;
import de.balto.laserexcavator.block.excavator.ExcavatorSolarManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;

import static de.balto.laserexcavator.screen.ExcavatorUiStyle.*;

/**
 * This class contains the general screen of the Mod and takes care
 * of the UI graphics.
 */

public class ExcavatorScreen extends AbstractContainerScreen<ExcavatorMenu> {
    private static final int ENERGY_EMPTY = 0xFF35171B;
    private static final int ENERGY_FILL = 0xFFE34F55;
    private static final int ENERGY_HIGHLIGHT = 0xFFFF8589;
    private static final int FUEL_BAR_EMPTY = 0xFF2A3038;
    private static final int FUEL_BAR_FILL = 0xFFE28A35;
    private static final int FUEL_BAR_HIGHLIGHT = 0xFFFFB45C;
    private static final int SOLAR_ACTIVE = 0xFFFFD45C;
    private static final int SOLAR_NIGHT = 0xFFC6D1E3;
    private static final int SOLAR_BLOCKED = 0xFFFF6B6B;

    private static final int GUI_WIDTH = 205;
    private static final int GUI_HEIGHT = 269;

    private static final int GAP = 5;
    static final int SLOT_STEP = 17;

    private static final int TITLE_BAR_Y = GAP;
    private static final int TITLE_BAR_HEIGHT = 15;
    private static final int TITLE_Y = 9;
    private static final int CONTROL_Y = 25;
    private static final int AXIS_LABEL_Y = 30;
    private static final int CONTROL_GROUP_WIDTH = 61;
    private static final int CONTROL_GROUP_GAP = 6;
    private static final int CONTROL_START_X = GAP;
    private static final int FIELD_WIDTH = 48;
    private static final int FIELD_X_OFFSET = 12;

    private static final int ACTION_X = GAP;
    private static final int ACTION_Y = 48;
    private static final int ACTION_WIDTH = 195;
    private static final int ACTION_BUTTON_WIDTH = (ACTION_WIDTH - GAP) / 2;
    private static final int ACTION_HEIGHT = 16;
    private static final int PROGRESS_Y = 70;
    private static final int STATUS_Y = 80;
    private static final int LIMIT_TILE_X = 190;
    private static final int LIMIT_TILE_Y = 78;
    private static final int LIMIT_TILE_W = 10;
    private static final int LIMIT_TILE_H = 10;

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
    static final int PLAYER_PANEL_Y = 167;
    private static final int PLAYER_PANEL_RIGHT = OUTPUT_PANEL_RIGHT;
    static final int PLAYER_PANEL_BOTTOM = GUI_HEIGHT - GAP;
    static final int PLAYER_GRID_X = OUTPUT_GRID_X;
    static final int PLAYER_GRID_Y = 183;
    static final int PLAYER_HOTBAR_Y = 240;

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
    private ExcavatorStyledButton scanButton;
    private ExcavatorStyledButton excavationButton;
    private ExcavatorStyledButton filterButton;
    private final ExcavatorFilterPanel filterPanel;

    private static final int FILTER_BUTTON_MARGIN = 2;
    private static final int FILTER_BUTTON_W = 11;
    private static final int FILTER_BUTTON_H = TITLE_BAR_HEIGHT - FILTER_BUTTON_MARGIN * 2;
    private static final int FILTER_BUTTON_Y = TITLE_BAR_Y + FILTER_BUTTON_MARGIN;
    private static final int SETTINGS_BUTTON_W = 11;
    private static final int SETTINGS_BUTTON_X = GUI_WIDTH - GAP - FILTER_BUTTON_MARGIN - SETTINGS_BUTTON_W;
    private static final int FILTER_BUTTON_X = SETTINGS_BUTTON_X - FILTER_BUTTON_W - 3;



    public ExcavatorScreen(ExcavatorMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = GUI_WIDTH;
        imageHeight = GUI_HEIGHT;
        titleLabelX = 10;
        titleLabelY = 8;
        inventoryLabelY = 1000;
        filterPanel = new ExcavatorFilterPanel(this);
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

        scanButton = addRenderableWidget(ExcavatorStyledButton.text(
                leftPos + ACTION_X, topPos + ACTION_Y, ACTION_BUTTON_WIDTH, ACTION_HEIGHT,
                Component.literal("Scan Area"), button -> pressMenuButton(ExcavatorMenu.BUTTON_START_SCAN)
        ));

        excavationButton = addRenderableWidget(ExcavatorStyledButton.text(
                leftPos + ACTION_X + ACTION_BUTTON_WIDTH + GAP, topPos + ACTION_Y, ACTION_BUTTON_WIDTH, ACTION_HEIGHT,
                Component.literal("Start Excavation"), button -> pressMenuButton(ExcavatorMenu.BUTTON_TOGGLE_EXCAVATION)
        ));

        filterButton = addRenderableWidget(ExcavatorStyledButton.filter(
                leftPos + FILTER_BUTTON_X, topPos + FILTER_BUTTON_Y, FILTER_BUTTON_W, FILTER_BUTTON_H,
                Component.literal("Filter"), button -> filterPanel.toggle(), filterPanel::isOpen,
                menu::isUpgradeConfigurationLocked
        ));
        addRenderableWidget(ExcavatorStyledButton.gear(
                leftPos + SETTINGS_BUTTON_X, topPos + FILTER_BUTTON_Y, SETTINGS_BUTTON_W, FILTER_BUTTON_H,
                Component.literal("Rendering settings"), button -> minecraft.setScreen(new ExcavatorRenderSettingsScreen(this))
        ));

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

    private boolean hasUnsavedSelection(EditBox field) {
        if (!isParsableNumber(field.getValue())) return false;
        try {
            return Integer.parseInt(field.getValue()) != selectionValue(field);
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private int selectionValue(EditBox field) {
        return field == widthField ? menu.getSelectionWidth() : field == heightField ? menu.getSelectionHeight() : menu.getSelectionLength();
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

        int currentValue = selectionValue(field);
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

    void pressMenuButton(int id) {
        commitFocusedField();
        sendMenuButton(id);
    }

    void sendMenuButton(int id) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.gameMode == null) return;
        menu.clickMenuButton(minecraft.player, id);
        minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id);
        updateControls();
    }

    void commitFocusedField() {
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
        if (!filterButton.visible) filterPanel.close();

        excavationButton.active = state == ExcavatorScanState.EXCAVATING || state == ExcavatorScanState.STORAGE_FULL
                || (state == ExcavatorScanState.READY && !overheating && !menu.isRunningLimitReached(menu.getRunningLimitTier()));
        excavationButton.setMessage(Component.literal(overheating && state != ExcavatorScanState.EXCAVATING && state != ExcavatorScanState.STORAGE_FULL ? "Overheated" : switch (state) {
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

        graphics.fill(x + GAP, y + TITLE_BAR_Y, x + imageWidth - GAP, y + TITLE_BAR_Y + TITLE_BAR_HEIGHT, FRAME);
        graphics.fill(x + GAP + 1, y + TITLE_BAR_Y + 1, x + imageWidth - GAP - 1, y + TITLE_BAR_Y + TITLE_BAR_HEIGHT - 1, PANEL_HEADER);

        drawProgress(graphics, x + ACTION_X, y + PROGRESS_Y, ACTION_WIDTH, 5, menu.getScanProgress());
        if (menu.areRunningLimitsEnabled()) drawRunningLimitTile(graphics, x + LIMIT_TILE_X, y + LIMIT_TILE_Y);

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

        String status = font.plainSubstrByWidth(statusText(), Math.round((GUI_WIDTH - 12) / STATUS_SCALE));
        drawCenteredScaledString(graphics, status, GUI_WIDTH / 2.0F, STATUS_Y, statusColor(), STATUS_SCALE);

        drawScaledString(graphics, "OUTPUT", OUTPUT_GRID_X, OUTPUT_PANEL_Y + 5, TEXT, LABEL_SCALE);
        int modPanelY = menu.isFuelSlotEnabled() ? MOD_PANEL_Y_WITH_FUEL : MOD_PANEL_Y_NO_FUEL;
        drawCenteredScaledString(graphics, "MOD", (MOD_PANEL_X + MOD_PANEL_RIGHT) / 2.0F, modPanelY + 5, GOLD, LABEL_SCALE);
        drawScaledString(graphics, "INVENTORY", PLAYER_GRID_X, PLAYER_PANEL_Y + 5, TEXT, LABEL_SCALE);
    }

    private int runningLimitColor() {
        int active = menu.getRunningLimitActive(menu.getRunningLimitTier()), allowed = menu.getRunningLimitAllowed(menu.getRunningLimitTier());
        return active >= allowed ? ERROR : active > 0 && active * 2 >= allowed ? WARNING : GOOD;
    }

    private void drawRunningLimitTile(GuiGraphics graphics, int x, int y) {
        int color = runningLimitColor();
        graphics.fill(x, y, x + LIMIT_TILE_W, y + LIMIT_TILE_H, FRAME);
        graphics.fill(x + 1, y + 1, x + LIMIT_TILE_W - 1, y + LIMIT_TILE_H - 1, PANEL_HEADER);
        graphics.fill(x + 2, y + 2, x + LIMIT_TILE_W - 2, y + LIMIT_TILE_H - 2, color);
    }

    private void renderRunningLimitTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        if (!menu.areRunningLimitsEnabled() || !isInside(mouseX, mouseY, leftPos + LIMIT_TILE_X, topPos + LIMIT_TILE_Y, LIMIT_TILE_W, LIMIT_TILE_H)) return;
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal("Server limits active").withStyle(ChatFormatting.GOLD));
        lines.add(Component.literal("Your running excavators:").withStyle(ChatFormatting.GRAY));
        for (int tier = 0; tier <= 5; tier++) {
            int active = menu.getRunningLimitActive(tier), allowed = menu.getRunningLimitAllowed(tier);
            ChatFormatting color = active >= allowed ? ChatFormatting.RED : active > 0 && active * 2 >= allowed ? ChatFormatting.YELLOW : ChatFormatting.GREEN;
            lines.add(Component.literal("Tier " + tier + ": " + active + "/" + allowed).withStyle(color));
        }
        graphics.setComponentTooltipForNextFrame(font, lines, mouseX, mouseY);
    }

    private void renderExcavationLimitTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        if (!menu.isRunningLimitReached(menu.getRunningLimitTier()) || (menu.getScanState() != ExcavatorScanState.READY || menu.isOverheating())
                || !isInside(mouseX, mouseY, excavationButton.getX(), excavationButton.getY(), excavationButton.getWidth(), excavationButton.getHeight())) return;
        int tier = menu.getRunningLimitTier();
        graphics.setComponentTooltipForNextFrame(font, List.of(
                Component.literal("Running excavator limit reached").withStyle(ChatFormatting.RED),
                Component.literal("Tier " + tier + ": " + menu.getRunningLimitActive(tier) + "/" + menu.getRunningLimitAllowed(tier)).withStyle(ChatFormatting.YELLOW),
                Component.literal("Pause another excavator of this tier first.").withStyle(ChatFormatting.GRAY)), mouseX, mouseY);
    }

    private void drawSlot(GuiGraphics graphics, int x, int y) {
        ExcavatorUiStyle.drawSlot(graphics, x, y);
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
        drawSolarStatusIcon(graphics, x + FUEL_BAR_W - 5, y, menu.getSolarStatus());
    }

    private void drawSolarStatusIcon(GuiGraphics graphics, int x, int y, int status) {
        if (status == ExcavatorSolarManager.STATUS_NONE) return;
        graphics.fill(x, y, x + 5, y + 5, 0xDD111820);
        if (status == ExcavatorSolarManager.STATUS_NIGHT) {
            graphics.fill(x + 1, y, x + 4, y + 1, SOLAR_NIGHT);
            graphics.fill(x, y + 1, x + 2, y + 4, SOLAR_NIGHT);
            graphics.fill(x + 1, y + 4, x + 4, y + 5, SOLAR_NIGHT);
            return;
        }

        int color = status == ExcavatorSolarManager.STATUS_ACTIVE ? SOLAR_ACTIVE : 0xFFB89A55;
        graphics.fill(x + 1, y + 1, x + 4, y + 4, color);
        graphics.fill(x + 2, y, x + 3, y + 5, color);
        graphics.fill(x, y + 2, x + 5, y + 3, color);
        if (status == ExcavatorSolarManager.STATUS_BLOCKED) {
            for (int i = 0; i < 5; i++) graphics.fill(x + i, y + i, x + i + 1, y + i + 1, SOLAR_BLOCKED);
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

    private void drawScaledString(GuiGraphics graphics, String text, float x, float y, int color, float scale) {
        ExcavatorUiStyle.drawScaledString(graphics, font, text, x, y, color, scale);
    }

    private void drawCenteredScaledString(GuiGraphics graphics, String text, float centerX, float y, int color, float scale) {
        ExcavatorUiStyle.drawCenteredScaledString(graphics, font, text, centerX, y, color, scale);
    }

    private void renderLockedUpgradeIndicators(GuiGraphics graphics) {
        graphics.nextStratum();
        int y = topPos + (menu.isFuelSlotEnabled() ? UPGRADE_Y_WITH_FUEL : UPGRADE_Y_NO_FUEL);
        for (int slot = 0; slot < menu.getUpgradeSlotCount(); slot++) {
            if (!menu.isUpgradeSlotLocked(slot)) continue;
            int x = leftPos + UPGRADE_X, sy = y + slot * SLOT_STEP;
            graphics.fill(x, sy, x + 16, sy + 16, LOCKED_OVERLAY);
            ExcavatorUiStyle.drawLock(graphics, x + 9, sy + 8, WARNING);
        }

    }

    private boolean isOverLockedUpgradeLock(double mouseX, double mouseY) {
        if (!menu.isUpgradeConfigurationLocked()) return false;
        int y = topPos + (menu.isFuelSlotEnabled() ? UPGRADE_Y_WITH_FUEL : UPGRADE_Y_NO_FUEL);
        for (int slot = 0; slot < menu.getUpgradeSlotCount(); slot++) {
            if (!menu.isUpgradeSlotLocked(slot)) continue;
            int x = leftPos + UPGRADE_X, sy = y + slot * SLOT_STEP;
            if (isInside(mouseX, mouseY, x + 8, sy + 7, 8, 9)) return true;
        }
        return false;
    }

    private void renderConfigurationLockTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        if (!menu.isUpgradeConfigurationLocked()) return;

        if (filterButton.visible && isInside(mouseX, mouseY, filterButton.getX(), filterButton.getY(),
                filterButton.getWidth(), filterButton.getHeight())) {
            renderLockTooltip(graphics, mouseX, mouseY, "filter");
            return;
        }

        if (isOverLockedUpgradeLock(mouseX, mouseY)) {
            renderLockTooltip(graphics, mouseX, mouseY, "upgrade");
        }
    }

    private void renderLockTooltip(GuiGraphics graphics, int mouseX, int mouseY, String target) {
        graphics.setComponentTooltipForNextFrame(font, List.of(
                Component.literal("Locked while active").withStyle(ChatFormatting.GOLD),
                Component.literal("Pause the excavator and wait for all transports").withStyle(ChatFormatting.GRAY),
                Component.literal("to finish before changing this " + target + ".").withStyle(ChatFormatting.GRAY)
        ), mouseX, mouseY);
    }

    private void renderUnsavedSelectionIcons(GuiGraphics graphics) {
        for (EditBox field : configurationFields) {
            if (!hasUnsavedSelection(field)) continue;
            int x = field.getX() + field.getWidth() - 12;
            int y = field.getY() + 5;
            graphics.fill(x + 3, y, x + 4, y + 1, WARNING);
            graphics.fill(x + 2, y + 1, x + 5, y + 3, WARNING);
            graphics.fill(x + 1, y + 3, x + 6, y + 5, WARNING);
            graphics.fill(x, y + 5, x + 7, y + 7, WARNING);
            graphics.fill(x, y + 7, x + 7, y + 8, WARNING);
            graphics.fill(x + 3, y + 2, x + 4, y + 5, FRAME);
            graphics.fill(x + 3, y + 6, x + 4, y + 7, FRAME);
        }
    }

    private void renderUnsavedSelectionTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        for (EditBox field : configurationFields) {
            if (!hasUnsavedSelection(field)) continue;
            int x = field.getX() + field.getWidth() - 12;
            int y = field.getY() + 5;
            if (!isInside(mouseX, mouseY, x, y, 7, 8)) continue;
            graphics.setComponentTooltipForNextFrame(font, List.of(
                    Component.literal("Unsaved change").withStyle(ChatFormatting.GOLD),
                    Component.literal("Click outside to save it.").withStyle(ChatFormatting.GRAY)
            ), mouseX, mouseY);
            return;
        }
    }

    private void renderEnergyTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = leftPos + ENERGY_BAR_X;
        int y = topPos + ENERGY_BAR_Y;
        if (!isInside(mouseX, mouseY, x, y, ENERGY_BAR_W, ENERGY_BAR_H)) return;

        graphics.setComponentTooltipForNextFrame(
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

    private void renderSolarTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        int status = menu.getSolarStatus();
        if (!menu.isFuelSlotEnabled() || status == ExcavatorSolarManager.STATUS_NONE) return;
        int x = leftPos + FUEL_X - 1 + FUEL_BAR_W - 5, y = topPos + FUEL_BAR_Y;
        if (!isInside(mouseX, mouseY, x, y, 5, 5)) return;
        String text = switch (status) {
            case ExcavatorSolarManager.STATUS_ACTIVE -> "Solar: Active";
            case ExcavatorSolarManager.STATUS_NIGHT -> "Solar: Night";
            default -> "Solar: Blocked";
        };
        graphics.setTooltipForNextFrame(font, Component.literal(text), mouseX, mouseY);
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
            graphics.setComponentTooltipForNextFrame(font, tooltip, mouseX, mouseY);
        }
    }

    private static boolean isInside(double mouseX, double mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    private static String formatEnergy(int value) {
        return String.format(java.util.Locale.ROOT, "%,d", Math.max(0, value));
    }

    int guiLeft() { return leftPos; }
    int guiTop() { return topPos; }
    void clearFilterButtonFocus() {
        if (filterButton != null) filterButton.setFocused(false);
    }

    void renderFilterInventory(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        hoveredSlot = null;
        graphics.pose().pushMatrix();
        graphics.pose().translate(leftPos, topPos);
        for (Slot slot : menu.slots) {
            if (!isPlayerInventorySlot(slot) || !slot.isActive()) continue;
            renderSlot(graphics, slot, mouseX, mouseY);
            if (mouseX >= leftPos + slot.x && mouseX < leftPos + slot.x + 16
                    && mouseY >= topPos + slot.y && mouseY < topPos + slot.y + 16) hoveredSlot = slot;
        }
        if (hoveredSlot != null) graphics.fill(hoveredSlot.x, hoveredSlot.y, hoveredSlot.x + 16, hoveredSlot.y + 16, 0x80FFFFFF);
        graphics.pose().popMatrix();
    }

    void renderFilterInventoryTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        if (hoveredSlot != null) renderTooltip(graphics, mouseX, mouseY);
    }

    boolean isFilterInventoryPosition(double mouseX, double mouseY) {
        for (Slot slot : menu.slots) {
            if (!isPlayerInventorySlot(slot)) continue;
            if (mouseX >= leftPos + slot.x && mouseX < leftPos + slot.x + 16
                    && mouseY >= topPos + slot.y && mouseY < topPos + slot.y + 16) return true;
        }
        return false;
    }

    ItemStack filterInventoryStackAt(double mouseX, double mouseY) {
        for (Slot slot : menu.slots) {
            if (!isPlayerInventorySlot(slot)) continue;
            if (mouseX >= leftPos + slot.x && mouseX < leftPos + slot.x + 16
                    && mouseY >= topPos + slot.y && mouseY < topPos + slot.y + 16) return slot.getItem();
        }
        return ItemStack.EMPTY;
    }

    private static boolean isPlayerInventorySlot(Slot slot) {
        int x = slot.x - PLAYER_GRID_X;
        if (x < 0 || x % SLOT_STEP != 0 || x / SLOT_STEP >= 9) return false;
        int y = slot.y - PLAYER_GRID_Y;
        return y >= 0 && y % SLOT_STEP == 0 && y / SLOT_STEP < 3 || slot.y == PLAYER_HOTBAR_Y;
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
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mouseX = event.x(), mouseY = event.y();
        int button = event.button();
        if (filterPanel.isOpen()) {
            if (filterPanel.mouseClicked(mouseX, mouseY, button, event.hasShiftDown())) return true;
            return super.mouseClicked(event, doubleClick);
        }

        for (EditBox field : configurationFields) {
            if (field.isFocused() && !field.isMouseOver(mouseX, mouseY) && isParsableNumber(field.getValue())) {
                sendSelectionValue(field, field.getValue());
                break;
            }
        }

        boolean result = super.mouseClicked(event, doubleClick);
        for (EditBox field : configurationFields) {
            if (!field.isFocused() && isParsableNumber(field.getValue())) sendSelectionValue(field, field.getValue());
        }
        return result;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        double mouseX = event.x(), mouseY = event.y();
        if (filterPanel.isOpen() && !isFilterInventoryPosition(mouseX, mouseY)) return true;
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        double mouseX = event.x(), mouseY = event.y();
        if (filterPanel.isOpen() && !isFilterInventoryPosition(mouseX, mouseY)) return true;
        return super.mouseReleased(event);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int keyCode = event.key();
        if (filterPanel.isOpen()) {
            if (keyCode == 256 || minecraft.options.keyInventory.matches(event)) onClose();
            return true;
        }

        boolean handled = super.keyPressed(event);
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
        if (!filterPanel.isOpen()) {
            super.render(graphics, mouseX, mouseY, partialTick);
            renderLockedUpgradeIndicators(graphics);
            renderUnsavedSelectionIcons(graphics);

            if (!isOverLockedUpgradeLock(mouseX, mouseY)) renderTooltip(graphics, mouseX, mouseY);
            renderEmptySlotTooltip(graphics, mouseX, mouseY);
            renderEnergyTooltip(graphics, mouseX, mouseY);
            renderRunningLimitTooltip(graphics, mouseX, mouseY);
            renderExcavationLimitTooltip(graphics, mouseX, mouseY);
            renderSolarTooltip(graphics, mouseX, mouseY);
            renderUnsavedSelectionTooltip(graphics, mouseX, mouseY);
            renderConfigurationLockTooltip(graphics, mouseX, mouseY);
        } else {
            super.render(graphics, -10000, -10000, partialTick);
            graphics.nextStratum();
            renderBlurredBackground(graphics);
            graphics.fill(0, 0, width, height, 0x44000000);
            filterPanel.render(graphics, font, mouseX, mouseY, partialTick);
        }

        ItemStack carried = menu.getCarried();
        if (filterPanel.isOpen() && !carried.isEmpty()) {
            graphics.nextStratum();
            graphics.renderItem(carried, mouseX - 8, mouseY - 8);
            graphics.renderItemDecorations(font, carried, mouseX - 8, mouseY - 8);
        }

        if (!filterPanel.isOpen()) {
            Component conflict = carried.isEmpty() ? null : menu.getUpgradeConflictMessage(carried);
            if (conflict != null && isOverUpgradeSlot(mouseX, mouseY)
                    && !isOverLockedUpgradeLock(mouseX, mouseY)) {
                graphics.setTooltipForNextFrame(font, conflict.copy().withStyle(ChatFormatting.RED), mouseX, mouseY);
            }
        }
    }
}
