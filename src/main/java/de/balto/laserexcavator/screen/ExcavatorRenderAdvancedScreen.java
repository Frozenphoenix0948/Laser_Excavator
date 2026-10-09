package de.balto.laserexcavator.screen;

import de.balto.laserexcavator.config.LaserExcavatorClientConfig;
import de.balto.laserexcavator.config.LaserExcavatorClientConfig.RenderPreset;
import de.balto.laserexcavator.config.LaserExcavatorClientConfig.TransportRenderMode;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

import static de.balto.laserexcavator.screen.ExcavatorUiStyle.*;

/**
 * This class contains the screen used for the advanced settings.
 */

public final class ExcavatorRenderAdvancedScreen extends Screen {
    private static final List<Group> GROUPS = List.of(
            group("General", 0,
                    ints("Render distance", "Distance in blocks where all rendering is stopped.", Impact.HIGH, LaserExcavatorClientConfig.RENDER_DISTANCE, 16, 2048),
                    ints("Ingestion budget", "Time the client is allowed to use per tick to process incoming transport packages. 0 disables it.", Impact.MEDIUM, LaserExcavatorClientConfig.VISUAL_INGESTION_BUDGET_MICROS, 0, 50000)),
            group("Lasers", 0,
                    ints("Laser lifetime", "The time a laser is rendered. Does not change mining speed or FE use.", Impact.MEDIUM, LaserExcavatorClientConfig.LASER_VISUAL_DURATION_TICKS, 1, 200),
                    ints("Max lasers", "Maximum number of lasers rendered per excavator.", Impact.MEDIUM, LaserExcavatorClientConfig.MAX_LASERS_PER_EXCAVATOR, 1, 4096),
                    doubles("Mid LOD", "Distance where lasers switch from 3D geometry to a camera-facing three-line beam.", Impact.LOW, LaserExcavatorClientConfig.LASER_MID_LOD_DISTANCE, 16, 2048),
                    doubles("Far LOD", "Distance where lasers switch from a camera-facing three-line beam to one line.", Impact.MEDIUM, LaserExcavatorClientConfig.LASER_FAR_LOD_DISTANCE, 16, 4096)),
            group("Transport Visuals", 0,
                    ints("Min block light", "Minimum block-light level used for transport lighting. Changes how bright transport blocks are", Impact.NONE, LaserExcavatorClientConfig.MIN_TRANSPORT_BLOCK_LIGHT, 0, 15),
                    doubles("Block size", "Half-size of transported blocks. Larger values make cubes and block billboards bigger.", Impact.NONE, LaserExcavatorClientConfig.BLOCK_VISUAL_HALF_SIZE, .01, 2),
                    doubles("Item size", "Half-size of non-block item billboards. Larger values make transported items bigger.", Impact.NONE, LaserExcavatorClientConfig.ITEM_VISUAL_HALF_SIZE, .01, 2),
                    doubles("Marker size", "Half-size of the large untextured transport marker. Smaller markers derive their size from this value.", Impact.NONE, LaserExcavatorClientConfig.TRANSPORT_MARKER_HALF_SIZE, .01, 1),
                    doubles("Cube distance", "Distance where transported blocks switch from textured cubes to textured billboards.", Impact.HIGH, LaserExcavatorClientConfig.BILLBOARD_DISTANCE, 1, 512)),
            group("Transports", 1,
                    ints("Max transports", "Maximum number of transports rendered per excavator. New transport renderings are skipped while the limit is full.", Impact.HIGH, LaserExcavatorClientConfig.MAX_TRANSPORTS_PER_EXCAVATOR, 16, 65536),
                    doubles("Large marker dist.", "Distance where transported blocks switch from textured billboards to large untextured markers.", Impact.HIGH, LaserExcavatorClientConfig.TRANSPORT_LARGE_MARKER_DISTANCE, 16, 2048),
                    doubles("Small marker dist.", "Distance where large block markers and non-block item billboards switch to smaller markers.", Impact.MEDIUM, LaserExcavatorClientConfig.TRANSPORT_SMALL_MARKER_DISTANCE, 16, 2048),
                    doubles("Batch distance", "Distance where individual transports start being batched. Instead of rendering each transport multiple are represented by one.", Impact.HIGH, LaserExcavatorClientConfig.TRANSPORT_BATCH_DISTANCE, 16, 2048),
                    ints("Batch max", "Maximum representative transport markers per excavator at the start of the batching range.", Impact.HIGH, LaserExcavatorClientConfig.TRANSPORT_BATCH_MAX_MARKERS, 1, 2048),
                    ints("Batch min", "Minimum representative transport markers just before the maximum transport render distance.", Impact.HIGH, LaserExcavatorClientConfig.TRANSPORT_BATCH_MIN_MARKERS, 1, 1024),
                    doubles("Batch falloff", "Batching becomes more aggressive with range, this value decides how aggressive. Higher values reduce marker count more aggressively with distance.", Impact.MEDIUM, LaserExcavatorClientConfig.TRANSPORT_BATCH_FALLOFF_EXPONENT, .25, 8),
                    doubles("Transport range", "Maximum camera distance for transport rendering.", Impact.HIGH, LaserExcavatorClientConfig.TRANSPORT_MAX_RENDER_DISTANCE, 16, 4096)),
            group("Force Field", 1,
                    ints("Grid spacing", "Base spacing in blocks between force-field grid lines. Medium and far LODs increase it automatically.", Impact.LOW, LaserExcavatorClientConfig.FORCE_FIELD_GRID_SPACING, 1, 64),
                    doubles("Pillar outer", "Base offset of the four outer lines from the pillar center forming each force-field corner pillar.", Impact.NONE, LaserExcavatorClientConfig.PILLAR_OUTER_RADIUS, .005, 2),
                    doubles("Pillar core", "Base offset of the inner lines from the pillar center of full-detail force-field corner pillars.", Impact.NONE, LaserExcavatorClientConfig.PILLAR_CORE_RADIUS, .001, 1))
    );
    private static final List<Setting> SETTINGS = GROUPS.stream().flatMap(g -> g.settings.stream()).toList();

