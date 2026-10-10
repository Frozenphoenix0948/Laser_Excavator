package de.balto.laserexcavator.screen;

import de.balto.laserexcavator.block.blockentities.ExcavatorBlockEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import java.util.List;

import static de.balto.laserexcavator.screen.ExcavatorUiStyle.*;

/**
 * This class contains the panel for the filter mene.
 */

final class ExcavatorFilterPanel {
    private static final int W = 195, HEADER_H = 20;
    private static final int GRID_X = 12, GRID_Y = 38, GRID_STEP = 19;
    private static final int ACTION_X = 108, ACTION_W = 72, ACTION_H = 16;
    private static final int COPY_Y = 40, PASTE_Y = 62, MODE_Y = 84;
    private static final int SHADOW = 0x88000000, FILTER_HOVER = 0xFF354656, FILTER_DISABLED = 0xFF202832;
    private static final Block[] COPIED_FILTER = new Block[ExcavatorBlockEntity.MAX_FILTER_SLOTS];
    private static int copiedCapacity;
    private static boolean copiedWhitelist;

    private final ExcavatorScreen screen;
    private boolean open;

    ExcavatorFilterPanel(ExcavatorScreen screen) { this.screen = screen; }

    boolean isOpen() { return open; }
    void toggle() {
        if (open) close();
        else open = true;
    }
    void close() {
        open = false;
        screen.clearFilterButtonFocus();
    }

    private ExcavatorMenu menu() { return screen.getExcavatorMenu(); }
    private int x() { return screen.guiLeft() + 5; }
    private int y() { return screen.guiTop() + 40; }
    private int bottom() { return screen.guiTop() + ExcavatorScreen.PLAYER_PANEL_BOTTOM; }
    private int inventoryX() { return screen.guiLeft() + ExcavatorScreen.PLAYER_GRID_X; }
    private int inventoryY() { return screen.guiTop() + ExcavatorScreen.PLAYER_GRID_Y; }
    private int hotbarY() { return screen.guiTop() + ExcavatorScreen.PLAYER_HOTBAR_Y; }

    void render(GuiGraphics graphics, Font font, int mouseX, int mouseY, float partialTick) {
        int x = x(), y = y(), bottom = bottom(), capacity = menu().getFilterCapacity();

        graphics.fill(x + 4, y + 4, x + W + 4, bottom + 4, SHADOW);
        graphics.fill(x, y, x + W, bottom, FRAME);
        graphics.fill(x + 2, y + 2, x + W - 2, bottom - 2, PANEL_BG);
        graphics.fill(x + 2, y + 2, x + W - 2, y + HEADER_H, PANEL_HEADER);
        graphics.fill(x + 2, y + HEADER_H, x + W - 2, y + HEADER_H + 1, ACCENT);

        graphics.drawString(font, "Block Filter", x + 8, y + 7, TEXT, false);
        drawClose(graphics, x + W - 15, y + 4, mouseX, mouseY);
        ExcavatorUiStyle.drawScaledString(graphics, font, capacity + "/" + ExcavatorBlockEntity.MAX_FILTER_SLOTS, x + W - 39, y + 8, MUTED, .68F);

        ExcavatorUiStyle.drawCenteredScaledString(graphics, font, "FILTER SLOTS", x + GRID_X + 34.5F, y + 27, TEXT, .62F);
        ExcavatorUiStyle.drawCenteredScaledString(graphics, font, "ACTIONS", x + ACTION_X + ACTION_W / 2F, y + 27, TEXT, .62F);
        graphics.fill(x + 96, y + 29, x + 97, y + 116, DIVIDER);

        for (int slot = 0; slot < ExcavatorBlockEntity.MAX_FILTER_SLOTS; slot++) {
            int sx = x + GRID_X + slot % 4 * GRID_STEP, sy = y + GRID_Y + slot / 4 * GRID_STEP;
            boolean active = slot < capacity, hovered = active && inside(mouseX, mouseY, sx, sy, 16, 16);
            drawFilterSlot(graphics, sx, sy, active, hovered);
            if (!active) continue;
            Block block = menu().getFilterBlock(slot);
            if (block != null) {
                ItemStack stack = block.asItem().getDefaultInstance();
                if (!stack.isEmpty()) graphics.renderItem(stack, sx, sy);
            }
        }

        boolean editable = !menu().isUpgradeConfigurationLocked();
        drawButton(graphics, font, x + ACTION_X, y + COPY_Y, ACTION_W, ACTION_H, "Copy", true,
                inside(mouseX, mouseY, x + ACTION_X, y + COPY_Y, ACTION_W, ACTION_H));
        drawButton(graphics, font, x + ACTION_X, y + PASTE_Y, ACTION_W, ACTION_H, "Paste", editable && copiedCapacity > 0,
                inside(mouseX, mouseY, x + ACTION_X, y + PASTE_Y, ACTION_W, ACTION_H));
        drawModeButton(graphics, font, x + ACTION_X, y + MODE_Y, ACTION_W, ACTION_H, editable,
                inside(mouseX, mouseY, x + ACTION_X, y + MODE_Y, ACTION_W, ACTION_H));

        String status = editable ? menu().isFilterWhitelist() ? "Only listed blocks are mined" : "Listed blocks are skipped" : "Locked while running";

        int invTop = screen.guiTop() + ExcavatorScreen.PLAYER_PANEL_Y;
        float statusScale = .58F, statusTop = y + MODE_Y + ACTION_H + 2;
        float statusY = (statusTop + invTop - font.lineHeight * statusScale) * .5F + 7;
        ExcavatorUiStyle.drawCenteredScaledString(graphics, font, status, x + W / 2F, statusY, editable ? TEXT : WARNING, statusScale);

        graphics.fill(x + 2, invTop, x + W - 2, invTop + 1, DIVIDER);
        ExcavatorUiStyle.drawScaledString(graphics, font, "INVENTORY", inventoryX(), invTop + 5, TEXT, LABEL_SCALE);
        drawInventoryBackground(graphics);
        screen.renderFilterInventory(graphics, mouseX, mouseY, partialTick);

        renderTooltip(graphics, font, mouseX, mouseY);
        screen.renderFilterInventoryTooltip(graphics, mouseX, mouseY);
    }

