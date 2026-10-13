package de.balto.laserexcavator.compat;

import net.minecraft.world.level.block.Block;
import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.Optional;

public final class DynamicTreesCompat {
    private DynamicTreesCompat() {}

    private static final Class<?> BRANCH_CLASS;
    private static final Method PRIMITIVE_LOG;

    static {
        Class<?> branchClass = null;
        Method primitiveLog = null;
        try {
            branchClass = Class.forName("com.dtteam.dynamictrees.block.branch.BranchBlock");
            primitiveLog = branchClass.getMethod("getPrimitiveLog");
        } catch (ReflectiveOperationException | LinkageError ignored) {
        }
        BRANCH_CLASS = branchClass;
        PRIMITIVE_LOG = primitiveLog;
    }

    public static @Nullable Block getPrimitiveLog(Block block) {
        if (BRANCH_CLASS == null || PRIMITIVE_LOG == null || !BRANCH_CLASS.isInstance(block)) {
            return null;
        }
        try {
            Object result = PRIMITIVE_LOG.invoke(block);
            if (result instanceof Optional<?> optional && optional.orElse(null) instanceof Block log) {
                return log;
            }
        } catch (ReflectiveOperationException | LinkageError ignored) {
        }
        return null;
    }
}
