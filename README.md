# S1ind Optimizer

A lightweight, targeted performance mod for **Minecraft Java 1.21.11 (Fabric)**.

Designed for complete, zero-conflict compatibility with **Sodium**, **ImmediatelyFast**, **Iris**, and **Lithium**.

---

## ⚡ What it actually does

Unlike heavy mods that attempt to rewrite Minecraft's entire rendering pipeline, **S1ind Optimizer** targets specific high-impact bottlenecks without interfering with Sodium's Vulkan/OpenGL rendering:

1. **Render Thread Priority Elevation (`MinecraftClientMixin`)**  
   Forces the OS scheduler to treat the Minecraft Render Thread with `Thread.MAX_PRIORITY` (10). Prevents frame drops, micro-stutters, and GPU starvation caused by background operating system tasks.

2. **Distant Passive Mob AI Throttling (`MobEntityMixin`)**  
   Distant passive entities (cows, sheep, pigs, chickens, villagers) further than 32 blocks from any player skip 2 out of 3 pathfinding/sensing ticks (`tickNewAi`). Hostile mobs (Zombies, Creepers, Skeletons) and mobs in combat are **100% untouched**. Reduces CPU pathfinding overhead near animal pens and villages.

3. **Lighting Update Fast-Path (`LightingProviderMixin`)**  
   Checks `hasUpdates()` before traversing lighting providers. If no light changes are queued, returns `0` immediately without iterating through block and sky light loops.

4. **Background Worker Budgeting (`UtilMixin`)**  
   Allows user configuration of the background worker thread pool used by Minecraft for chunk generation, loading, and world meshing via `config/s1ind_opt.json`.

5. **Memory Pressure Watchdog (`MemoryCleaner`)**  
   Monitors heap utilization on a low-priority background daemon thread. Instead of spamming `System.gc()` every few seconds (which causes frame freezes), it proactively runs garbage collection only when used heap memory crosses the configured threshold (default: 85%) to prevent `OutOfMemoryError` crashes.

---

## ⚙️ Configuration (`config/s1ind_opt.json`)

The config file is generated automatically on first launch:

```json
{
  "boostRenderThreadPriority": true,
  "throttleDistantPassiveMobAi": true,
  "fastLightUpdates": true,
  "autoMemoryClean": true,
  "memoryCheckIntervalSeconds": 30,
  "memoryPressureThresholdPercent": 85,
  "backgroundThreadCount": 0
}
```

- `boostRenderThreadPriority`: Elevates render thread priority to 10.
- `throttleDistantPassiveMobAi`: Skips redundant AI updates for distant animals.
- `fastLightUpdates`: Early-exit on empty lighting queue.
- `autoMemoryClean`: Activates the background memory watchdog.
- `memoryCheckIntervalSeconds`: Frequency of memory pressure checks in seconds.
- `memoryPressureThresholdPercent`: Heap percentage threshold (50–95%) that triggers reclamation.
- `backgroundThreadCount`: `0` = auto (CPU cores - 1).

---

## 📦 Downloads & Installation

1. Install **Fabric Loader** for **Minecraft 1.21.11**.
2. Download the pre-built jar from [`build/libs/s1ind-optimizer-2.4.0.jar`](build/libs/s1ind-optimizer-2.4.0.jar).
3. Place `s1ind-optimizer-2.4.0.jar` into your `.minecraft/mods` folder.
4. (Optional) Install alongside **Sodium**, **ImmediatelyFast**, and **Lithium** for maximum performance.

---

## 🔨 Building from Source

Requirements:
- **JDK 25** (or compatible modern JDK)
- Fabric Loom 1.18.4+

```bash
git clone https://github.com/<username>/s1ind-optimizer.git
cd s1ind-optimizer
./gradlew build
```

Compiled `.jar` will be in `build/libs/`.

---

## 📄 License

This project is licensed under the [MIT License](LICENSE).
