package de.balto.laserexcavator.screen;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * This class contains mostly constants and basic functions used by other screen elements.
 */

final class ExcavatorUiStyle {
    static final int FRAME = 0xFF111820;
    static final int SCREEN_BG = 0xF01B2430;
    static final int PANEL_BG = 0xEE1B2430;
    static final int PANEL_INNER = 0xEE27323F;
    static final int PANEL_HEADER = 0xFF303B49;
    static final int SLOT_INNER = 0xFF343F4D;

    static final int TEXT = 0xFFF1EBDD;
    static final int MUTED = 0xFFADB5C1;
    static final int ACCENT = 0xFF76D7FF;
    static final int GOLD = 0xFFFFD47A;
    static final int GOOD = 0xFF7CFF9A;
    static final int WARNING = 0xFFFFC96B;
    static final int ERROR = 0xFFFF7A7A;

    static final int DIVIDER = 0xFF465362;
    static final int ACCENT_DIM = 0x664B9AB8;
    static final int BUTTON_BG = 0xEE202A35;
    static final int BUTTON_HOVER = 0xEE2A3744;
    static final int BUTTON_SELECTED = 0xEE263A48;
    static final int BUTTON_TOP = 0xFF455461;
    static final int BUTTON_SHADOW = 0xFF090D12;
    static final int BUTTON_BOTTOM = 0xFF111821;
    static final int BUTTON_PRESSED = 0xFF14202A;
    static final int BUTTON_DISABLED = 0xCC202832;
    static final int BUTTON_DISABLED_TEXT = 0xFF77818E;
    static final int LOCKED_OVERLAY = 0x66111820;

    static final float TITLE_SCALE = 0.85F;
    static final float LABEL_SCALE = 0.80F;
    static final float STATUS_SCALE = 0.82F;

    private ExcavatorUiStyle() {}

    static void drawSlot(GuiGraphics graphics, int x, int y) {
        graphics.fill(x - 1, y - 1, x + 17, y + 17, FRAME);
        graphics.fill(x, y, x + 16, y + 16, SLOT_INNER);
    }

    static void drawButton(GuiGraphics graphics, int x, int y, int w, int h,
                           boolean active, boolean hovered, boolean selected, boolean shadow) {
        int bodyBottom = shadow ? y + h - 1 : y + h;
        int fill = !active ? BUTTON_DISABLED : selected ? BUTTON_SELECTED : hovered ? BUTTON_HOVER : BUTTON_BG;
        int edge = active && (selected || hovered) ? ACCENT : FRAME;

        if (shadow) graphics.fill(x, y + 1, x + w, y + h, BUTTON_SHADOW);
        graphics.fill(x, y, x + w, bodyBottom, edge);
        graphics.fill(x + 1, y + 1, x + w - 1, bodyBottom - 1, fill);

        if (selected) {
            graphics.fill(x + 1, y + 1, x + w - 1, y + 2, BUTTON_PRESSED);
            graphics.fill(x + 1, y + 1, x + 2, bodyBottom - 1, BUTTON_PRESSED);
            graphics.fill(x + 2, bodyBottom - 2, x + w - 1, bodyBottom - 1, BUTTON_TOP);
            graphics.fill(x + w - 2, y + 2, x + w - 1, bodyBottom - 1, BUTTON_TOP);
        } else {
            graphics.fill(x + 2, y + 1, x + w - 2, y + 2, BUTTON_TOP);
            graphics.fill(x + 1, y + 2, x + 2, bodyBottom - 2, BUTTON_TOP);
            graphics.fill(x + 2, bodyBottom - 2, x + w - 1, bodyBottom - 1, BUTTON_BOTTOM);
            graphics.fill(x + w - 2, y + 2, x + w - 1, bodyBottom - 1, BUTTON_BOTTOM);
        }
    }

    static void drawLock(GuiGraphics graphics, int x, int y, int color) {
        graphics.fill(x + 1, y + 2, x + 6, y + 7, color);
        graphics.fill(x + 2, y, x + 5, y + 1, color);
        graphics.fill(x + 1, y + 1, x + 2, y + 3, color);
        graphics.fill(x + 5, y + 1, x + 6, y + 3, color);
        graphics.fill(x + 3, y + 4, x + 4, y + 6, FRAME);
    }

    static void drawScaledString(GuiGraphics graphics, Font font, String text, float x, float y, int color, float scale) {
        graphics.pose().pushMatrix();
        graphics.pose().translate(x, y);
        graphics.pose().scale(scale, scale);
        graphics.drawString(font, text, 0, 0, color, false);
        graphics.pose().popMatrix();
    }

    static void drawCenteredScaledString(GuiGraphics graphics, Font font, String text, float centerX, float y, int color, float scale) {
        drawScaledString(graphics, font, text, centerX - font.width(text) * scale * .5F, y, color, scale);
    }
}
