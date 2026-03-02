package com.Tribulla.thermodynamica.api.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.lang.reflect.Method;

/**
 * Compatibility layer for Valkyrien Skies mod.
 * Provides coordinate transformation utilities for heat simulation on ships.
 * Uses reflection to avoid compile-time dependency on VS.
 */
public class ValkyrienSkiesCompat {
    private static final Logger LOGGER = LogManager.getLogger();
    private static boolean initialized = false;
    private static boolean vsInstalled = false;
    
    // Reflection handles
    private static Class<?> vsGameUtilsClass = null;
    private static Class<?> shipClass = null;
    private static Class<?> shipTransformClass = null;
    private static Class<?> vectorConversionsClass = null;
    private static Class<?> matrix4dcClass = null;
    private static Class<?> vector3dClass = null;
    
    private static Method getShipManagingPosMethod = null;
    private static Method getTransformMethod = null;
    private static Method getShipToWorldMethod = null;
    private static Method getWorldToShipMethod = null;
    private static Method getPositionInWorldMethod = null;
    private static Method transformPositionMethod = null;
    private static Method transformDirectionMethod = null;
    private static Method toJOMLMethod = null;
    private static Method toMinecraftMethod = null;
    private static Method getVelocityMethod = null;

    /**
     * Initialize VS reflection hooks.
     */
    public static void init() {
        if (initialized)
            return;
        initialized = true;
        try {
            // Core VS classes
            vsGameUtilsClass = Class.forName("org.valkyrienskies.mod.common.VSGameUtilsKt");
            shipClass = Class.forName("org.valkyrienskies.core.api.ships.Ship");
            shipTransformClass = Class.forName("org.valkyrienskies.core.api.ships.properties.ShipTransform");
            vectorConversionsClass = Class.forName("org.valkyrienskies.mod.common.util.VectorConversionsMCKt");
            matrix4dcClass = Class.forName("org.joml.Matrix4dc");
            vector3dClass = Class.forName("org.joml.Vector3d");
            
            // Get methods
            getShipManagingPosMethod = vsGameUtilsClass.getMethod("getShipManagingPos", Level.class, BlockPos.class);
            getTransformMethod = shipClass.getMethod("getTransform");
            getShipToWorldMethod = shipTransformClass.getMethod("getShipToWorld");
            getWorldToShipMethod = shipTransformClass.getMethod("getWorldToShip");
            getPositionInWorldMethod = shipTransformClass.getMethod("getPositionInWorld");
            
            // Matrix methods
            transformPositionMethod = matrix4dcClass.getMethod("transformPosition", vector3dClass);
            transformDirectionMethod = matrix4dcClass.getMethod("transformDirection", vector3dClass);
            
            // Vector conversion methods
            toJOMLMethod = vectorConversionsClass.getMethod("toJOML", Vec3.class);
            Class<?> vector3dcClass = Class.forName("org.joml.Vector3dc");
            toMinecraftMethod = vectorConversionsClass.getMethod("toMinecraft", vector3dcClass);
            
            // Ship velocity
            getVelocityMethod = shipClass.getMethod("getVelocity");
            
            vsInstalled = true;
            LOGGER.info("Successfully hooked into Valkyrien Skies coordinate transformations.");
        } catch (Exception e) {
            vsInstalled = false;
            LOGGER.debug("Valkyrien Skies not detected or incompatible version. Native coordinates will be used.");
        }
    }

    /**
     * Check if Valkyrien Skies is installed and compatible.
     * @return true if VS is available
     */
    public static boolean isVSInstalled() {
        if (!initialized) init();
        return vsInstalled;
    }

    /**
     * Check if a block position is on a VS ship.
     * @param level the level
     * @param pos the block position
     * @return true if the position is on a ship
     */
    public static boolean isOnShip(Level level, BlockPos pos) {
        return getShipManagingPos(level, pos) != null;
    }