    private void drawInventoryBackground(GuiGraphics graphics) {
        int x = inventoryX(), y = inventoryY();
        for (int row = 0; row < 3; row++) for (int col = 0; col < 9; col++)
            ExcavatorUiStyle.drawSlot(graphics, x + col * ExcavatorScreen.SLOT_STEP, y + row * ExcavatorScreen.SLOT_STEP);
        for (int col = 0; col < 9; col++)
            ExcavatorUiStyle.drawSlot(graphics, x + col * ExcavatorScreen.SLOT_STEP, hotbarY());
    }

    private static void drawFilterSlot(GuiGraphics graphics, int x, int y, boolean active, boolean hovered) {
        graphics.fill(x - 1, y - 1, x + 17, y + 17, hovered ? ACCENT : FRAME);
        graphics.fill(x, y, x + 16, y + 16, active ? hovered ? FILTER_HOVER : SLOT_INNER : FILTER_DISABLED);
        if (!active) graphics.fill(x + 3, y + 7, x + 13, y + 9, FRAME);
    }

    private void drawModeButton(GuiGraphics graphics, Font font, int x, int y, int w, int h, boolean active, boolean hovered) {
        boolean whitelist = menu().isFilterWhitelist();
        drawButton(graphics, font, x, y, w, h, whitelist ? "Whitelist" : "Blacklist", active, hovered);
    }

    private static void drawButton(GuiGraphics graphics, Font font, int x, int y, int w, int h, String text, boolean active, boolean hovered) {
        ExcavatorUiStyle.drawButton(graphics, x, y, w, h, active, hovered, false, true);
        graphics.drawCenteredString(font, text, x + w / 2, y + 4, active ? hovered ? ACCENT : TEXT : BUTTON_DISABLED_TEXT);
    }

    private static void drawClose(GuiGraphics graphics, int x, int y, int mouseX, int mouseY) {
        boolean hovered = inside(mouseX, mouseY, x, y, 11, 11);
        ExcavatorUiStyle.drawButton(graphics, x, y, 11, 11, true, hovered, false, false);
        int color = hovered ? ACCENT : TEXT;
        for (int i = 0; i < 5; i++) {
            graphics.fill(x + 3 + i, y + 3 + i, x + 4 + i, y + 4 + i, color);
            graphics.fill(x + 7 - i, y + 3 + i, x + 8 - i, y + 4 + i, color);
        }
    }

