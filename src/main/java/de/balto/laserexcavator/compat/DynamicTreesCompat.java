package de.balto.laserexcavator.compat;

import com.dtteam.dynamictrees.block.branch.BranchBlock;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;

public final class DynamicTreesCompat {
    private DynamicTreesCompat() {
    }

    public static @Nullable Block getPrimitiveLog(Block block) {
        if (block instanceof BranchBlock branch) {
            return branch.getPrimitiveLog().orElse(null);
        }

        return null;
    }
}