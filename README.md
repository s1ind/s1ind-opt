# S1ind Optimizer v3.2.2 (Adaptive Performance Engine)

[![Release](https://img.shields.io/github/v/release/s1ind/s1ind-opt?style=flat-square)](https://github.com/s1ind/s1ind-opt/releases)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.11-blue?style=flat-square)](https://fabricmc.net/)
[![Fabric Loader](https://img.shields.io/badge/Fabric-0.19.5-green?style=flat-square)](https://fabricmc.net/)
[![Java](https://img.shields.io/badge/Java-21%2B-orange?style=flat-square)](https://adoptium.net/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg?style=flat-square)](LICENSE)

**S1ind Optimizer v3.2.2** — инженерная ревизия архитектуры, сфокусированная на надёжности многопоточного планирования, отсутствии внутритикового устаревания пространственного индекса, честной классификации узких мест и полном сохранении данных конфигурации.

---

## 🛠️ Ключевые инженерные исправления v3.2.2

### 1. Двухпоточный `AdaptiveTaskScheduler` (P0)
- Выделен отдельный исполнитель `S1ind-Scheduler-High` исключительно под `HIGH`-задачи, а фоновые `NORMAL`/`LOW` выполняются на `S1ind-Scheduler-Worker`: длительные `LOW`-задачи больше не блокируют приоритет `HIGH`.
- Полностью исключён `Thread.sleep()` — оркестрация построена на `ReentrantLock` и `Condition.awaitNanos()`.
- Защита от голодания (aging) проверяет возраст очередей `LOW`/`NORMAL` независимо от поступления новых `HIGH`/`NORMAL` задач.
- Переполнение `HIGH`-очереди (`>128`) возвращает явный `SubmissionStatus.REJECTED` без вытеснения уже принятых `HIGH`-задач.
- Поддержка coalescing по тегам (`COALESCED`), кооперативной отмены `TaskHandle`, и корректного завершения обоих потоков в `stop()` без обнуления живых ссылок.
- Разделены метрики F3: `Deferred Attempts`, `Unique Deferred`, `Currently Deferred`, `Rejected`.

### 2. Инкрементальная синхронизация `PlayerSpatialIndex` (P0)
- `EntitySpatialHookMixin` (`Entity.setPos` и `Entity.setRemoved` только для серверных игроков) мгновенно обновляет или мигрирует игрока между 32×32 бакетами при ходьбе, телепорте, смерти, респавне и смене измерения **внутри того же игрового тика**.
- Строгая граница `<= 16.0` блоков (`minDistSq <= 256.0`).

### 3. Защита осёдланных сущностей (P0)
- Через проверенный для Yarn 1.21.11 API `LivingEntity.hasStackEquipped(EquipmentSlot.SADDLE)` защищены свиньи с седлом (с пассажиром и без), страйдеры с седлом, лошади и весь применимый транспорт.
- Сохранены исключения для жителей, торговцев, големов (`GolemEntity`), приручённых, именных, на поводках, размножающихся и сторонних модифицированных сущностей.

### 4. Надёжность `GcMonitor`, `MemoryCleaner` и `AdaptivePerformanceEngine` (P0/P1)
- `GcMonitor` ведёт состояние регистрации независимо для каждого `GarbageCollectorMXBean` (`attachedByBean`): при частичном сбое последующие вызовы повторяют регистрацию только для не подключённых MXBean без дублирования слушателей; при остановке все слушатели снимаются. Чёткое разделение `GC Cycle Duration` и `STW Pause: N/A`.
- `MemoryCleaner` переведён в чисто диагностический режим мониторинга давления и темпа выделений Heap без принудительных вызовов `System.gc()` и без ложных заявлений про VRAM/текстуры.
- Устранено дублирование задач между `APE Worker` и `AdaptiveTaskScheduler`; `MODERATE`/`CRITICAL` MSPT интегрированного сервера теперь учитываются даже в фазе клиентского разогрева `INITIALIZING`.
- Переименовано: `CPU_BOUND` → `SERVER_TICK_BOUND`; добавлен `NO_CLEAR_BOTTLENECK`; `UNKNOWN` при нехватке данных никогда не возвращает `HIGH` confidence. Поддерживаются целевые бюджеты для `30`, `60`, `120`, `144`, `240` FPS и `VSync`.

### 5. Конфигурация `S1indConfig` (P1)
- `saveAsync()` синхронно снимает неизменяемый `ConfigSnapshot` — изменения после вызова не портят поставленную в очередь запись.
- Сериализованная запись под `FILE_WRITE_LOCK` через уникальные временные файлы (`Files.createTempFile`) + `ATOMIC_MOVE` (с fallback на `REPLACE_EXISTING`) и явным `CompletableFuture<SaveStatus>`.

---

## 🤝 Матрица совместимости

| Мод | Взаимодействие с v3.2.2 | Примечания |
| :--- | :--- | :--- |
| **Sodium / Embeddium** | Не перехватывает буферы мешей/рендера | Архитектурно совместим (требует живых FPS-замеров) |
| **Iris Shaders** | Не затрагивает шейдерный конвейер | Архитектурно совместим |
| **ImmediatelyFast** | Не модифицирует буферы HUD | Архитектурно совместим |
| **Lithium** | Пересечений по AI/потокам не внесено (использует свои миксины на тики/поиск пути; рекомендуем профиль `COMPATIBILITY` или отключение дублей при тестировании) | Требует совместного профилирования |
| **Starlight / Phosphor** | `S1indMixinPlugin` автоматически отключает `LightingProviderMixin` | Подтверждённое отключение по Mod ID |
| **Entity Culling / FerriteCore / ModernFix** | Не затрагивает куллинг видимости и дедупликацию моделей | Архитектурно совместим |

---

## 📊 Сборка и тестирование

- **Юнит-тесты:** 29 тестов JUnit 5 в 6 тестовых наборах (`./gradlew clean test build`).
- В релизный `s1ind-optimizer-3.2.2.jar` тестовые классы не включаются; все исходники тестов поставляются в `s1ind-optimizer-3.2.2-sources.jar`.

```bash
git clone https://github.com/s1ind/s1ind-opt.git
cd s1ind-opt
.\gradlew.bat clean test build
```
