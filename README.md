# S1ind Optimizer v3.0.0 (Adaptive Performance Engine)

[![Release](https://img.shields.io/github/v/release/s1ind/s1ind-opt?style=flat-square)](https://github.com/s1ind/s1ind-opt/releases)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.11-blue?style=flat-square)](https://fabricmc.net/)
[![Fabric Loader](https://img.shields.io/badge/Fabric-0.19.5-green?style=flat-square)](https://fabricmc.net/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg?style=flat-square)](LICENSE)

**S1ind Optimizer v3.0.0** — модульный оптимизатор Minecraft Java 1.21.11, построенный на архитектуре **Adaptive Performance Engine (APE)**.

Вместо слепого вмешательства в графический конвейер (где отлично работают Sodium и Iris), мод динамически отслеживает устойчивость фреймтайма и распределяет вычислительные ресурсы между потоком рендера, логическим тиком и фоновыми воркерами.

---

## 🚀 Архитектура APE v3.0 (Adaptive Performance Engine)

### 1. In-Engine телеметрия (F3 Debug HUD)
- Кольцевой буфер на 512 кадров с наносекундной точностью непрерывно рассчитывает:
  - **Avg FPS** (средняя частота кадров).
  - **1% Low FPS** (99-й перцентиль задержки — устойчивость динамических сцен).
  - **0.1% Low FPS** (99.9-й перцентиль — редкие микростаттеры и фризы).
  - **MSPT** (длительность серверного тика).
  - **GC Pause Time** (время остановок Stop-The-World через `GarbageCollectorMXBean`).
- Отображается прямо на экране F3:
  ```
  [S1ind APE v3.0] FPS: 144 | 1% Low: 112 | 0.1% Low: 86 | MSPT: 14.2ms | GC: 12ms | Stress: NORMAL
  ```

### 2. Динамическое регулирование нагрузки (Stress Levels)
Движок непрерывно вычисляет текущий стресс системы:
- `NORMAL` (FPS >= 52, MSPT <= 35ms): полная точность вычислений, стандартное фоновое расписание.
- `MODERATE` (FPS 35–52, MSPT 35–45ms): умеренное сжатие очередей AI и сброс некритичных вычислений.
- `CRITICAL` (FPS < 35, MSPT > 45ms): максимальный сброс AI дальних мобов и приоритет игровому циклу.

### 3. Трёхуровневый троттлинг пассивных мобов (`MobEntityMixin`)
В отличие от наивных модов, выключающих тики целиком:
- **Зона взаимодействия (<16 блоков):** 100% честный ванильный тик (торговля, кормление, реакция на игрока).
- **Средняя зона (16–32 блока):** 100% при норме; 50% сброс только при критической просадке FPS.
- **Дальняя зона (>32 блоков):** адаптивный пропуск от 50% до 75% вызовов `tickNewAi`.
- **Гарантии безопасности:**
  - Враждебные мобы **никогда не замедляются**.
  - Именные животные (`hasCustomName`) сохраняют 100% тиков.
  - Животные на поводке (`isLeashed`), в лодках/вагонетках или под седлом **никогда не замедляются**.
  - Спаривающиеся животные (`isInLove`) и мобы в панике/бою не трогаются.

### 4. Честный баланс потоков планировщика
- Потоку рендера назначается `Thread.MAX_PRIORITY` (10), давая преимущество в квантах времени планировщика ОС.
- Фоновым работникам (`ForkJoinWorkerThread` и `IO-Worker`) фабрично выставляется `Thread.NORM_PRIORITY - 1` (4).
- Для 2-ядерных CPU выделяется ровно 1 фоновый поток, предотвращая голодание видеокарты.

### 5. Безопасность памяти
- Навязчивый спам `System.gc()` каждые 15 секунд полностью устранён.
- Опциональный сторож памяти активируется только при одновременном выполнении условий: куча >88% и свободного остатка менее 250 МБ.

---

## ⚙️ Профили оптимизации (`config/s1ind_opt.json`)

В v3.0.0 доступны три готовых пресета:
- `"profile": "BALANCED"` (Рекомендуемый): умная адаптивная оптимизация с полным сохранением ванильной механики.
- `"profile": "PERFORMANCE"`: агрессивный троттлинг дальних мобов для слабых процессоров.
- `"profile": "COMPATIBILITY"`: минимальное вмешательство, отключение всех хуков кроме телеметрии.

```json
{
  "profile": "BALANCED",
  "enableAdaptiveEngine": true,
  "enableF3Telemetry": true,
  "boostRenderThreadPriority": true,
  "lowerBackgroundThreadPriority": true,
  "backgroundThreadCount": 0,
  "throttleDistantPassiveMobAi": true,
  "passiveMobDistanceThreshold": 32.0,
  "fastLightUpdates": true,
  "autoMemoryClean": false,
  "memoryCheckIntervalSeconds": 30,
  "memoryPressureThresholdPercent": 88
}
```

---

## 🤝 Совместимость

Мод полностью совместим со следующим стеком:
- **Sodium / Embeddium**
- **Iris Shaders / Oculus**
- **ImmediatelyFast**
- **Lithium**
- **Entity Culling**
- **FerriteCore / ModernFix**

---

## 🔨 Сборка

```bash
git clone https://github.com/s1ind/s1ind-opt.git
cd s1ind-opt
./gradlew build
```

Готовые файлы находятся в каталоге `build/libs/`.

---

## 📄 Лицензия

MIT License (c) 2026 s1ind
