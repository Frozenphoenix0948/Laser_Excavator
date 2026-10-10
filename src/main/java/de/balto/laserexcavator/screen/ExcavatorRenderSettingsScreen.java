package de.balto.laserexcavator.screen;

import de.balto.laserexcavator.config.LaserExcavatorClientConfig;
import de.balto.laserexcavator.config.LaserExcavatorClientConfig.RenderPreset;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import static de.balto.laserexcavator.screen.ExcavatorUiStyle.*;

/**
 * This class contains the screen oif the general render settings.
 */

public final class ExcavatorRenderSettingsScreen extends Screen {
    private static final RenderPreset[] PRESETS = {RenderPreset.MINIMAL, RenderPreset.REDUCED, RenderPreset.NORMAL, RenderPreset.ENHANCED, RenderPreset.MAXIMUM};
    private final Screen parent;

    public ExcavatorRenderSettingsScreen(Screen parent) {
        super(Component.literal("Excavator Rendering"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int panelW = Math.min(width - 24, 390), left = (width - panelW) / 2, top = Math.max(12, height / 2 - 86);
        int gap = 4, buttonW = (panelW - 24 - gap * 4) / 5, x = left + 12, y = top + 48;
        for (int i = 0; i < PRESETS.length; i++) {
            RenderPreset preset = PRESETS[i];
            addRenderableWidget(ExcavatorStyledButton.text(x + i * (buttonW + gap), y, buttonW, 20,
                    Component.literal(preset.label()), b -> select(preset), () -> LaserExcavatorClientConfig.renderPreset() == preset));
        }
        int by = top + 132;
        addRenderableWidget(ExcavatorStyledButton.text(left + 12, by, 92, 20, Component.literal("Advanced..."), b -> minecraft.setScreen(new ExcavatorRenderAdvancedScreen(this))));
        addRenderableWidget(ExcavatorStyledButton.text(left + panelW - 76, by, 64, 20, Component.literal("Done"), b -> onClose()));
    }

    private void select(RenderPreset preset) {
        LaserExcavatorClientConfig.applyRenderPreset(preset);
        LaserExcavatorClientConfig.saveRendering();
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {}

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(graphics, mouseX, mouseY, partialTick);
        int panelW = Math.min(width - 24, 390), left = (width - panelW) / 2, top = Math.max(12, height / 2 - 86), bottom = top + 164;
        graphics.fill(0, 0, width, height, 0x33000000);
        graphics.fill(left, top, left + panelW, bottom, FRAME);
        graphics.fill(left + 1, top + 1, left + panelW - 1, bottom - 1, SCREEN_BG);
        graphics.fill(left + 1, top + 1, left + panelW - 1, top + 25, PANEL_HEADER);
        graphics.fill(left + 9, top + 78, left + panelW - 9, top + 122, PANEL_INNER);
        graphics.drawCenteredString(font, title, width / 2, top + 8, TEXT);
        graphics.drawCenteredString(font, "Client-side visual settings", width / 2, top + 30, MUTED);
        RenderPreset preset = LaserExcavatorClientConfig.renderPreset();
        graphics.drawCenteredString(font, preset == RenderPreset.CUSTOM ? "Custom" : preset.label(), width / 2, top + 87, preset == RenderPreset.CUSTOM ? GOLD : ACCENT);
        graphics.drawCenteredString(font, description(preset), width / 2, top + 102, MUTED);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private String description(RenderPreset preset) {
        return switch (preset) {
            case MINIMAL -> "Renders transports only as dots - best performance for large setups";
            case REDUCED -> "Reduces transport detail and rendering distances";
            case NORMAL -> "Balances visual quality and rendering performance";
            case ENHANCED -> "Increases transport detail and rendering distances";
            case MAXIMUM -> "Greatly increases transport detail and rendering distances";
            case CUSTOM -> "Custom settings are used";
        };
    }

    void closeExcavatorMenu() {
        if (parent instanceof ExcavatorScreen) parent.onClose();
        else minecraft.setScreen(null);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int keyCode = event.key();
        if (keyCode == 256 || minecraft.options.keyInventory.matches(event)) {
            closeExcavatorMenu();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void onClose() { minecraft.setScreen(parent); }
}
