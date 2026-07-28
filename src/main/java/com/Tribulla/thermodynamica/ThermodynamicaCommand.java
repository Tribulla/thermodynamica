package com.Tribulla.thermodynamica;

import com.Tribulla.thermodynamica.network.FluidDebugOverlayPacket;
import com.Tribulla.thermodynamica.network.FluidDebugPlayers;
import com.Tribulla.thermodynamica.network.FluidSyncManager;
import com.Tribulla.thermodynamica.network.HeatDebugOverlayPacket;
import com.Tribulla.thermodynamica.network.HeatDebugPlayers;
import com.Tribulla.thermodynamica.network.HeatNetwork;
import com.Tribulla.thermodynamica.network.HeatSyncManager;
import com.Tribulla.thermodynamica.simulation.FluidSimulationManager;
import com.Tribulla.thermodynamica.simulation.HeatSimulationManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PacketDistributor;

import java.util.OptionalDouble;

public class ThermodynamicaCommand {

        public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
                dispatcher.register(buildRoot("thermodynamica"));
                dispatcher.register(buildRoot("td"));
        }

        private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> buildRoot(String name) {
                return Commands.literal(name)
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.literal("tps")
                                                .executes(ThermodynamicaCommand::executeTps))
                                .then(Commands.literal("status")
                                                .executes(ThermodynamicaCommand::executeStatus))
                                .then(Commands.literal("reset")
                                                .executes(ThermodynamicaCommand::executeReset))
                                .then(Commands.literal("debug")
                                                .executes(ThermodynamicaCommand::executeDebug))
                                .then(Commands.literal("fluiddebug")
                                                .executes(ThermodynamicaCommand::executeFluidDebug))
                                .executes(ThermodynamicaCommand::executeTps);
        }

        private static int executeTps(CommandContext<CommandSourceStack> ctx) {
                HeatSimulationManager sim = getSimulation(ctx);
                if (sim == null)
                        return 0;

                CommandSourceStack source = ctx.getSource();

                double lastMs = sim.getLastSimulationTimeMs();
                double avgMs = sim.getAverageSimulationTimeMs();
                double simTps = sim.getSimulationTPS();
                int lastChunks = sim.getLastChunksProcessed();
                long totalTicks = sim.getSimulationTickCount();
                long totalBlocks = sim.getTotalBlocksProcessed();

                MutableComponent header = Component.literal("=== Thermodynamica TPS ===")
                                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
                source.sendSuccess(() -> header, false);

                ChatFormatting tpsColor = simTps >= 0.9 ? ChatFormatting.GREEN
                                : simTps >= 0.5 ? ChatFormatting.YELLOW : ChatFormatting.RED;

                source.sendSuccess(() -> Component.literal("Simulation TPS: ")
                                .withStyle(ChatFormatting.GRAY)
                                .append(Component.literal(String.format("%.2f", simTps))
                                                .withStyle(tpsColor)),
                                false);

                source.sendSuccess(() -> Component.literal("Last tick: ")
                                .withStyle(ChatFormatting.GRAY)
                                .append(Component.literal(String.format("%.2f ms", lastMs))
                                                .withStyle(lastMs < 50 ? ChatFormatting.GREEN : ChatFormatting.RED)),
                                false);

                source.sendSuccess(() -> Component.literal("Average tick: ")
                                .withStyle(ChatFormatting.GRAY)
                                .append(Component.literal(String.format("%.2f ms", avgMs))
                                                .withStyle(avgMs < 50 ? ChatFormatting.GREEN : ChatFormatting.RED)),
                                false);

                source.sendSuccess(() -> Component.literal("Sources advanced: ")
                                .withStyle(ChatFormatting.GRAY)
                                .append(Component.literal(String.valueOf(lastChunks))
                                                .withStyle(ChatFormatting.AQUA)),
                                false);

                source.sendSuccess(() -> Component.literal("Total sim ticks: ")
                                .withStyle(ChatFormatting.GRAY)
                                .append(Component.literal(String.valueOf(totalTicks))
                                                .withStyle(ChatFormatting.WHITE)),
                                false);

                source.sendSuccess(() -> Component.literal("Total advanced: ")
                                .withStyle(ChatFormatting.GRAY)
                                .append(Component.literal(String.format("%,d", totalBlocks))
                                                .withStyle(ChatFormatting.WHITE)),
                                false);

                FluidSimulationManager fluid = getFluidSimulation(ctx.getSource());
                if (fluid != null) {
                        boolean enabled = Thermodynamica.getInstance().getConfigManager()
                                        .getSettings().isFluidSimulationEnabled();
                        source.sendSuccess(() -> Component.literal("Fluid last tick: ")
                                        .withStyle(ChatFormatting.GRAY)
                                        .append(Component.literal(String.format("%.2f ms", fluid.getLastSimulationTimeMs()))
                                                        .withStyle(ChatFormatting.AQUA))
                                        .append(Component.literal(enabled ? "" : " (disabled)")
                                                        .withStyle(ChatFormatting.DARK_GRAY)),
                                        false);
                }

                return 1;
        }

        private static int executeStatus(CommandContext<CommandSourceStack> ctx) {
                HeatSimulationManager sim = getSimulation(ctx);
                if (sim == null)
                        return 0;

                CommandSourceStack source = ctx.getSource();

                int loadedChunks = sim.getLoadedChunkCount();
                int activeBlocks = sim.getActiveBlockCount();
                int dirtyChunks = sim.getDirtyChunkCount();
                int propagating = sim.getPropagatingSourceCount();
                boolean running = sim.isRunning();

                MutableComponent header = Component.literal("=== Thermodynamica Status ===")
                                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
                source.sendSuccess(() -> header, false);

                source.sendSuccess(() -> Component.literal("Engine: ")
                                .withStyle(ChatFormatting.GRAY)
                                .append(Component.literal(running ? "RUNNING" : "STOPPED")
                                                .withStyle(running ? ChatFormatting.GREEN : ChatFormatting.RED)),
                                false);

                source.sendSuccess(() -> Component.literal("Loaded chunks: ")
                                .withStyle(ChatFormatting.GRAY)
                                .append(Component.literal(String.valueOf(loadedChunks))
                                                .withStyle(ChatFormatting.AQUA)),
                                false);

                source.sendSuccess(() -> Component.literal("Heat sources: ")
                                .withStyle(ChatFormatting.GRAY)
                                .append(Component.literal(String.format("%,d", activeBlocks))
                                                .withStyle(ChatFormatting.YELLOW)),
                                false);

                source.sendSuccess(() -> Component.literal("Active cells: ")
                                .withStyle(ChatFormatting.GRAY)
                                .append(Component.literal(String.valueOf(propagating))
                                                .withStyle(propagating > 0 ? ChatFormatting.YELLOW
                                                                : ChatFormatting.GREEN)),
                                false);

                source.sendSuccess(() -> Component.literal("Chunks pending scan: ")
                                .withStyle(ChatFormatting.GRAY)
                                .append(Component.literal(String.valueOf(dirtyChunks))
                                                .withStyle(ChatFormatting.WHITE)),
                                false);

                FluidSimulationManager fluid = getFluidSimulation(source);
                if (fluid != null) {
                        boolean enabled = Thermodynamica.getInstance().getConfigManager()
                                        .getSettings().isFluidSimulationEnabled();
                        source.sendSuccess(() -> Component.literal("Fluid sim: ")
                                        .withStyle(ChatFormatting.GRAY)
                                        .append(Component.literal(enabled ? "ENABLED" : "DISABLED")
                                                        .withStyle(enabled ? ChatFormatting.GREEN : ChatFormatting.RED)),
                                        false);
                        source.sendSuccess(() -> Component.literal("Fluid air cells: ")
                                        .withStyle(ChatFormatting.GRAY)
                                        .append(Component.literal(String.format("%,d active / %,d stored",
                                                        fluid.getActiveCellCount(), fluid.getGridSize()))
                                                        .withStyle(ChatFormatting.AQUA)),
                                        false);
                        source.sendSuccess(() -> Component.literal("Fluid last step: ")
                                        .withStyle(ChatFormatting.GRAY)
                                        .append(Component.literal(String.format("%d cells, %d changed, %.2f ms",
                                                        fluid.getLastCellsProcessed(),
                                                        fluid.getLastChangedCells(),
                                                        fluid.getLastSimulationTimeMs()))
                                                        .withStyle(ChatFormatting.WHITE)),
                                        false);
                }

                return 1;
        }

        private static int executeReset(CommandContext<CommandSourceStack> ctx) {
                CommandSourceStack source = ctx.getSource();
                source.sendSuccess(() -> Component.literal("Performance counters reset.")
                                .withStyle(ChatFormatting.GREEN), true);
                return 1;
        }

        private static int executeDebug(CommandContext<CommandSourceStack> ctx) {
                HeatSimulationManager sim = getSimulation(ctx);
                if (sim == null)
                        return 0;

                CommandSourceStack source = ctx.getSource();
                ServerPlayer player = source.getPlayer();
                if (player == null) {
                        source.sendFailure(Component.literal("Must be run by a player")
                                        .withStyle(ChatFormatting.RED));
                        return 0;
                }

                boolean enabled = HeatDebugPlayers.toggle(player);
                HeatSyncManager.forceResync(player);
                HeatNetwork.CHANNEL.send(
                                PacketDistributor.PLAYER.with(() -> player),
                                new HeatDebugOverlayPacket(enabled));

                BlockPos pos = player.blockPosition();
                ResourceLocation dim = player.level().dimension().location();
                String debugInfo = sim.getNearestSourceDebug(dim, pos);

                MutableComponent header = Component.literal("=== Thermodynamica Debug ===")
                                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
                source.sendSuccess(() -> header, false);

                source.sendSuccess(() -> Component.literal("Energy overlay: ")
                                .withStyle(ChatFormatting.GRAY)
                                .append(Component.literal(enabled ? "ON" : "OFF")
                                                .withStyle(enabled ? ChatFormatting.GREEN : ChatFormatting.RED))
                                .append(Component.literal(enabled
                                                ? "  (0 J black → 5 MJ white; cold=blue, hot=red)"
                                                : "")
                                                .withStyle(ChatFormatting.DARK_GRAY)),
                                false);

                source.sendSuccess(() -> Component.literal(debugInfo)
                                .withStyle(ChatFormatting.WHITE), false);

                return 1;
        }

        private static int executeFluidDebug(CommandContext<CommandSourceStack> ctx) {
                FluidSimulationManager fluid = getFluidSimulation(ctx.getSource());
                if (fluid == null) {
                        ctx.getSource().sendFailure(Component.literal("Fluid simulation is not running.")
                                        .withStyle(ChatFormatting.RED));
                        return 0;
                }

                CommandSourceStack source = ctx.getSource();
                ServerPlayer player = source.getPlayer();
                if (player == null) {
                        source.sendFailure(Component.literal("Must be run by a player")
                                        .withStyle(ChatFormatting.RED));
                        return 0;
                }

                boolean simEnabled = Thermodynamica.getInstance().getConfigManager()
                                .getSettings().isFluidSimulationEnabled();
                boolean enabled = FluidDebugPlayers.toggle(player);
                FluidSyncManager.forceResync(player);
                HeatNetwork.CHANNEL.send(
                                PacketDistributor.PLAYER.with(() -> player),
                                new FluidDebugOverlayPacket(enabled));

                BlockPos pos = player.blockPosition();
                ResourceLocation dim = player.level().dimension().location();
                OptionalDouble temp = fluid.getAirTemperature(dim, pos.asLong());
                OptionalDouble pressure = fluid.getAirPressure(dim, pos.asLong());
                Vec3 velocity = fluid.getAirVelocity(dim, pos.asLong());

                MutableComponent header = Component.literal("=== Thermodynamica Fluid Debug ===")
                                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
                source.sendSuccess(() -> header, false);

                source.sendSuccess(() -> Component.literal("Fluid sim config: ")
                                .withStyle(ChatFormatting.GRAY)
                                .append(Component.literal(simEnabled ? "ENABLED" : "DISABLED")
                                                .withStyle(simEnabled ? ChatFormatting.GREEN : ChatFormatting.RED)),
                                false);

                source.sendSuccess(() -> Component.literal("Air overlay: ")
                                .withStyle(ChatFormatting.GRAY)
                                .append(Component.literal(enabled ? "ON" : "OFF")
                                                .withStyle(enabled ? ChatFormatting.GREEN : ChatFormatting.RED))
                                .append(Component.literal(enabled
                                                ? "  (air temp: cold=blue, hot=red)"
                                                : "")
                                                .withStyle(ChatFormatting.DARK_GRAY)),
                                false);

                source.sendSuccess(() -> Component.literal(String.format(
                                "Standing air @ %s: T=%s  P=%s  v=(%.3f, %.3f, %.3f)",
                                pos.toShortString(),
                                temp.isPresent() ? String.format("%.2f C", temp.getAsDouble()) : "n/a",
                                pressure.isPresent() ? String.format("%.1f Pa", pressure.getAsDouble()) : "n/a",
                                velocity.x, velocity.y, velocity.z))
                                .withStyle(ChatFormatting.WHITE), false);

                source.sendSuccess(() -> Component.literal(String.format(
                                "Grid: %,d active / %,d stored | last step %d cells (%.2f ms)",
                                fluid.getActiveCellCount(), fluid.getGridSize(),
                                fluid.getLastCellsProcessed(), fluid.getLastSimulationTimeMs()))
                                .withStyle(ChatFormatting.AQUA), false);

                return 1;
        }

        private static HeatSimulationManager getSimulation(CommandContext<CommandSourceStack> ctx) {
                Thermodynamica instance = Thermodynamica.getInstance();
                if (instance == null || instance.getSimulationManager() == null) {
                        ctx.getSource().sendFailure(Component.literal("Thermodynamica simulation is not running.")
                                        .withStyle(ChatFormatting.RED));
                        return null;
                }
                return instance.getSimulationManager();
        }

        private static FluidSimulationManager getFluidSimulation(CommandSourceStack source) {
                Thermodynamica instance = Thermodynamica.getInstance();
                if (instance == null)
                        return null;
                return instance.getFluidSimulationManager();
        }
}
