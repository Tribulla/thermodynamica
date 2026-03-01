package com.Tribulla.thermodynamica.debug;

import com.Tribulla.thermodynamica.api.HeatAPI;
import com.Tribulla.thermodynamica.api.HeatTier;
import com.Tribulla.thermodynamica.api.ThermalProperties;
import com.Tribulla.thermodynamica.api.TierResolution;
import com.Tribulla.thermodynamica.network.DebugInfoPacket;
import com.Tribulla.thermodynamica.network.HeatNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.network.PacketDistributor;

public class HeatInspectorItem extends Item {

    public HeatInspectorItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();

        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }

        if (!(context.getPlayer() instanceof ServerPlayer player)) {
            return InteractionResult.PASS;
        }

        if (!player.isCreative()) {
            player.sendSystemMessage(Component.literal("§cHeat Inspector requires Creative mode"));
            return InteractionResult.FAIL;
        }

        BlockState state = level.getBlockState(pos);
        ResourceLocation blockId = state.getBlock().builtInRegistryHolder().key().location();

        HeatAPI api = HeatAPI.get();

        HeatTier tier = api.getResolvedTier(blockId);
        double celsius = api.getSimulatedCelsius(level, pos)
                .orElseGet(() -> api.getResolvedCelsius(blockId, level, pos));

        TierResolution resolution = api.resolveBlockTier(blockId);
        String source = resolution != null ? resolution.getSource().name() : "AMBIENT_DEFAULT";
        int priority = resolution != null ? resolution.getPriority() : 0;

        ThermalProperties props = api.getThermalProperties(blockId);

        player.sendSystemMessage(Component.literal("§6═══ Heat Inspector ═══"));
        player.sendSystemMessage(Component.literal("§7Block: §f" + blockId));
        player.sendSystemMessage(Component.literal("§7Tier: §e" + tier.name() + " §7(" + tier.getId() + ")"));
        player.sendSystemMessage(Component.literal(String.format("§7Temperature: §b%.1f °C", celsius)));
        player.sendSystemMessage(Component.literal("§7Source: §a" + source));
        player.sendSystemMessage(Component.literal("§7Priority: §d" + priority));
        player.sendSystemMessage(Component.literal(String.format("§7Conductivity: §f%.2f", props.getConductivity())));
        player.sendSystemMessage(Component.literal(String.format("§7Transfer Rate: §f%.2f", props.getTransferRate())));
        player.sendSystemMessage(
                Component.literal(String.format("§7Dissipation: §f%.4f /face/tick", props.getDissipationRate())));
        player.sendSystemMessage(
                Component.literal(String.format("§7Biome Offset: §f%.1f °C", api.getBiomeOffset(level, pos))));

        HeatNetwork.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new DebugInfoPacket(pos, tier.name(), celsius, source, priority,
                        props.getConductivity(), props.getDissipationRate()));

        return InteractionResult.SUCCESS;
    }
}