    private final Screen parent;
    private final List<EditBox> fields = new ArrayList<>();
    private final int[] rowX = new int[SETTINGS.size()], rowY = new int[SETTINGS.size()], groupY = new int[GROUPS.size()];
    private final double[] original = new double[SETTINGS.size()];
    private int scroll, maxScroll, contentTop, contentBottom, panelLeft, panelWidth, columnWidth;
    private boolean draggingScroll;
    private int dragOffset;
    private RenderPreset originalPreset;
    private TransportRenderMode originalTransportMode;
    private boolean originalBreakEffects, snapshot;
    private ExcavatorStyledButton breakEffectsButton, transportModeButton;

    public ExcavatorRenderAdvancedScreen(Screen parent) {
        super(Component.literal("Advanced Rendering"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        if (!snapshot) {
            for (int i = 0; i < SETTINGS.size(); i++) original[i] = SETTINGS.get(i).get.getAsDouble();
            originalPreset = LaserExcavatorClientConfig.renderPreset();
            originalBreakEffects = LaserExcavatorClientConfig.EMIT_VANILLA_BREAK_EFFECTS.get();
            originalTransportMode = LaserExcavatorClientConfig.transportRenderMode();
            snapshot = true;
        }
        fields.clear();
        int top = 8;
        panelWidth = Math.min(width - 20, 560);
        panelLeft = (width - panelWidth) / 2;
        columnWidth = (panelWidth - 26) / 2;
        contentTop = top + 28;
        contentBottom = height - 36;
        int[] y = {top + 31, top + 31};
        int settingIndex = 0;
        for (int g = 0; g < GROUPS.size(); g++) {
            Group group = GROUPS.get(g);
            int col = group.column, x = panelLeft + 15 + col * (columnWidth + 6);
            groupY[g] = y[col];
            y[col] += 11;
            for (Setting setting : group.settings) {
                int index = settingIndex++;
                rowX[index] = x;
                rowY[index] = y[col];
                EditBox box = new EditBox(font, x + columnWidth - 76, y[col], 62, 14, Component.literal(setting.name));
                box.setValue(format(setting));
                box.setMaxLength(10);
                box.setFilter(setting.integer ? s -> s.chars().allMatch(Character::isDigit) : s -> s.isEmpty() || s.matches("\\d*(\\.\\d*)?"));
                box.setResponder(value -> update(index, box, value));
                fields.add(addRenderableWidget(box));
                y[col] += 17;
            }
            y[col] += 5;
        }
        maxScroll = Math.max(0, Math.max(y[0], y[1]) - contentBottom + 1);
        scroll = Math.min(scroll, maxScroll);
        updateFieldPositions();
        int by = height - 30;
        breakEffectsButton = addRenderableWidget(ExcavatorStyledButton.text(
                panelLeft + 10, by, 112, 20, Component.empty(), b -> toggleBreakEffects()
        ));
        transportModeButton = addRenderableWidget(ExcavatorStyledButton.text(
                panelLeft + 126, by, 122, 20, Component.empty(), b -> cycleTransportMode()
        ));
        addRenderableWidget(ExcavatorStyledButton.text(panelLeft + panelWidth - 146, by, 64, 20, Component.literal("Cancel"), b -> cancel()));
        addRenderableWidget(ExcavatorStyledButton.text(panelLeft + panelWidth - 78, by, 68, 20, Component.literal("Save"), b -> save()));
        updateToggleText();
    }

    private void updateFieldPositions() {
        for (int i = 0; i < fields.size(); i++) {
            EditBox box = fields.get(i);
            int y = rowY[i] - scroll;
            box.setY(y);
            box.visible = y + box.getHeight() > contentTop && y < contentBottom;
        }
    }

    private void setScroll(int value) {
        scroll = Math.max(0, Math.min(maxScroll, value));
        updateFieldPositions();
    }

    private int thumbHeight() {
        int track = contentBottom - contentTop;
        return maxScroll == 0 ? track : Math.max(18, track * track / (track + maxScroll));
    }

    private int thumbY() {
        int range = contentBottom - contentTop - thumbHeight();
        return contentTop + (maxScroll == 0 ? 0 : Math.round(range * (scroll / (float) maxScroll)));
    }

    private void update(int index, EditBox box, String value) {
        Setting setting = SETTINGS.get(index);
        try {
            double parsed = Double.parseDouble(value);
            if (parsed < setting.min || parsed > setting.max) throw new NumberFormatException();
            setting.set.accept(setting.integer ? Math.rint(parsed) : parsed);
            box.setTextColor(TEXT);
            LaserExcavatorClientConfig.RENDER_PRESET.set(RenderPreset.CUSTOM);
        } catch (NumberFormatException ignored) { box.setTextColor(ERROR); }
    }

    private void toggleBreakEffects() {
        LaserExcavatorClientConfig.EMIT_VANILLA_BREAK_EFFECTS.set(!LaserExcavatorClientConfig.EMIT_VANILLA_BREAK_EFFECTS.get());
        LaserExcavatorClientConfig.RENDER_PRESET.set(RenderPreset.CUSTOM);
        updateToggleText();
    }

    private void cycleTransportMode() {
        TransportRenderMode next = switch (LaserExcavatorClientConfig.transportRenderMode()) {
            case NORMAL -> TransportRenderMode.DOTS;
            case DOTS -> TransportRenderMode.OFF;
            case OFF -> TransportRenderMode.NORMAL;
        };
        LaserExcavatorClientConfig.setTransportRenderMode(next);
        LaserExcavatorClientConfig.RENDER_PRESET.set(RenderPreset.CUSTOM);
        updateToggleText();
    }

    private void updateToggleText() {
        breakEffectsButton.setMessage(Component.literal("Break effects: " + (LaserExcavatorClientConfig.EMIT_VANILLA_BREAK_EFFECTS.get() ? "On" : "Off")));
        transportModeButton.setMessage(Component.literal("Transports: " + LaserExcavatorClientConfig.transportRenderMode().label()));
    }

    private void save() {
        LaserExcavatorClientConfig.RENDER_PRESET.set(RenderPreset.CUSTOM);
        LaserExcavatorClientConfig.saveRendering();
        minecraft.setScreen(parent);
    }

    private void cancel() {
        for (int i = 0; i < SETTINGS.size(); i++) SETTINGS.get(i).set.accept(original[i]);
        LaserExcavatorClientConfig.EMIT_VANILLA_BREAK_EFFECTS.set(originalBreakEffects);
        LaserExcavatorClientConfig.setTransportRenderMode(originalTransportMode);
        LaserExcavatorClientConfig.RENDER_PRESET.set(originalPreset);
        minecraft.setScreen(parent);
    }

    private void closeExcavatorMenu() {
        LaserExcavatorClientConfig.saveRendering();
        if (parent instanceof ExcavatorRenderSettingsScreen settings) settings.closeExcavatorMenu();
        else minecraft.setScreen(null);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256 || minecraft.options.keyInventory.matches(keyCode, scanCode)) {
            closeExcavatorMenu();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() { cancel(); }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {}

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(graphics, mouseX, mouseY, partialTick);
        int top = 8, bottom = height - 6;
        graphics.fill(0, 0, width, height, 0x33000000);
        graphics.fill(panelLeft, top, panelLeft + panelWidth, bottom, FRAME);
        graphics.fill(panelLeft + 1, top + 1, panelLeft + panelWidth - 1, bottom - 1, SCREEN_BG);
        graphics.fill(panelLeft + 1, top + 1, panelLeft + panelWidth - 1, top + 22, PANEL_HEADER);
        graphics.fill(panelLeft + 7, contentTop, panelLeft + 12 + columnWidth, contentBottom, PANEL_INNER);
        graphics.fill(panelLeft + 14 + columnWidth, contentTop, panelLeft + panelWidth - 7, contentBottom, PANEL_INNER);
        graphics.drawCenteredString(font, title, width / 2, top + 7, TEXT);
        graphics.drawString(font, "Changes apply live", panelLeft + 10, top + 7, ACCENT, false);

        graphics.enableScissor(panelLeft + 7, contentTop, panelLeft + panelWidth - 7, contentBottom);
        for (int g = 0; g < GROUPS.size(); g++) {
            Group group = GROUPS.get(g);
            int x = panelLeft + 15 + group.column * (columnWidth + 6), y = groupY[g] - scroll;
            graphics.pose().pushMatrix();
            graphics.pose().translate(x, y);
            graphics.pose().scale(.85F, .85F);
            graphics.drawString(font, group.name, 0, 0, ACCENT, false);
            graphics.pose().popMatrix();
            int lineEnd = x + columnWidth - 14;
            graphics.fill(x + (int) (font.width(group.name) * .85F) + 5, y + 3, lineEnd, y + 4, ACCENT_DIM);
        }
        for (int i = 0; i < SETTINGS.size(); i++) {
            int y = rowY[i] - scroll;
            Setting setting = SETTINGS.get(i);
            drawImpactBlock(graphics, rowX[i], y + 4, setting.impact);
            graphics.drawString(font, setting.name, rowX[i] + 9, y + 3, MUTED, false);
            EditBox box = fields.get(i);
            if (box.visible) box.render(graphics, mouseX, mouseY, partialTick);
        }
        graphics.disableScissor();

        boolean[] visible = new boolean[fields.size()];
        for (int i = 0; i < fields.size(); i++) {
            visible[i] = fields.get(i).visible;
            fields.get(i).visible = false;
        }
        super.render(graphics, mouseX, mouseY, partialTick);
        for (int i = 0; i < fields.size(); i++) fields.get(i).visible = visible[i];

        if (maxScroll > 0) {
            int x = panelLeft + panelWidth - 11, ty = thumbY(), th = thumbHeight();
            graphics.fill(x, contentTop + 2, x + 4, contentBottom - 2, 0x662A3542);
            graphics.fill(x, ty + 2, x + 4, ty + th - 2, draggingScroll ? 0xFF9CE6FF : ACCENT);
        }

        Impact hoveredImpact = hoveredImpact(mouseX, mouseY);
        Setting hovered = hoveredSetting(mouseX, mouseY);
        if (hoveredImpact != null) graphics.setTooltipForNextFrame(font, Component.literal(hoveredImpact.tooltip), mouseX, mouseY);
        else if (hovered != null) graphics.setTooltipForNextFrame(font, font.split(settingTooltip(hovered), 260), mouseX, mouseY);
        else if (breakEffectsButton.isHovered()) graphics.setTooltipForNextFrame(font, font.split(Component.literal("Play vanilla block-break sounds and particles locally when excavator removals reach this client."), 260), mouseX, mouseY);
        else if (transportModeButton.isHovered()) graphics.setTooltipForNextFrame(font, font.split(Component.literal("Transport rendering mode: Normal uses full LOD visuals, Dots uses the cheapest markers, and Off renders no transports."), 260), mouseX, mouseY);
    }

    private Impact hoveredImpact(int mouseX, int mouseY) {
        if (mouseY < contentTop || mouseY >= contentBottom) return null;
        for (int i = 0; i < SETTINGS.size(); i++) {
            int y = rowY[i] - scroll;
            if (mouseX >= rowX[i] && mouseX < rowX[i] + 6 && mouseY >= y + 4 && mouseY < y + 10)
                return SETTINGS.get(i).impact;
        }
        return null;
    }

    private Setting hoveredSetting(int mouseX, int mouseY) {
        if (mouseY < contentTop || mouseY >= contentBottom) return null;
        for (int i = 0; i < SETTINGS.size(); i++) {
            int y = rowY[i] - scroll;
            if (mouseY < y - 1 || mouseY >= y + 15) continue;
            int labelX = rowX[i] + 9, labelEnd = labelX + font.width(SETTINGS.get(i).name) + 4;
            EditBox box = fields.get(i);
            if (mouseX >= labelX && mouseX < labelEnd || mouseX >= box.getX() - 2 && mouseX < box.getX() + box.getWidth() + 2)
                return SETTINGS.get(i);
        }
        return null;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        if (mouseX >= panelLeft + 7 && mouseX < panelLeft + panelWidth - 7 && mouseY >= contentTop && mouseY < contentBottom) {
            setScroll(scroll - (int) Math.round(deltaY * 24));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, deltaX, deltaY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int x = panelLeft + panelWidth - 13;
        if (button == 0 && maxScroll > 0 && mouseX >= x && mouseX < x + 8 && mouseY >= contentTop && mouseY < contentBottom) {
            int ty = thumbY(), th = thumbHeight();
            if (mouseY >= ty && mouseY < ty + th) dragOffset = (int) mouseY - ty;
            else {
                dragOffset = th / 2;
                scrollFromThumb((int) mouseY - dragOffset);
            }
            draggingScroll = true;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingScroll && button == 0) {
            scrollFromThumb((int) mouseY - dragOffset);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && draggingScroll) {
            draggingScroll = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private void scrollFromThumb(int y) {
        int range = contentBottom - contentTop - thumbHeight();
        if (range <= 0) setScroll(0);
        else setScroll(Math.round(maxScroll * ((Math.max(contentTop, Math.min(contentTop + range, y)) - contentTop) / (float) range)));
    }

    private static Component settingTooltip(Setting setting) {
        String min = setting.integer ? Integer.toString((int) setting.min) : trim(setting.min);
        String max = setting.integer ? Integer.toString((int) setting.max) : trim(setting.max);
        return Component.literal(setting.tooltip + "\nMin: " + min + "   Max: " + max);
    }

    private static String trim(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
    }

    private static String format(Setting setting) {
        double value = setting.get.getAsDouble();
        if (setting.integer) return Integer.toString((int) Math.round(value));
        String text = Double.toString(value);
        return text.endsWith(".0") ? text.substring(0, text.length() - 2) : text;
    }

    private static void drawImpactBlock(GuiGraphics graphics, int x, int y, Impact impact) {
        graphics.fill(x, y, x + 6, y + 6, DIVIDER);
        graphics.fill(x + 1, y + 1, x + 5, y + 5, impact.color);
    }

    private static Group group(String name, int column, Setting... settings) { return new Group(name, column, List.of(settings)); }
    private static Setting ints(String name, String tooltip, Impact impact, ModConfigSpec.IntValue value, double min, double max) { return new Setting(name, tooltip, impact, min, max, true, value::get, v -> value.set((int) Math.round(v))); }
    private static Setting doubles(String name, String tooltip, Impact impact, ModConfigSpec.DoubleValue value, double min, double max) { return new Setting(name, tooltip, impact, min, max, false, value::get, value::set); }
    private enum Impact {
        NONE("No Impact", 0xFF050607), LOW("Low Impact", GOOD), MEDIUM("Medium Impact", WARNING), HIGH("High Impact", ERROR);

        private final String tooltip;
        private final int color;

        Impact(String tooltip, int color) {
            this.tooltip = tooltip;
            this.color = color;
        }
    }
    private record Group(String name, int column, List<Setting> settings) {}
    private record Setting(String name, String tooltip, Impact impact, double min, double max, boolean integer, DoubleSupplier get, DoubleConsumer set) {}
}
