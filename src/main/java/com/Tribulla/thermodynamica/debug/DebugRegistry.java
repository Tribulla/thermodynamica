package com.Tribulla.thermodynamica.debug;

import com.Tribulla.thermodynamica.Thermodynamica;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class DebugRegistry {

    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS,
            Thermodynamica.MODID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS,
            Thermodynamica.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES,
            Thermodynamica.MODID);

    public static final RegistryObject<Block> VARIABLE_HEAT_BLOCK = BLOCKS.register("variable_heat_block",
            () -> new VariableHeatBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_RED)
                    .strength(1.5f)
                    .noOcclusion()));

    public static final RegistryObject<Item> VARIABLE_HEAT_ITEM = ITEMS.register("variable_heat_block",
            () -> new BlockItem(VARIABLE_HEAT_BLOCK.get(), new Item.Properties()));

    public static final RegistryObject<BlockEntityType<VariableHeatBlockEntity>> VARIABLE_HEAT_BLOCK_ENTITY = BLOCK_ENTITIES.register("variable_heat_block",
            () -> BlockEntityType.Builder.of(VariableHeatBlockEntity::new, VARIABLE_HEAT_BLOCK.get()).build(null));

    public static final RegistryObject<Item> HEAT_INSPECTOR = ITEMS.register("heat_inspector",
            () -> new HeatInspectorItem(new Item.Properties().stacksTo(1)));

    public static final RegistryObject<Item> ENERGY_INJECTOR = ITEMS.register("energy_injector",
            () -> new EnergyInjectorItem(new Item.Properties().stacksTo(1)));

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        Thermodynamica.LOGGER.debug("Debug registries bound to mod bus");
    }
}

