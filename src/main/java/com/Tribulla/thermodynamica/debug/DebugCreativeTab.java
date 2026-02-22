package com.Tribulla.thermodynamica.debug;

import com.Tribulla.thermodynamica.Thermodynamica;
import com.Tribulla.thermodynamica.api.HeatTier;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

@Mod.EventBusSubscriber(modid = Thermodynamica.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class DebugCreativeTab {

    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB,
            Thermodynamica.MODID);

    public static final RegistryObject<CreativeModeTab> THERMODYNAMICA_TAB = TABS.register("thermodynamica_tab",
            () -> CreativeModeTab.builder()
                    .title(Component.literal("Thermodynamica"))
                    .icon(() -> new ItemStack(DebugRegistry.HEAT_INSPECTOR.get()))
                    .displayItems((params, output) -> {
                        // Add inspector
                        output.accept(DebugRegistry.HEAT_INSPECTOR.get());
                        // Add all heat source blocks
                        for (HeatTier tier : HeatTier.values()) {
                            RegistryObject<net.minecraft.world.item.Item> item = DebugRegistry.HEAT_SOURCE_ITEMS
                                    .get(tier);
                            if (item != null) {
                                output.accept(item.get());
                            }
                        }
                    })
                    .build());

    public static void register(net.minecraftforge.eventbus.api.IEventBus modBus) {
        TABS.register(modBus);
    }
}
