# S1ind Optimizer v3.2.0 (Adaptive Performance Engine)

[![Release](https://img.shields.io/github/v/release/s1ind/s1ind-opt?style=flat-square)](https://github.com/s1ind/s1ind-opt/releases)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.11-blue?style=flat-square)](https://fabricmc.net/)
[![Fabric Loader](https://img.shields.io/badge/Fabric-0.19.5-green?style=flat-square)](https://fabricmc.net/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg?style=flat-square)](LICENSE)

**S1ind Optimizer v3.2.0** — инженерная ревизия архитектуры, полностью устраняющая технический долг v3.1.0 по результатам аудита: работа на выделенных серверах, истинная математика худших 200/20 кадров, нулевые аллокации при фоновой сортировке, защита зоны 16 блоков без устаревшего кеша и честный профиль совместимости.

---

## 🛠️ Что исправлено и переработано в v3.2.0

### 1. Поддержка выделенного сервера (Dedicated Server APE)
- **Исправлено:** запуск `AdaptivePerformanceEngine` и `AdaptiveTaskScheduler` перенесён в общий `ModInitializer` (`S1indOptMod.onInitialize()`).
- В безголовом (headless) режиме выделенного сервера стресс рассчитывается исключительно по серверному `MSPT`, не ожидая клиентских кадров.

### 2. Честная математика 1% / 0.1% Low и режим разогрева
- **Исправлено:** вместо одного пограничного элемента теперь вычисляется **истинное среднее время худших `count / 100` (200) кадров** для `1% Low` и **худших `count / 1000` (20) кадров** для `0.1% Low`.
- Введён статус **`WARMING UP (n/500)`**, пока не накоплено достаточно статистики.
- **Учёт лимита FPS игрока:** если пользователь вручную ограничил FPS (например, 30 FPS или VSync), движок масштабирует порог стресса под фактический потолок и не уходит в ложный `CRITICAL`.
- **Zero-Lock & Zero-Alloc:** входящие кадры пишутся в ping-pong буфер без ожидания сортировки; рабочий массив `sortWorkspace[20_000]` выделяется один раз при запуске (0 байт/сек мусора в установившемся режиме).

### 3. Гарантия 16-блочной зоны взаимодействия в реальном времени (`MobEntityMixin`)
- **Исправлено:** убран устаревающий кеш дистанции на 32 тика.
- Индексированный цикл без аллокаций проверяет квадрат расстояния до активных игроков (не в спектаторе) на каждом тике. Приближение игрока на `<= 16` блоков отменяет любые ограничения на **том же тике**.
- **Исключения:** враждебные мобы, жители и торговцы (`protectVillagerAi=true`), модифицированные сущности (`protectModdedEntities=true`), приручённые, именные, оседланные, привязанные, размножающиеся и паникующие мобы.

### 4. Реальное разделение профилей (`S1indConfig` + `S1indMixinPlugin`)
- **`COMPATIBILITY`:** отключает любые вмешательства в приоритеты потоков, бюджет воркеров (`UtilMixin`), AI мобов и fast-path света, но сохраняет `MinecraftClientMixin` и `DebugHudMixin` для полноценной телеметрии.
- **`BALANCED`:** дистанция троттлинга пассивных мобов 32 блока, задержка эскалации 3.0s, консервативный лимит потоков.
- **`PERFORMANCE`:** дистанция 24 блока, быстрая эскалация 1.5s, жесткое ограничение фоновых потоков (макс. 4) для сохранения квантов под главный цикл.
- Добавлена защита от битого JSON (`s1ind_opt.json.bak` + безопасный откат к настройкам по умолчанию).

### 5. Временное окно событий GC (`GcMonitor`) & `AdaptiveTaskScheduler`
- События JMX `GarbageCollectionNotificationInfo` хранятся в скользящем 60-секундном окне на монотонном времени `System.nanoTime()` (`lastDurationMs`, `maxDuration60s`, `totalDuration60s`, `gcCount60s`).
- `AdaptiveTaskScheduler` реально распределяет фоновые задачи по приоритетам (`HIGH`, `NORMAL`, `LOW`), откладывая низкоприоритетное обслуживание при статусе `CRITICAL`.
- Детектор `CLIENT_RENDER_BOUND` заменил фиктивное упоминание аппаратного измерения загрузки GPU.

---

## 📊 Автоматическое тестирование (JUnit 5)

Весь движок APE и планировщик задач покрыты 6 автоматическими тестами (`./gradlew test`), включая исходники тестов в `src/test/java/` и `-sources.jar`:
1. `testTrueMeanOfWorst200AndWorst20` — проверка среднего арифметического по худшим 200 и 20 кадрам в буфере 20,000.
2. `testWarmupStateThreshold` — проверка порога `WARMING UP` (<500 кадров).
3. `testDedicatedServerHeadlessMsptStress` — проверка работы APE без клиентских кадров.
4. `testRespectsIntentional30FpsCap` — проверка отсутствия ложной паники при ручном ограничении 30 FPS.
5. `testBottleneckAttributionClientRenderVsCpuTick` — разделение `CLIENT_RENDER_BOUND` и `CPU_TICK_BOUND`.
6. `testAdaptiveTaskSchedulerDefersLowPriorityUnderCriticalStress` — приоритезация очередей под нагрузкой.

---

## 📦 Сборка и загрузка

- **Требования:** Minecraft 1.21.11, Fabric Loader >=0.19.0, Java 21+.
- **Зеркало на Google Drive (режим читателя):** [Открыть папку сборок](https://drive.google.com/open?id=1trBMYrs9pOBxJxg9dhWSj49c0WJUK8Sr)

```bash
git clone https://github.com/s1ind/s1ind-opt.git
cd s1ind-opt
./gradlew test build
```
