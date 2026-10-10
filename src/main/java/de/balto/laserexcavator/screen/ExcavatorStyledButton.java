package de.balto.laserexcavator.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.util.function.BooleanSupplier;

import static de.balto.laserexcavator.screen.ExcavatorUiStyle.*;

/**
 * This class contains this mods button design and functionality.
 */

final class ExcavatorStyledButton extends Button {
    private final BooleanSupplier selected;
    private final BooleanSupplier locked;
    private final Icon icon;

    static ExcavatorStyledButton text(int x, int y, int w, int h, Component text, OnPress press) {
        return new ExcavatorStyledButton(x, y, w, h, text, press, () -> false, () -> false, Icon.NONE);
    }

    static ExcavatorStyledButton text(int x, int y, int w, int h, Component text, OnPress press, BooleanSupplier selected) {
        return new ExcavatorStyledButton(x, y, w, h, text, press, selected, () -> false, Icon.NONE);
    }

    static ExcavatorStyledButton gear(int x, int y, int w, int h, Component narration, OnPress press) {
        return new ExcavatorStyledButton(x, y, w, h, narration, press, () -> false, () -> false, Icon.GEAR);
    }

    static ExcavatorStyledButton filter(int x, int y, int w, int h, Component narration, OnPress press, BooleanSupplier selected, BooleanSupplier locked) {
        return new ExcavatorStyledButton(x, y, w, h, narration, press, selected, locked, Icon.FILTER);
    }

    private ExcavatorStyledButton(int x, int y, int w, int h, Component text, OnPress press, BooleanSupplier selected, BooleanSupplier locked, Icon icon) {
        super(Button.builder(text, press).bounds(x, y, w, h));
        this.selected = selected;
        this.locked = locked;
        this.icon = icon;
    }

    @Override
    protected void renderContents(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        boolean chosen = selected.getAsBoolean(), iconButton = icon != Icon.NONE, lockedNow = locked.getAsBoolean();
        boolean hovered = iconButton ? isHovered() : isHoveredOrFocused();
        int x = getX(), y = getY(), fill = !active ? BUTTON_DISABLED : chosen ? BUTTON_SELECTED : hovered ? BUTTON_HOVER : BUTTON_BG;
        ExcavatorUiStyle.drawButton(graphics, x, y, getWidth(), getHeight(), active, hovered, chosen, !iconButton);

        int color = lockedNow ? WARNING : !active ? BUTTON_DISABLED_TEXT : chosen || hovered ? ACCENT : TEXT;
        int dy = chosen ? 1 : 0;
        if (lockedNow) ExcavatorUiStyle.drawLock(graphics, x + (getWidth() - 6) / 2, y + (getHeight() - 7) / 2, color);
        else if (icon == Icon.GEAR) drawGear(graphics, color, fill);
        else if (icon == Icon.FILTER) drawFilter(graphics, color);
        else graphics.drawCenteredString(Minecraft.getInstance().font, getMessage(), x + getWidth() / 2, y + (getHeight() - 8) / 2 + dy, color);
    }

    private void drawGear(GuiGraphics graphics, int color, int fill) {
        int cx = getX() + getWidth() / 2, cy = getY() + getHeight() / 2;
        graphics.fill(cx - 1, cy - 2, cx + 2, cy + 3, color);
        graphics.fill(cx - 2, cy - 1, cx + 3, cy + 2, color);
        graphics.fill(cx, cy, cx + 1, cy + 1, fill);
    }

    private void drawFilter(GuiGraphics graphics, int color) {
        int cx = getX() + getWidth() / 2, cy = getY() + getHeight() / 2;
        graphics.fill(cx - 2, cy - 2, cx + 3, cy - 1, color);
        graphics.fill(cx - 1, cy - 1, cx + 2, cy, color);
        graphics.fill(cx, cy, cx + 1, cy + 3, color);
    }

    private enum Icon { NONE, GEAR, FILTER }
}
