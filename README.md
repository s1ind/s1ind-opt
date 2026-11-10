# S1ind Optimizer v3.1.0 (Adaptive Performance Engine)

[![Release](https://img.shields.io/github/v/release/s1ind/s1ind-opt?style=flat-square)](https://github.com/s1ind/s1ind-opt/releases)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.11-blue?style=flat-square)](https://fabricmc.net/)
[![Fabric Loader](https://img.shields.io/badge/Fabric-0.19.5-green?style=flat-square)](https://fabricmc.net/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg?style=flat-square)](LICENSE)

**S1ind Optimizer v3.1.0** — инженерно выверенный оптимизатор для **Minecraft Java 1.21.11 (Fabric)** с событийным мониторингом JVM, математически обоснованной телеметрией фреймтайма и гистерезисным управлением нагрузкой.

---

## 🛠️ Что изменено и проверено в v3.1.0

### 1. Кольцевой буфер на 20 000 кадров с фоновым расчётом (`AdaptivePerformanceEngine`)
- **Исправлен недостаток 512 кадров:** размер выборки увеличен до **20 000 кадров**.
  - `1% Low` строится по 200 медленнейшим кадрам (p99).
  - `0.1% Low` строится по 20 медленнейшим кадрам (p99.9).
- **Нулевая нагрузка на рендер (`O(1)`):** поток рендера только записывает наносекундную дельту в циклический массив. Тяжёлая сортировка `Arrays.sort()` вынесена в изолированный фоновый демон `S1ind-Telemetry-Worker` с приоритетом `MIN_PRIORITY`.

### 2. Анти-флаппинг гистерезис и определение узких мест (Bottleneck Attribution)
- Введены задержки переключения состояний (**Dwell Timers**):
  - Эскалация нагрузки (`NORMAL -> MODERATE -> CRITICAL`) происходит, только если просадка держится непрерывно **>= 3.0 секунд**.
  - Сброс обратно к `NORMAL` требует стабильного запаса производительности в течение **>= 5.0 секунд** с более высоким порогом восстановления.
- Различение причины тормозов (`BottleneckType`):
  - `CPU_TICK_BOUND`: серверный тик >38ms, при этом кадр рисуется быстро.
  - `RENDER_GPU_BOUND`: кадр >22ms (<45 FPS), при нормальном тике логики.
  - `GC_BOUND`: событийная детекция недавней паузы GC >=50ms.

### 3. Событийный мониторинг GC (`GcMonitor`)
- Отказ от усреднённого счётчика `getCollectionTime()`.
- Используется `NotificationEmitter` + `GarbageCollectionNotificationInfo`: подписка на реальные события завершения циклов сборки мусора с точным временем конкретной паузы и её причиной (`lastPauseMs`, `maxRecentPauseMs`).

### 4. Честный MSPT для одиночной игры и серверов (`MinecraftServerMixin` + `PerformanceTelemetry`)
- В одиночной игре замеряется реальная длительность `MinecraftServer.tick()`.
- На удалённых серверах (`!isIntegratedServerRunning()`) в F3 явно пишется `MSPT: Remote`, без фиктивных вычислений на стороне клиента.

### 5. Безопасный планировщик AI мобов (Heuristic-Guarded)
Вместо заявлений о «100% безопасности» реализована чёткая система исключений:
- Враждебные мобы (`!PassiveEntity`) — **не трогаются**.
- Жители и торговцы (`MerchantEntity`) — защищены по умолчанию (`protectVillagerAi = true`), чтобы не нарушать торговые залы и фермы железа.
- Сущности из сторонних модов (`!namespace.equals("minecraft")`) — защищены по умолчанию (`protectModdedEntities = true`).
- Приручённые (`isTamed`), именные (`hasCustomName`), привязанные поводком (`isLeashed`), оседланные и спаривающиеся животные (`isInLove`) — **всегда 100% ванильный тик**.
- Ближний радиус (`< 16` блоков) — **всегда 100% ванильный тик**.

---

## 📊 Сводная телеметрия на экране F3

```text
[S1ind APE v3.1] FPS: 144 (6.9ms) | 1% Low: 118 | 0.1% Low: 92 (n=20000)
[S1ind Metrics] MSPT: 11.4ms | GC Pause: 4ms (Max: 12ms) | Stress: NORMAL [BALANCED]
```

---

## 🧪 Автоматическое тестирование (JUnit 5)

Алгоритмы статистики перцентилей, гистерезиса переходов и детекции узких мест покрыты юнит-тестами (`./gradlew test`):
- `test20kFrameRingBufferPercentiles()` — верификация 1% и 0.1% Low по 20 000 кадров.
- `testAntiFlappingHysteresisAndDwell()` — проверка защиты от дребезга состояний на кратковременном скачке лага.
- `testBottleneckAttributionCpuVsRender()` — проверка разделения `CPU_TICK_BOUND` и `RENDER_GPU_BOUND`.

---

## 📦 Сборка и установка

- **Требования:** Minecraft 1.21.11, Fabric Loader >=0.19.0, Java 21+.
- **Совместимость:** Архитектурно спроектирован для совместной работы с Sodium, Iris, ImmediatelyFast, Lithium, Entity Culling и FerriteCore (требует комплексного игрового профилирования на конкретных сборках модов).

```bash
git clone https://github.com/s1ind/s1ind-opt.git
cd s1ind-opt
./gradlew test build
```
