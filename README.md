# S1ind Optimizer v3.2.1 (Adaptive Performance Engine)

[![Release](https://img.shields.io/github/v/release/s1ind/s1ind-opt?style=flat-square)](https://github.com/s1ind/s1ind-opt/releases)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.11-blue?style=flat-square)](https://fabricmc.net/)
[![Fabric Loader](https://img.shields.io/badge/Fabric-0.19.5-green?style=flat-square)](https://fabricmc.net/)
[![Java](https://img.shields.io/badge/Java-21%2B-orange?style=flat-square)](https://adoptium.net/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg?style=flat-square)](LICENSE)

**S1ind Optimizer v3.2.1** — оптимизатор для **Minecraft Java 1.21.11 (Fabric)** с адаптивным ядром **Adaptive Performance Engine (APE)**, полностью разделёнными клиентскими/серверными контурами телеметрии, событийным мониторингом GC и строгими гарантиями безопасности игровых механик.

---

## 🛠️ Ключевые компоненты v3.2.1

### 1. Adaptive Performance Engine (APE)
- **Поддержка всех окружений:** запускается в `S1indOptMod.onInitialize()` как на клиенте, так и на выделенном сервере (`Dedicated Server`); корректно останавливает все фоновые потоки в `MinecraftClientMixin.stop` и `MinecraftServerMixin.shutdown`.
- **Разделённые контуры:** на выделенном сервере без клиентских классов нагрузка оценивается исключительно по `MSPT`; на клиенте объединяются локальный `MSPT` (если открыт мир) и динамика времени кадров с учётом пользовательского `FPS Cap` и `VSync`.
- **Anti-Flapping:** монотонный гистерезис (`System.nanoTime()`) с раздельными порогами входа/выхода и таймером удержания (3.0s для `BALANCED`, 1.5s для `PERFORMANCE` на эскалацию; 5.0s на восстановление).

### 2. Кольцевой буфер 20 000 кадров и честные перцентили
- O(1) ping-pong батчинг на Render Thread (без сортировки, `String.format` и ожидания локов).
- Предаллоцированный буфер на **20 000 кадров** и статический `sortWorkspace` (сортировка в приоритет-1 потоке).
- **1% Low** рассчитывается как честное арифметическое среднее **худших `count / 100` (200) кадров**.
- **0.1% Low** рассчитывается как честное арифметическое среднее **худших `count / 1000` (20) кадров**.
- Явные состояния готовности: `INITIALIZING` (<500 кадров — Low-метрики скрыты), `PARTIAL` (500–19 999), `STABLE` (20 000).
- F3 HUD потребляет готовый снимок `TelemetrySnapshot`, обновляемый вне потока отрисовки каждые ~500 мс.

### 3. Реактивный `AdaptiveTaskScheduler`
- Три ограниченные очереди (`HIGH`, `NORMAL`, `LOW`, по 128 слотов) на `ReentrantLock` + `Condition.awaitNanos()` — **без `Thread.sleep()`** внутри планировщика.
- Задачи `HIGH` мгновенно пробуждают диспетчер и выполняются первыми даже при критическом стрессе.
- Задачи `LOW` откладываются при `CRITICAL` стрессе, но защищены от бесконечного голодания порогом старения (4.0 секунды).
- Подключенные задачи: обновление снимков телеметрии (`Telemetry`), ротация окна GC (`GcMonitor`), асинхронное сохранение конфигурации (`S1indConfig`), сторож кучи (`MemoryCleaner`).

### 4. Событийный `GcMonitor` (JMX)
- Независимая, идемпотентная и отказоустойчивая регистрация на каждом доступном `GarbageCollectorMXBean` с очисткой при остановке.
- Монотонное окно 60 секунд: отслеживает `Last Cycle Duration`, `Max (60s)`, `Total (60s)`, `Cycles/min` и общее число событий.
- Честно разделяет `GC Cycle Duration` (`GcInfo.getDuration()`) и `STW Pause` (выводится как `N/A`, когда сборщик не даёт чистую паузу mutator-потоков).

### 5. Безопасная оптимизация AI мобов (`MobAiPolicy` + `PlayerSpatialIndex`)
- **Зона <= 16.0 блоков (включая границу ровно 16.0):** 100% ванильный тик на каждом тике, без устаревающего 32-тикового кеша.
- **Пространственный индекс (`PlayerSpatialIndex`):** при >8 игроках использует регионные бакеты `32x32` блока с пулингом буферов, исключая деградацию `O(мобы × игроки)`.
- **Безусловные исключения:** враждебные мобы, жители и торговцы (`MerchantEntity`), големы (`GolemEntity`), модифицированные сущности (`!minecraft`), приручённые (`isTamed`), именные (`hasCustomName`), оседланные, на поводке, в размножении (`isInLove`), а также в бою/панике.

### 6. Профили и надёжность конфигурации (`S1indConfig`)
- **Профили:** `BALANCED` (32b / 3s), `PERFORMANCE` (24b / 1.5s / жёсткий лимит воркеров <=4 даже при ручной настройке), `COMPATIBILITY` (отключает все вмешательства в потоки/AI/свет, полностью сохраняя телеметрию APE и F3).
- Атомарная запись через `.tmp` + `ATOMIC_MOVE`, асинхронное сохранение (`saveAsync`), резервная копия `.bak` при битом JSON, устойчивость к пустым файлам и неизвестным значениям enum без сброса валидных настроек.

---

## 🤝 Матрица совместимости (архитектурный аудит)

| Мод | Взаимодействие с S1ind v3.2.1 | Статус |
| :--- | :--- | :--- |
| **Sodium / Embeddium** | S1ind не изменяет буферы мешей и пайплайн рендера чанков | Архитектурно совместим (требует живых FPS-замеров) |
| **Iris Shaders** | Шейдерный пайплайн не затрагивается | Архитектурно совместим |
| **ImmediatelyFast** | Буферы HUD/Batching не перехватываются | Архитектурно совместим |
| **Lithium** | `LightingProviderMixin` затрагивает лишь пустой fast-path `hasUpdates()` | Требует совместного игрового профилирования |
| **Starlight / Phosphor** | Автоматически отключает `LightingProviderMixin` в `S1indMixinPlugin` | Безопасная изоляция |
| **Entity Culling** | Не затрагивает видимость/рендер-куллинг сущностей | Архитектурно совместим |
| **FerriteCore / ModernFix** | Не модифицирует дедупликацию моделей/блоков; `System.gc()` отключён по умолчанию | Архитектурно совместим |

---

## 📊 Юнит-тестирование (JUnit 5)

Проект содержит 6 специализированных тестовых наборов в `src/test/java/` (и включённых в `-sources.jar`):
- `FrameTimeTelemetryTest` (6 тестов)
- `AdaptivePerformanceEngineLifecycleTest` (4 теста)
- `AdaptiveTaskSchedulerTest` (6 тестов)
- `GcMonitorTest` (2 теста)
- `MobAiPolicyAndSpatialTest` (3 теста)
- `S1indConfigTest` (4 теста)

---

## 🔨 Сборка

```bash
git clone https://github.com/s1ind/s1ind-opt.git
cd s1ind-opt
./gradlew clean test build
```
