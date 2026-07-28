package com.Tribulla.thermodynamica.client;

import com.Tribulla.thermodynamica.Thermodynamica;
import com.Tribulla.thermodynamica.api.CachedFluidEntry;
import com.Tribulla.thermodynamica.api.ClientFluidCache;
import com.Tribulla.thermodynamica.api.HeatAPI;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
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
public final class AirFluidDebugOverlay {

    private static final double MAX_DELTA_C = 80.0;
    private static final double MIN_RENDER_DELTA = 0.25;
    private static final float BOX_EXPAND = 0.02f;

    private static volatile boolean enabled = false;

    private AirFluidDebugOverlay() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
        Thermodynamica.LOGGER.info("Air fluid debug overlay {}", value ? "ENABLED" : "DISABLED");
        if (!value) {
            ClientFluidCache.clear();
        }
    }

    public static void toggle() {
        setEnabled(!enabled);
    }

    @SubscribeEvent
    public static void onDisconnect(ClientPlayerNetworkEvent.LoggingOut event) {
        enabled = false;
        ClientFluidCache.clear();
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

        Map<BlockPos, CachedFluidEntry> snapshot = ClientFluidCache.getSnapshot();
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

        for (Map.Entry<BlockPos, CachedFluidEntry> entry : snapshot.entrySet()) {
            CachedFluidEntry fluid = entry.getValue();
            double delta = fluid.celsius() - ambient;
            if (Math.abs(delta) < MIN_RENDER_DELTA && fluid.velocity().lengthSqr() < 0.0004)
                continue;

            float[] rgba = temperatureToRgba(delta);
            Vec3 center = fluid.worldCenter();
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
        pose.popPose();
    }

    private static float[] temperatureToRgba(double deltaC) {
        float t = (float) Math.min(1.0, Math.abs(deltaC) / MAX_DELTA_C);
        float alpha = 0.15f + 0.45f * t;
        if (deltaC >= 0.0) {
            if (t < 0.5f) {
                float u = t / 0.5f;
                return new float[] { u, 0.0f, 0.0f, alpha };
            }
            float u = (t - 0.5f) / 0.5f;
            return new float[] { 1.0f, u, u * 0.4f, alpha };
        }
        if (t < 0.5f) {
            float u = t / 0.5f;
            return new float[] { 0.0f, 0.0f, u, alpha };
        }
        float u = (t - 0.5f) / 0.5f;
        return new float[] { 0.0f, u, 1.0f, alpha };
    }

    private static void putBox(BufferBuilder buffer, Matrix4f matrix,
            float x0, float y0, float z0, float x1, float y1, float z1,
            float r, float g, float b, float a) {
        // -Y
        buffer.vertex(matrix, x0, y0, z0).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, x1, y0, z0).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, x1, y0, z1).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, x0, y0, z1).color(r, g, b, a).endVertex();
        // +Y
        buffer.vertex(matrix, x0, y1, z0).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, x0, y1, z1).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, x1, y1, z1).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, x1, y1, z0).color(r, g, b, a).endVertex();
        // -Z
        buffer.vertex(matrix, x0, y0, z0).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, x0, y1, z0).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, x1, y1, z0).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, x1, y0, z0).color(r, g, b, a).endVertex();
        // +Z
        buffer.vertex(matrix, x0, y0, z1).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, x1, y0, z1).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, x1, y1, z1).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, x0, y1, z1).color(r, g, b, a).endVertex();
        // -X
        buffer.vertex(matrix, x0, y0, z0).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, x0, y0, z1).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, x0, y1, z1).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, x0, y1, z0).color(r, g, b, a).endVertex();
        // +X
        buffer.vertex(matrix, x1, y0, z0).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, x1, y1, z0).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, x1, y1, z1).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, x1, y0, z1).color(r, g, b, a).endVertex();
    }
}
