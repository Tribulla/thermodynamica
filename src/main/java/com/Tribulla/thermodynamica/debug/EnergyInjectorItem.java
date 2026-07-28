package com.Tribulla.thermodynamica.debug;

import com.Tribulla.thermodynamica.api.HeatAPI;
import com.Tribulla.thermodynamica.api.ThermalProperties;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.List;
import java.util.OptionalDouble;

public class EnergyInjectorItem extends Item {

    private static final String TAG_AMOUNT_INDEX = "EnergyIndex";

    public static final double[] ENERGY_PRESETS = {
            100.0,
            500.0,
            1_000.0,
            5_000.0,
            10_000.0,
            50_000.0,
            100_000.0,
            500_000.0,
            1_000_000.0
    };

    public EnergyInjectorItem(Properties properties) {
        super(properties);
    }

    public static int getAmountIndex(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(TAG_AMOUNT_INDEX))
            return 2; // default 1 kJ
        int index = tag.getInt(TAG_AMOUNT_INDEX);
        if (index < 0 || index >= ENERGY_PRESETS.length)
            return 2;
        return index;
    }

    public static double getEnergyJoules(ItemStack stack) {
        return ENERGY_PRESETS[getAmountIndex(stack)];
    }

    public static void setAmountIndex(ItemStack stack, int index) {
        int wrapped = Math.floorMod(index, ENERGY_PRESETS.length);
        stack.getOrCreateTag().putInt(TAG_AMOUNT_INDEX, wrapped);
    }

    public static String formatEnergy(double joules) {
        if (joules >= 1_000_000.0)
            return String.format("%.0f MJ", joules / 1_000_000.0);
        if (joules >= 1_000.0)
            return String.format("%.0f kJ", joules / 1_000.0);
        return String.format("%.0f J", joules);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        Player player = context.getPlayer();
        ItemStack stack = context.getItemInHand();

        if (player == null)
            return InteractionResult.PASS;

        if (level.isClientSide)
            return InteractionResult.SUCCESS;

        if (!(player instanceof ServerPlayer serverPlayer) || !serverPlayer.isCreative()) {
            player.displayClientMessage(Component.literal("§cEnergy Injector requires Creative mode"), true);
            return InteractionResult.FAIL;
        }

        BlockState state = level.getBlockState(pos);
        if (state.isAir())
            return InteractionResult.PASS;

        ResourceLocation blockId = state.getBlock().builtInRegistryHolder().key().location();
        HeatAPI api = HeatAPI.get();
        ThermalProperties props = api.getThermalProperties(blockId);
        double cp = props.getHeatCapacity();
        if (cp <= 0.0 || !Double.isFinite(cp)) {
            player.displayClientMessage(Component.literal("§cBlock has invalid heat capacity"), true);
            return InteractionResult.FAIL;
        }

        double energy = getEnergyJoules(stack);
        if (player.isShiftKeyDown())
            energy = -energy;

        double dT = energy / cp;
        double biome = api.getBiomeOffset(level, pos);
        OptionalDouble simulated = api.getSimulatedCelsius(level, pos);
        double currentGrid = simulated.isPresent()
                ? simulated.getAsDouble() - biome
                : api.getAmbientTemperature();
        double newGrid = currentGrid + dT;

        api.setTransientTemperature(level, pos, newGrid);

        player.displayClientMessage(Component.literal(String.format(
                "§6%s §e%s §7→ §b%.1f °C §8(ΔT %+.2f, Cp=%.0f)",
                energy >= 0 ? "Injected" : "Extracted",
                formatEnergy(Math.abs(energy)),
                newGrid + biome,
                dT,
                cp)), true);

        return InteractionResult.CONSUME;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        if (level.isClientSide)
            return InteractionResultHolder.success(stack);

        if (!player.isCreative()) {
            player.displayClientMessage(Component.literal("§cEnergy Injector requires Creative mode"), true);
            return InteractionResultHolder.fail(stack);
        }

        int current = getAmountIndex(stack);
        int next = player.isShiftKeyDown() ? current - 1 : current + 1;
        setAmountIndex(stack, next);

        player.displayClientMessage(Component.literal(
                "§eEnergy dose: §b" + formatEnergy(getEnergyJoules(stack))), true);

        return InteractionResultHolder.consume(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.literal("§7Dose: §b" + formatEnergy(getEnergyJoules(stack))));
        tooltip.add(Component.literal("§8Right-click block: inject energy"));
        tooltip.add(Component.literal("§8Sneak + right-click block: extract energy"));
        tooltip.add(Component.literal("§8Right-click air: cycle dose (sneak = previous)"));
        tooltip.add(Component.literal("§8ΔT = Q / heat capacity"));
    }
}
