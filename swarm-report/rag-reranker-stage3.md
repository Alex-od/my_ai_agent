# Этап 3: UI — RagIndexPanel — настройки реранкера (ChatScreen.kt)

## Контекст
`RagIndexPanel` — это collapsible-панель внутри RagScreen. Сейчас содержит:
- Выбор стратегии (segmented buttons: Fixed / Structural)
- Top-K контрол (− / число / +)
- Поле ввода server path
- Кнопки "Старт" и "Сравнить"

Все параметры передаются через callbacks: `onStrategyChange`, `onTopKChange`, и т.д.

## Задача
Добавить секцию настройки реранкера в панель. Разбить один top-K на два. Обновить сигнатуру composable и точку вызова.

## Изменения в RagIndexPanel

### Обновление сигнатуры

**Добавить параметры:**
- `rerankerEnabled: Boolean`
- `rerankerModel: String`
- `rerankerThreshold: Float`
- `topKInitial: Int`
- `onRerankerEnabledChange: (Boolean) -> Unit`
- `onRerankerModelChange: (String) -> Unit`
- `onRerankerThresholdChange: (Float) -> Unit`
- `onTopKInitialChange: (Int) -> Unit`

**Переименовать:**
- `topK: Int` → `topKFinal: Int`
- `onTopKChange: (Int) -> Unit` → `onTopKFinalChange: (Int) -> Unit`

### Обновление top-K блока

Вместо одного ряда "Top-K: − N +" добавить два ряда:
- "Извлечь" — контрол для `topKInitial` (диапазон 1..20)
- "Вернуть" — контрол для `topKFinal` (диапазон 1..10)

Оба контрола используют тот же паттерн кнопок − / число / +.

### Новый блок RerankerSection

Располагается между блоком стратегии и блоком top-K. Содержит:

**1. RerankerToggleRow**
- `Row` с `Text("Реранкер")` + `Switch(checked = rerankerEnabled, ...)`
- При включении — показывает ниже настройки модели и порога

**2. RerankerModelDropdown** (виден только если `rerankerEnabled`)
- `ExposedDropdownMenuBox` с тремя пунктами:
  - `cross-encoder/ms-marco-MiniLM-L-6-v2` — отображается как "MiniLM-L6 (быстрая)"
  - `cross-encoder/ms-marco-MiniLM-L-12-v2` — отображается как "MiniLM-L12 (точнее)"
  - `BAAI/bge-reranker-base` — отображается как "BGE Base (мультиязычная)"
- При смене — вызывает `onRerankerModelChange`

**3. ThresholdSliderRow** (виден только если `rerankerEnabled`)
- `Text("Порог: ${threshold}")` — показывает значение с 2 знаками
- `Slider(value = rerankerThreshold, onValueChange = onRerankerThresholdChange, valueRange = 0f..1f, steps = 19)`
- Подпись под слайдером: "0.0 — всё пройдёт · 1.0 — только точные совпадения"
- steps = 19 даёт шаг 0.05 (20 позиций от 0.0 до 1.0)

### Обновление точки вызова RagIndexPanel

В `RagScreen.kt` (или в `ChatScreen.kt` где вызывается `RagIndexPanel`) добавить новые параметры, подключив соответствующие StateFlow через `collectAsState()`:
- `rerankerEnabled = rerankerEnabled`
- `rerankerModel = rerankerModel`
- `rerankerThreshold = rerankerThreshold`
- `topKInitial = topKInitial`
- `onRerankerEnabledChange = { viewModel.setRerankerEnabled(it) }`
- `onRerankerModelChange = { viewModel.setRerankerModel(it) }`
- `onRerankerThresholdChange = { viewModel.setRerankerThreshold(it) }`
- `onTopKInitialChange = { viewModel.setTopKInitial(it) }`
- переименовать `onTopKChange` → `onTopKFinalChange = { viewModel.setTopKFinal(it) }`

## Визуальная схема панели после изменений

```
┌─ RAG Настройки ────────────────────────────┐
│ Стратегия:  [ Fixed ] [ Structural ]        │
│                                             │
│ Реранкер:                    [ Toggle ]     │
│   Модель:  ▼ MiniLM-L6 (быстрая)           │  ← виден если toggle ON
│   Порог:   ━━━━●━━━━━━━━  0.30              │  ← виден если toggle ON
│                                             │
│ Извлечь:    [ − ] 10 [ + ]                  │
│ Вернуть:    [ − ]  3 [ + ]                  │
│                                             │
│ Путь: /path/to/docs                         │
│ [ Старт ]              [ Сравнить ]         │
└─────────────────────────────────────────────┘
```

## Резюме

**Что получим:** Пользователь может включить реранкер, выбрать модель, настроить порог и оба top-K прямо из панели.

**Критерии успеха:**
- RerankerSection появляется/скрывается по toggle
- Dropdown показывает три модели с понятными названиями
- Слайдер меняет threshold с шагом 0.05 и показывает значение
- Оба top-K контрола работают независимо
- Приложение компилируется без ошибок
