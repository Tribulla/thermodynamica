package com.Tribulla.thermodynamica.api.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Method;

public class ValkyrienSkiesCompat {
    private static final Logger LOGGER = LogManager.getLogger();
    private static boolean initialized = false;
    private static boolean vsInstalled = false;
    private static Method toWorldCoordinatesMethod = null;

    public static void init() {
        if (initialized)
            return;
        initialized = true;
        try {
            Class<?> vsUtils = Class.forName("org.valkyrienskies.mod.common.VSGameUtilsKt");
            toWorldCoordinatesMethod = vsUtils.getMethod("toWorldCoordinates", Level.class, BlockPos.class);
            vsInstalled = true;
            LOGGER.info("Successfully hooked into Valkyrien Skies coordinate transformations.");
        } catch (Exception e) {
            vsInstalled = false;
            LOGGER.debug("Valkyrien Skies not detected or incompatible version. Native coordinates will be used.");
        }
    }

    public static BlockPos toWorldPos(Level level, BlockPos pos) {
        if (!initialized)
            init();
        if (!vsInstalled || toWorldCoordinatesMethod == null)
            return pos;

        try {
            Object result = toWorldCoordinatesMethod.invoke(null, level, pos);
            if (result instanceof BlockPos) {
                return (BlockPos) result;
            }
        } catch (Exception e) {
            // Silently fall back to native coordinates if the transformation fails
        }
        return pos;
    }
}