    /**
     * Get the ship managing a block position.
     * @param level the level
     * @param pos the block position
     * @return the ship object, or null if not on a ship
     */
    @Nullable
    public static Object getShipManagingPos(Level level, BlockPos pos) {
        if (!initialized) init();
        if (!vsInstalled || getShipManagingPosMethod == null) return null;
        
        try {
            return getShipManagingPosMethod.invoke(null, level, pos);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Transform a ship-local BlockPos to world coordinates.
     * @param level the level
     * @param pos the ship-local position
     * @return the world position, or the original if not on a ship
     */
    public static BlockPos toWorldPos(Level level, BlockPos pos) {
        if (!initialized) init();
        if (!vsInstalled) return pos;

        try {
            Object ship = getShipManagingPosMethod.invoke(null, level, pos);
            if (ship == null) return pos;
            
            Vec3 localVec = Vec3.atCenterOf(pos);
            Vec3 worldVec = toWorldCoordinates(level, pos, localVec);
            return BlockPos.containing(worldVec);
        } catch (Exception e) {
            return pos;
        }
    }

    /**
     * Transform a ship-local Vec3 position to world coordinates.
     * @param level the level
     * @param shipBlockPos a block position on the ship (used to find the ship)
     * @param shipLocalPos the ship-local position to transform
     * @return the world position, or the original if not on a ship
     */
    public static Vec3 toWorldCoordinates(Level level, BlockPos shipBlockPos, Vec3 shipLocalPos) {
        if (!initialized) init();
        if (!vsInstalled) return shipLocalPos;
        
        try {
            Object ship = getShipManagingPosMethod.invoke(null, level, shipBlockPos);
            if (ship == null) return shipLocalPos;
            
            // Convert to JOML Vector3d
            Object localJoml = toJOMLMethod.invoke(null, shipLocalPos);
            
            // Get ship-to-world transform matrix
            Object transform = getTransformMethod.invoke(ship);
            Object shipToWorld = getShipToWorldMethod.invoke(transform);
            
            // Transform position
            transformPositionMethod.invoke(shipToWorld, localJoml);
            
            // Convert back to Minecraft Vec3
            return (Vec3) toMinecraftMethod.invoke(null, localJoml);
        } catch (Exception e) {
            return shipLocalPos;
        }
    }

    /**
     * Transform a world Vec3 position to ship-local coordinates.
     * @param level the level
     * @param shipBlockPos a block position on the ship (used to find the ship)
     * @param worldPos the world position to transform
     * @return the ship-local position, or the original if not on a ship
     */
    public static Vec3 toShipCoordinates(Level level, BlockPos shipBlockPos, Vec3 worldPos) {
        if (!initialized) init();
        if (!vsInstalled) return worldPos;
        
        try {
            Object ship = getShipManagingPosMethod.invoke(null, level, shipBlockPos);
            if (ship == null) return worldPos;
            
            // Convert to JOML Vector3d
            Object worldJoml = toJOMLMethod.invoke(null, worldPos);
            
            // Get world-to-ship transform matrix
            Object transform = getTransformMethod.invoke(ship);
            Object worldToShip = getWorldToShipMethod.invoke(transform);
            
            // Transform position
            transformPositionMethod.invoke(worldToShip, worldJoml);
            
            // Convert back to Minecraft Vec3
            return (Vec3) toMinecraftMethod.invoke(null, worldJoml);
        } catch (Exception e) {
            return worldPos;
        }
    }

    /**
     * Transform a direction vector from ship-local to world space.
     * @param level the level
     * @param shipBlockPos a block position on the ship
     * @param shipLocalDirection the ship-local direction
     * @return the world direction, or the original if not on a ship
     */
    public static Vec3 transformDirectionToWorld(Level level, BlockPos shipBlockPos, Vec3 shipLocalDirection) {
        if (!initialized) init();
        if (!vsInstalled) return shipLocalDirection;
        
        try {
            Object ship = getShipManagingPosMethod.invoke(null, level, shipBlockPos);
            if (ship == null) return shipLocalDirection;
            
            Object dirJoml = toJOMLMethod.invoke(null, shipLocalDirection);
            Object transform = getTransformMethod.invoke(ship);
            Object shipToWorld = getShipToWorldMethod.invoke(transform);
            transformDirectionMethod.invoke(shipToWorld, dirJoml);
            return (Vec3) toMinecraftMethod.invoke(null, dirJoml);
        } catch (Exception e) {
            return shipLocalDirection;
        }
    }

    /**
     * Transform a direction vector from world space to ship-local.
     * @param level the level
     * @param shipBlockPos a block position on the ship
     * @param worldDirection the world direction
     * @return the ship-local direction, or the original if not on a ship
     */
    public static Vec3 transformDirectionToShip(Level level, BlockPos shipBlockPos, Vec3 worldDirection) {
        if (!initialized) init();
        if (!vsInstalled) return worldDirection;
        
        try {
            Object ship = getShipManagingPosMethod.invoke(null, level, shipBlockPos);
            if (ship == null) return worldDirection;
            
            Object dirJoml = toJOMLMethod.invoke(null, worldDirection);
            Object transform = getTransformMethod.invoke(ship);
            Object worldToShip = getWorldToShipMethod.invoke(transform);
            transformDirectionMethod.invoke(worldToShip, dirJoml);
            return (Vec3) toMinecraftMethod.invoke(null, dirJoml);
        } catch (Exception e) {
            return worldDirection;
        }
    }

    /**
     * Get the velocity of a ship at a block position.
     * @param level the level
     * @param shipBlockPos a block position on the ship
     * @return the ship's velocity vector, or Vec3.ZERO if not on a ship
     */
    public static Vec3 getShipVelocity(Level level, BlockPos shipBlockPos) {
        if (!initialized) init();
        if (!vsInstalled || getVelocityMethod == null) return Vec3.ZERO;
        
        try {
            Object ship = getShipManagingPosMethod.invoke(null, level, shipBlockPos);
            if (ship == null) return Vec3.ZERO;
            
            Object velocity = getVelocityMethod.invoke(ship);
            if (velocity instanceof org.joml.Vector3d vel) {
                return new Vec3(vel.x, vel.y, vel.z);
            }
        } catch (Exception e) {
            // Silently fall back
        }
        return Vec3.ZERO;
    }

    /**
     * Get the ship's world position (center of mass).
     * @param level the level
     * @param shipBlockPos a block position on the ship
     * @return the ship's center position in world coordinates, or null if not on a ship
     */
    @Nullable
    public static Vec3 getShipWorldPosition(Level level, BlockPos shipBlockPos) {
        if (!initialized) init();
        if (!vsInstalled) return null;
        
        try {
            Object ship = getShipManagingPosMethod.invoke(null, level, shipBlockPos);
            if (ship == null) return null;
            
            Object transform = getTransformMethod.invoke(ship);
            Object posInWorld = getPositionInWorldMethod.invoke(transform);
            if (posInWorld instanceof org.joml.Vector3dc pos) {
                return new Vec3(pos.x(), pos.y(), pos.z());
            }
        } catch (Exception e) {
            // Silently fall back
        }
        return null;
    }
}
