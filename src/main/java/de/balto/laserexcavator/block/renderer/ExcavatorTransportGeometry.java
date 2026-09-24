package de.balto.laserexcavator.block.renderer;

import org.joml.Vector3f;

final class ExcavatorTransportGeometry {
    private ExcavatorTransportGeometry() {}

    static final class Position {
        float x;
        float y;
        float z;
    }

    static final class RotatingCube {
        final float[] cornerX = new float[4];
        final float[] cornerZ = new float[4];
        final float halfSize;

        float southNormalX, southNormalZ;
        float northNormalX, northNormalZ;
        float eastNormalX, eastNormalZ;
        float westNormalX, westNormalZ;

        RotatingCube(float halfSize) {
            this.halfSize = halfSize;
            update(1.0F, 0.0F);
        }

        void update(float cos, float sin) {
            float h = halfSize;
            setCorner(0, -h, -h, cos, sin);
            setCorner(1, h, -h, cos, sin);
            setCorner(2, h, h, cos, sin);
            setCorner(3, -h, h, cos, sin);

            southNormalX = sin;
            southNormalZ = cos;
            northNormalX = -sin;
            northNormalZ = -cos;
            eastNormalX = cos;
            eastNormalZ = -sin;
            westNormalX = -cos;
            westNormalZ = sin;
        }

        private void setCorner(int index, float x, float z, float cos, float sin) {
            cornerX[index] = x * cos + z * sin;
            cornerZ[index] = -x * sin + z * cos;
        }
    }

    static final class RotatingBillboard {
        final float[] cornerX = new float[4];
        final float[] cornerY = new float[4];
        final float[] cornerZ = new float[4];
        final float halfSize;

        float normalX, normalY, normalZ;

        RotatingBillboard(float halfSize) {
            this.halfSize = halfSize;
        }

        void update(Vector3f cameraLeft, Vector3f cameraUp, Vector3f cameraLook, float cos, float sin) {
            float rightX = -cameraLeft.x();
            float rightY = -cameraLeft.y();
            float rightZ = -cameraLeft.z();
            float upX = cameraUp.x();
            float upY = cameraUp.y();
            float upZ = cameraUp.z();

            float rotatedRightX = rightX * cos + upX * sin;
            float rotatedRightY = rightY * cos + upY * sin;
            float rotatedRightZ = rightZ * cos + upZ * sin;
            float rotatedUpX = -rightX * sin + upX * cos;
            float rotatedUpY = -rightY * sin + upY * cos;
            float rotatedUpZ = -rightZ * sin + upZ * cos;
            float h = halfSize;

            setCorner(0, -rotatedRightX * h - rotatedUpX * h, -rotatedRightY * h - rotatedUpY * h, -rotatedRightZ * h - rotatedUpZ * h);
            setCorner(1, rotatedRightX * h - rotatedUpX * h, rotatedRightY * h - rotatedUpY * h, rotatedRightZ * h - rotatedUpZ * h);
            setCorner(2, rotatedRightX * h + rotatedUpX * h, rotatedRightY * h + rotatedUpY * h, rotatedRightZ * h + rotatedUpZ * h);
            setCorner(3, -rotatedRightX * h + rotatedUpX * h, -rotatedRightY * h + rotatedUpY * h, -rotatedRightZ * h + rotatedUpZ * h);

            normalX = -cameraLook.x();
            normalY = -cameraLook.y();
            normalZ = -cameraLook.z();
        }

        private void setCorner(int index, float x, float y, float z) {
            cornerX[index] = x;
            cornerY[index] = y;
            cornerZ[index] = z;
        }
    }
}
