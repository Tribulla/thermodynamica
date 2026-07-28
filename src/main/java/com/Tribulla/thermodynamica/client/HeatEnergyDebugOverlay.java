package com.Tribulla.thermodynamica.client;

import com.Tribulla.thermodynamica.Thermodynamica;
import com.Tribulla.thermodynamica.api.CachedHeatEntry;
import com.Tribulla.thermodynamica.api.ClientHeatCache;
import com.Tribulla.thermodynamica.api.HeatAPI;
import com.Tribulla.thermodynamica.api.ThermalProperties;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

import java.util.Map;

@Mod.EventBusSubscriber(modid = Thermodynamica.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class HeatEnergyDebugOverlay {

    public static final double MAX_ENERGY_JOULES = 5_000_000.0;

    private static final double MIN_RENDER_ENERGY = 50.0;
    private static final float BOX_EXPAND = 0.01f;

    private static volatile boolean enabled = false;

    private HeatEnergyDebugOverlay() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
        Thermodynamica.LOGGER.info("Heat energy debug overlay {}", value ? "ENABLED" : "DISABLED");
    }

    public static void toggle() {
        setEnabled(!enabled);
    }

    @SubscribeEvent
    public static void onDisconnect(ClientPlayerNetworkEvent.LoggingOut event) {
        enabled = false;
        ClientHeatCache.clear();
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (!enabled)
            return;
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES)
            return;

        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null || mc.player == null)
            return;

        Map<BlockPos, CachedHeatEntry> snapshot = ClientHeatCache.getSnapshot();
        if (snapshot.isEmpty())
            return;

        double ambient;
        try {
            ambient = HeatAPI.get().getAmbientTemperature();
        } catch (IllegalStateException e) {
            return;
        }

        Vec3 cam = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);
        Matrix4f matrix = pose.last().pose();

        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(
                GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ZERO);
        RenderSystem.disableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buffer = tesselator.getBuilder();
        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        for (Map.Entry<BlockPos, CachedHeatEntry> entry : snapshot.entrySet()) {
            CachedHeatEntry heat = entry.getValue();
            BlockPos statePos = heat.renderStatePos();
            if (!level.isLoaded(statePos))
                continue;

            BlockState state = level.getBlockState(statePos);
            if (state.isAir())
                continue;

            ResourceLocation blockId = state.getBlock().builtInRegistryHolder().key().location();
            ThermalProperties props = HeatAPI.get().getThermalProperties(blockId);
            double cp = props.getHeatCapacity();
            if (cp <= 0.0 || !Double.isFinite(cp))
                continue;

            double energyJ = (heat.celsius() - ambient) * cp;
            if (Math.abs(energyJ) < MIN_RENDER_ENERGY)
                continue;

            float[] rgba = colorForEnergy(energyJ);
            Vec3 center = heat.worldCenter();
            float x0 = (float) (center.x - 0.5 - BOX_EXPAND);
            float y0 = (float) (center.y - 0.5 - BOX_EXPAND);
            float z0 = (float) (center.z - 0.5 - BOX_EXPAND);
            float x1 = (float) (center.x + 0.5 + BOX_EXPAND);
            float y1 = (float) (center.y + 0.5 + BOX_EXPAND);
            float z1 = (float) (center.z + 0.5 + BOX_EXPAND);

            putBox(buffer, matrix, x0, y0, z0, x1, y1, z1, rgba[0], rgba[1], rgba[2], rgba[3]);
        }

        tesselator.end();

        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        pose.popPose();
    }

    public static float[] colorForEnergy(double energyJoules) {
        double abs = Math.abs(energyJoules);
        float t = (float) Math.min(1.0, Math.sqrt(abs / MAX_ENERGY_JOULES));
        t = Math.max(t, 0.25f);

        float r, g, b;
        if (energyJoules >= 0.0) {
            if (t < 0.33f) {
                float u = t / 0.33f;
                r = u;
                g = 0.0f;
                b = 0.0f;
            } else if (t < 0.66f) {
                float u = (t - 0.33f) / 0.33f;
                r = 1.0f;
                g = u;
                b = 0.0f;
            } else {
                float u = (t - 0.66f) / 0.34f;
                r = 1.0f;
                g = 1.0f;
                b = u;
            }
        } else {
            if (t < 0.33f) {
                float u = t / 0.33f;
                r = 0.0f;
                g = 0.0f;
                b = u;
            } else if (t < 0.66f) {
                float u = (t - 0.33f) / 0.33f;
                r = 0.0f;
                g = u;
                b = 1.0f;
            } else {
                float u = (t - 0.66f) / 0.34f;
                r = u;
                g = 1.0f;
                b = 1.0f;
            }
        }

        float a = 0.55f + 0.40f * t;
        return new float[] { r, g, b, a };
    }

    private static void putBox(BufferBuilder buffer, Matrix4f matrix,
            float x0, float y0, float z0, float x1, float y1, float z1,
            float r, float g, float b, float a) {
        vertex(buffer, matrix, x0, y0, z0, r, g, b, a);
        vertex(buffer, matrix, x1, y0, z0, r, g, b, a);
        vertex(buffer, matrix, x1, y0, z1, r, g, b, a);
        vertex(buffer, matrix, x0, y0, z1, r, g, b, a);

        vertex(buffer, matrix, x0, y1, z0, r, g, b, a);
        vertex(buffer, matrix, x0, y1, z1, r, g, b, a);
        vertex(buffer, matrix, x1, y1, z1, r, g, b, a);
        vertex(buffer, matrix, x1, y1, z0, r, g, b, a);

        vertex(buffer, matrix, x0, y0, z0, r, g, b, a);
        vertex(buffer, matrix, x0, y1, z0, r, g, b, a);
        vertex(buffer, matrix, x1, y1, z0, r, g, b, a);
        vertex(buffer, matrix, x1, y0, z0, r, g, b, a);

        vertex(buffer, matrix, x0, y0, z1, r, g, b, a);
        vertex(buffer, matrix, x1, y0, z1, r, g, b, a);
        vertex(buffer, matrix, x1, y1, z1, r, g, b, a);
        vertex(buffer, matrix, x0, y1, z1, r, g, b, a);

        vertex(buffer, matrix, x0, y0, z0, r, g, b, a);
        vertex(buffer, matrix, x0, y0, z1, r, g, b, a);
        vertex(buffer, matrix, x0, y1, z1, r, g, b, a);
        vertex(buffer, matrix, x0, y1, z0, r, g, b, a);

        vertex(buffer, matrix, x1, y0, z0, r, g, b, a);
        vertex(buffer, matrix, x1, y1, z0, r, g, b, a);
        vertex(buffer, matrix, x1, y1, z1, r, g, b, a);
        vertex(buffer, matrix, x1, y0, z1, r, g, b, a);
    }

    private static void vertex(BufferBuilder buffer, Matrix4f matrix,
            float x, float y, float z, float r, float g, float b, float a) {
        buffer.vertex(matrix, x, y, z).color(r, g, b, a).endVertex();
    }
}
