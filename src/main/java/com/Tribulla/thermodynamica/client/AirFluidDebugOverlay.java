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
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Mod.EventBusSubscriber(modid = Thermodynamica.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class AirFluidDebugOverlay {

    private static final double MAX_DELTA_C = 80.0;
    private static final double MIN_RENDER_DELTA = 0.5;
    private static final float BOX_EXPAND = 0.02f;

    private static final double RENDER_RANGE = 40.0;
    private static final double RENDER_RANGE_SQR = RENDER_RANGE * RENDER_RANGE;
    private static final int REBUILD_INTERVAL_FRAMES = 3;
    private static final double LOD_FULL_BOX_RANGE_SQR = 18.0 * 18.0;

    private static volatile boolean enabled = false;

    private static final List<RenderCell> visible = new ArrayList<>(4096);
    private static int framesSinceRebuild = REBUILD_INTERVAL_FRAMES;
    private static double cachedAmbient = 20.0;

    private record RenderCell(float x0, float y0, float z0, float x1, float y1, float z1,
            float r, float g, float b, float a, boolean fullBox) {
    }

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
            visible.clear();
        }
    }

    public static void toggle() {
        setEnabled(!enabled);
    }

    @SubscribeEvent
    public static void onDisconnect(ClientPlayerNetworkEvent.LoggingOut event) {
        enabled = false;
        ClientFluidCache.clear();
        visible.clear();
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (!enabled)
            return;
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES)
            return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null)
            return;

        Vec3 cam = event.getCamera().getPosition();
        framesSinceRebuild++;
        if (framesSinceRebuild >= REBUILD_INTERVAL_FRAMES || visible.isEmpty()) {
            rebuildVisible(cam);
            framesSinceRebuild = 0;
        }

        if (visible.isEmpty())
            return;

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

        for (int i = 0, n = visible.size(); i < n; i++) {
            RenderCell cell = visible.get(i);
            if (cell.fullBox) {
                putBox(buffer, matrix, cell.x0, cell.y0, cell.z0, cell.x1, cell.y1, cell.z1,
                        cell.r, cell.g, cell.b, cell.a);
            } else {
                // Far LOD: top face only (4 verts vs 24).
                putTopFace(buffer, matrix, cell.x0, cell.y1, cell.z0, cell.x1, cell.z1,
                        cell.r, cell.g, cell.b, cell.a);
            }
        }

        tesselator.end();

        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        pose.popPose();
    }

    private static void rebuildVisible(Vec3 cam) {
        visible.clear();
        try {
            cachedAmbient = HeatAPI.get().getAmbientTemperature();
        } catch (IllegalStateException ignored) {
            // keep last ambient
        }

        ClientFluidCache.prune(cam, RENDER_RANGE * 1.35);

        double camX = cam.x;
        double camY = cam.y;
        double camZ = cam.z;

        for (Map.Entry<BlockPos, CachedFluidEntry> entry : ClientFluidCache.entries()) {
            CachedFluidEntry fluid = entry.getValue();
            double delta = fluid.celsius() - cachedAmbient;
            double absDelta = Math.abs(delta);
            if (absDelta < MIN_RENDER_DELTA)
                continue;

            Vec3 center = fluid.worldCenter();
            double dx = center.x - camX;
            double dy = center.y - camY;
            double dz = center.z - camZ;
            double distSqr = dx * dx + dy * dy + dz * dz;
            if (distSqr > RENDER_RANGE_SQR)
                continue;

            float r, g, b, a;
            float t = (float) Math.min(1.0, absDelta / MAX_DELTA_C);
            a = 0.12f + 0.40f * t;
            if (delta >= 0.0) {
                if (t < 0.5f) {
                    float u = t / 0.5f;
                    r = u;
                    g = 0.0f;
                    b = 0.0f;
                } else {
                    float u = (t - 0.5f) / 0.5f;
                    r = 1.0f;
                    g = u;
                    b = u * 0.4f;
                }
            } else if (t < 0.5f) {
                float u = t / 0.5f;
                r = 0.0f;
                g = 0.0f;
                b = u;
            } else {
                float u = (t - 0.5f) / 0.5f;
                r = 0.0f;
                g = u;
                b = 1.0f;
            }

            float x0 = (float) (center.x - 0.5 - BOX_EXPAND);
            float y0 = (float) (center.y - 0.5 - BOX_EXPAND);
            float z0 = (float) (center.z - 0.5 - BOX_EXPAND);
            float x1 = (float) (center.x + 0.5 + BOX_EXPAND);
            float y1 = (float) (center.y + 0.5 + BOX_EXPAND);
            float z1 = (float) (center.z + 0.5 + BOX_EXPAND);
            visible.add(new RenderCell(x0, y0, z0, x1, y1, z1, r, g, b, a,
                    distSqr <= LOD_FULL_BOX_RANGE_SQR));
        }
    }

    private static void putTopFace(BufferBuilder buffer, Matrix4f matrix,
            float x0, float y, float z0, float x1, float z1,
            float r, float g, float b, float a) {
        buffer.vertex(matrix, x0, y, z0).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, x0, y, z1).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, x1, y, z1).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, x1, y, z0).color(r, g, b, a).endVertex();
    }

    private static void putBox(BufferBuilder buffer, Matrix4f matrix,
            float x0, float y0, float z0, float x1, float y1, float z1,
            float r, float g, float b, float a) {
        buffer.vertex(matrix, x0, y0, z0).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, x1, y0, z0).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, x1, y0, z1).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, x0, y0, z1).color(r, g, b, a).endVertex();

        buffer.vertex(matrix, x0, y1, z0).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, x0, y1, z1).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, x1, y1, z1).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, x1, y1, z0).color(r, g, b, a).endVertex();

        buffer.vertex(matrix, x0, y0, z0).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, x0, y1, z0).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, x1, y1, z0).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, x1, y0, z0).color(r, g, b, a).endVertex();

        buffer.vertex(matrix, x0, y0, z1).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, x1, y0, z1).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, x1, y1, z1).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, x0, y1, z1).color(r, g, b, a).endVertex();

        buffer.vertex(matrix, x0, y0, z0).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, x0, y0, z1).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, x0, y1, z1).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, x0, y1, z0).color(r, g, b, a).endVertex();

        buffer.vertex(matrix, x1, y0, z0).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, x1, y1, z0).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, x1, y1, z1).color(r, g, b, a).endVertex();
        buffer.vertex(matrix, x1, y0, z1).color(r, g, b, a).endVertex();
    }
}