    private void renderTooltip(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
        int x = x(), y = y(), capacity = menu().getFilterCapacity();
        for (int slot = 0; slot < capacity; slot++) {
            int sx = x + GRID_X + slot % 4 * GRID_STEP, sy = y + GRID_Y + slot / 4 * GRID_STEP;
            if (!inside(mouseX, mouseY, sx, sy, 16, 16)) continue;
            Block block = menu().getFilterBlock(slot);
            if (block == null) graphics.setComponentTooltipForNextFrame(font, List.of(
                    Component.literal("Filter slot").withStyle(ChatFormatting.GOLD),
                    Component.literal("Hold a block and click, or Shift-click it in your inventory.").withStyle(ChatFormatting.GRAY)
            ), mouseX, mouseY);
            else {
                ItemStack stack = block.asItem().getDefaultInstance();
                if (!stack.isEmpty()) graphics.setTooltipForNextFrame(font, stack, mouseX, mouseY);
            }
            return;
        }
    }

    boolean mouseClicked(double mouseX, double mouseY, int button, boolean shiftDown) {
        int x = x(), y = y();
        if (!inside(mouseX, mouseY, x, y, W, bottom() - y)) {
            if (button == 0 || button == 1) close();
            return true;
        }

        if (inside(mouseX, mouseY, x + W - 15, y + 4, 11, 11)) {
            if (button == 0 || button == 1) close();
            return true;
        }

        if (screen.isFilterInventoryPosition(mouseX, mouseY)) {
            if (button == 0 && shiftDown) {
                addInventoryBlock(screen.filterInventoryStackAt(mouseX, mouseY));
                return true;
            }
            return false;
        }
        if (button != 0) return true;

        if (inside(mouseX, mouseY, x + ACTION_X, y + COPY_Y, ACTION_W, ACTION_H)) {
            copy();
            return true;
        }
        if (inside(mouseX, mouseY, x + ACTION_X, y + PASTE_Y, ACTION_W, ACTION_H)) {
            paste();
            return true;
        }
        if (inside(mouseX, mouseY, x + ACTION_X, y + MODE_Y, ACTION_W, ACTION_H)) {
            if (!menu().isUpgradeConfigurationLocked()) screen.pressMenuButton(ExcavatorMenu.BUTTON_TOGGLE_FILTER_MODE);
            return true;
        }

        int capacity = menu().getFilterCapacity();
        for (int slot = 0; slot < capacity; slot++) {
            int sx = x + GRID_X + slot % 4 * GRID_STEP, sy = y + GRID_Y + slot / 4 * GRID_STEP;
            if (inside(mouseX, mouseY, sx, sy, 16, 16)) {
                screen.pressMenuButton(ExcavatorMenu.BUTTON_FILTER_SLOT_BASE + slot);
                return true;
            }
        }
        return true;
    }

    private void addInventoryBlock(ItemStack stack) {
        if (menu().isUpgradeConfigurationLocked() || !(stack.getItem() instanceof BlockItem blockItem)) return;
        Block block = blockItem.getBlock();
        int capacity = menu().getFilterCapacity();
        for (int slot = 0; slot < capacity; slot++) {
            if (menu().getFilterBlock(slot) == block) return;
        }
        for (int slot = 0; slot < capacity; slot++) {
            if (menu().getFilterBlock(slot) == null) {
                screen.pressMenuButton(ExcavatorMenu.encodeFilterBlockButton(slot, block));
                return;
            }
        }
    }

    private void copy() {
        int capacity = menu().getFilterCapacity();
        for (int slot = 0; slot < ExcavatorBlockEntity.MAX_FILTER_SLOTS; slot++)
            COPIED_FILTER[slot] = slot < capacity ? menu().getFilterBlock(slot) : null;
        copiedCapacity = capacity;
        copiedWhitelist = menu().isFilterWhitelist();
    }

    private void paste() {
        if (copiedCapacity <= 0 || menu().isUpgradeConfigurationLocked()) return;
        screen.commitFocusedField();
        if (menu().isFilterWhitelist() != copiedWhitelist) screen.sendMenuButton(ExcavatorMenu.BUTTON_TOGGLE_FILTER_MODE);

        int capacity = menu().getFilterCapacity();
        for (int slot = 0; slot < capacity; slot++) screen.sendMenuButton(ExcavatorMenu.encodeFilterBlockButton(slot, null));
        for (int slot = 0; slot < capacity; slot++) {
            Block block = slot < copiedCapacity ? COPIED_FILTER[slot] : null;
            if (block != null) screen.sendMenuButton(ExcavatorMenu.encodeFilterBlockButton(slot, block));
        }
    }

    private static boolean inside(double mouseX, double mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }
}
