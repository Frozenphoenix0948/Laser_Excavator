package de.balto.laserexcavator.block.renderer;

import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;

public final class ExcavatorRenderStates extends RenderStateShard {
    private ExcavatorRenderStates() {
        super("laser_excavator_render_states", () -> {}, () -> {});
    }

    public static RenderType.CompositeState opaqueLines() {
        return RenderType.CompositeState.builder()
                .setShaderState(RENDERTYPE_LINES_SHADER)
                .setTransparencyState(NO_TRANSPARENCY)
                .setCullState(NO_CULL)
                .setWriteMaskState(COLOR_DEPTH_WRITE)
                .setLightmapState(NO_LIGHTMAP)
                .createCompositeState(false);
    }

    public static RenderType.CompositeState opaqueMarkers() {
        return RenderType.CompositeState.builder()
                .setShaderState(POSITION_COLOR_SHADER)
                .setTransparencyState(NO_TRANSPARENCY)
                .setDepthTestState(LEQUAL_DEPTH_TEST)
                .setCullState(NO_CULL)
                .setWriteMaskState(COLOR_DEPTH_WRITE)
                .createCompositeState(false);
    }
}
