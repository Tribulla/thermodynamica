package com.Tribulla.thermodynamica.debug;

import com.Tribulla.thermodynamica.Thermodynamica;
import com.Tribulla.thermodynamica.api.HeatTier;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.EnumMap;
import java.util.Map;

public class DebugRegistry {

    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS,
            Thermodynamica.MODID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS,
            Thermodynamica.MODID);

    public static final Map<HeatTier, RegistryObject<Block>> HEAT_SOURCE_BLOCKS = new EnumMap<>(HeatTier.class);
    public static final Map<HeatTier, RegistryObject<Item>> HEAT_SOURCE_ITEMS = new EnumMap<>(HeatTier.class);

    public static final RegistryObject<Item> HEAT_INSPECTOR;

    static {
        for (HeatTier tier : HeatTier.values()) {
            String name = "heat_source_" + tier.getId();
            MapColor color = tierToColor(tier);

            RegistryObject<Block> block = BLOCKS.register(name,
                    () -> new HeatSourceBlock(tier,
                            BlockBehaviour.Properties.of()
                                    .mapColor(color)
                                    .strength(1.5f)
                                    .noOcclusion()));

            RegistryObject<Item> item = ITEMS.register(name,
                    () -> new BlockItem(block.get(),
                            new Item.Properties()));

            HEAT_SOURCE_BLOCKS.put(tier, block);
            HEAT_SOURCE_ITEMS.put(tier, item);
        }

        HEAT_INSPECTOR = ITEMS.register("heat_inspector",
                () -> new HeatInspectorItem(new Item.Properties().stacksTo(1)));
    }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        Thermodynamica.LOGGER.debug("Debug registries bound to mod bus");
    }

    private static MapColor tierToColor(HeatTier tier) {
        return switch (tier) {
            case NEG5 -> MapColor.COLOR_BLUE;
            case NEG4 -> MapColor.COLOR_LIGHT_BLUE;
            case NEG3 -> MapColor.COLOR_CYAN;
            case NEG2 -> MapColor.ICE;
            case NEG1 -> MapColor.QUARTZ;
            case ZERO -> MapColor.SNOW;
            case POS1 -> MapColor.COLOR_GREEN;
            case POS2 -> MapColor.COLOR_YELLOW;
            case POS3 -> MapColor.COLOR_ORANGE;
            case POS4 -> MapColor.COLOR_RED;
            case POS5 -> MapColor.COLOR_MAGENTA;
        };
    }
}
