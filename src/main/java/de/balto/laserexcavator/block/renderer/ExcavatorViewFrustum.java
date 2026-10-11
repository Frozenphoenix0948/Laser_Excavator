package de.balto.laserexcavator.block.renderer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

final class ExcavatorViewFrustum {
    private static Frustum cached;
    private static Vec3 lastPosition;
    private static final Quaternionf lastRotation = new Quaternionf();
    private static float lastFov = Float.NaN;
    private static int lastWidth, lastHeight;

    static Frustum current(CameraRenderState camera) {
        return current(camera.pos, camera.orientation, camera.projectionMatrix);
    }

    static Frustum current(Camera camera) {
        return current(camera.position(), camera.rotation(), Minecraft.getInstance().gameRenderer.getGameRenderState().levelRenderState.cameraRenderState.projectionMatrix);
    }

    private static Frustum current(Vec3 position, Quaternionf orientation, Matrix4f cameraProjection) {
        Minecraft minecraft = Minecraft.getInstance();
        float fov = Math.max(130.0F, minecraft.options.fov().get() + 25.0F);
        int width = minecraft.getWindow().getWidth(), height = minecraft.getWindow().getHeight();
        if (cached == null || !position.equals(lastPosition) || !orientation.equals(lastRotation) || lastFov != fov || width != lastWidth || height != lastHeight) {
            Matrix4f view = new Matrix4f().rotation(new Quaternionf(orientation).conjugate());
            Matrix4f projection = new Matrix4f(cameraProjection);
            float verticalScale = 1.0F / (float) Math.tan(Math.toRadians(fov) * 0.5D);
            projection.m00(verticalScale * height / width).m11(verticalScale);
            cached = new Frustum(view, projection);
            cached.prepare(position.x, position.y, position.z);
            lastPosition = position;
            lastRotation.set(orientation);
            lastFov = fov;
            lastWidth = width;
            lastHeight = height;
        }
        return cached;
    }

    private ExcavatorViewFrustum() {}
}
