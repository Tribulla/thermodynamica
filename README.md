# Thermodynamica

**Thermodynamica** is a high-performance, multi-threaded heat simulation library for Minecraft. It uses an optimized Breadth-First Search (BFS) engine to simulate realistic temperature diffusion across chunks, allowing for complex thermal interactions between blocks.

> [!IMPORTANT]
> The mod is a "naked" library. It provides the simulation engine but requires **Data Packs** to define which blocks are heat sources and how different materials conduct heat.

## 🚀 Features

- **Parallel BFS Engine**: Simulated heat spreads across multiple threads, ensuring minimal impact on server TPS.
- **Data Pack Driven**: Completely customize thermal properties and heat tiers without touching any code.
- **Dynamic Conductivity**: Different materials (Wool, Stone, Iron, etc.) have unique conductivity, transfer, and dissipation rates.
- **Ambient re-equilibration**: Blocks naturally cool down or heat up towards the biome's ambient temperature.
- **Create Mod Integration**: Optional support for Create mod heat sources and fluid pipes.

## 🛠️ Thermal Configuration Tool
A Python-based GUI tool is provided in the `tools/` directory to help you manage your thermal configurations.
- **Run**: `python tools/thermal_tool.py`
- **Features**: 
    - Adjust block properties (Conductivity, Transfer, Dissipation) visually.
    - Update existing data pack JSON files.
    - Generate brand new Data Packs with all required folder structures and metadata.

## 📁 Installation & Data Packs
To use the simulation, you must install at least one thermal Data Pack into your world's `datapacks` folder. Three standalone packs are provided at the project root:
- `thermodynamica_vanilla`: Comprehensive mappings for standard Minecraft blocks.
- `thermodynamica_create`: Thermal support for the Create mod.
- `thermodynamica_example`: A template to start your own custom configuration.

## ⌨️ Commands
- `/thermodynamica status` (or `/td status`): View engine metrics, active heat sources, and loaded chunks.
- `/thermodynamica tps`: View detailed performance stats of the simulation.
- `/thermodynamica debug`: Shows the thermal data of the block you are currently standing on.

## 📜 Credits
- **Tribulla**: Everything

---
*Developed for modern Minecraft versions using Forge.*
