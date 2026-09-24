package de.balto.laserexcavator.block.excavator;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;

public final class ExcavatorArea {
    private final BlockPos min;
    private final BlockPos max;
    private final int sizeX;
    private final int sizeZ;
    private final int columnCount;

    private ExcavatorArea(BlockPos min, BlockPos max) {
        this.min = min;
        this.max = max;
        this.sizeX = max.getX() - min.getX() + 1;
        this.sizeZ = max.getZ() - min.getZ() + 1;
        this.columnCount = sizeX * sizeZ;
    }

    public static ExcavatorArea create(
            BlockPos excavatorPos,
            Direction facing,
            int width,
            int height,
            int length
    ) {
        width = Math.max(1, width);
        height = Math.max(1, height);
        length = Math.max(1, length);

        /*
         * Center the lateral footprint in world space for every facing. Even widths use
         * the same asymmetric integer span on both opposing directions; for example,
         * width 32 spans -16..+15 from the excavator on the lateral axis.
         */
        int minSideOffset = -(width / 2);
        int maxSideOffset = minSideOffset + width - 1;

        int minX;
        int maxX;
        int minZ;
        int maxZ;

        if (facing.getAxis() == Direction.Axis.Z) {
            minX = excavatorPos.getX() + minSideOffset;
            maxX = excavatorPos.getX() + maxSideOffset;

            int nearZ = excavatorPos.getZ() + facing.getStepZ();
            int farZ = excavatorPos.getZ() + facing.getStepZ() * length;
            minZ = Math.min(nearZ, farZ);
            maxZ = Math.max(nearZ, farZ);
        } else {
            minZ = excavatorPos.getZ() + minSideOffset;
            maxZ = excavatorPos.getZ() + maxSideOffset;

            int nearX = excavatorPos.getX() + facing.getStepX();
            int farX = excavatorPos.getX() + facing.getStepX() * length;
            minX = Math.min(nearX, farX);
            maxX = Math.max(nearX, farX);
        }
        int maxY = excavatorPos.getY() - 1;
        int minY = maxY - height + 1;

        return new ExcavatorArea(
                new BlockPos(minX, minY, minZ),
                new BlockPos(maxX, maxY, maxZ)
        );
    }

    public BlockPos min() {
        return min;
    }

    public BlockPos max() {
        return max;
    }

    public int sizeX() {
        return sizeX;
    }

    public int sizeZ() {
        return sizeZ;
    }

    public int columnCount() {
        return columnCount;
    }

    public boolean containsColumn(int worldX, int worldZ) {
        return worldX >= min.getX() && worldX <= max.getX()
                && worldZ >= min.getZ() && worldZ <= max.getZ();
    }

    public int columnIndex(int worldX, int worldZ) {
        if (!containsColumn(worldX, worldZ)) return -1;
        return (worldZ - min.getZ()) * sizeX + (worldX - min.getX());
    }

    public int worldXForColumn(int index) {
        checkColumnIndex(index);
        return min.getX() + index % sizeX;
    }

    public int worldZForColumn(int index) {
        checkColumnIndex(index);
        return min.getZ() + index / sizeX;
    }

    private void checkColumnIndex(int index) {
        if (index < 0 || index >= columnCount) {
            throw new IndexOutOfBoundsException("Column index " + index + " outside 0.." + (columnCount - 1));
        }
    }

    public AABB asAabb() {
        return new AABB(
                min.getX(), min.getY(), min.getZ(),
                max.getX() + 1.0, max.getY() + 1.0, max.getZ() + 1.0
        );
    }
}
