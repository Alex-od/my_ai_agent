# Этап 2: ViewModel — Новые стейты и методы (AgentViewModel.kt)

## Контекст
AgentViewModel управляет всем RAG-состоянием через StateFlow. Текущие RAG-стейты:
- `ragEnabled: StateFlow<Boolean>`
- `ragTopK: StateFlow<Int>` (default: 3)
- `selectedRagStrategy: StateFlow<String>`
- `lastRagResults: StateFlow<List<RagSearchResult>>`

Метод `send()` использует `ragTopK` при вызове `search_documents` через MCP.

## Задача
Добавить стейты и методы для управления реранкером. Обновить `RagSearchResult` и вызов `search_documents`.

## Изменения

### Константы (companion object или top-level)

**Добавить:**
- `RERANKER_MODELS: List<String>` — список из трёх моделей:
  1. `"cross-encoder/ms-marco-MiniLM-L-6-v2"` (default)
  2. `"cross-encoder/ms-marco-MiniLM-L-12-v2"`
  3. `"BAAI/bge-reranker-base"`
- `DEFAULT_RERANKER_THRESHOLD: Float = 0.3f`
- `DEFAULT_TOP_K_INITIAL: Int = 10`

### Data class RagSearchResult — расширение

**Добавить поле:**
- `rerankerScore: Float? = null` — score от cross-encoder (null если реранкер выключен)

Поле опциональное с дефолтом — не ломает существующий код.

### Новые StateFlows

```
ragRerankerEnabled: MutableStateFlow<Boolean> = MutableStateFlow(false)
ragRerankerModel: MutableStateFlow<String> = MutableStateFlow(RERANKER_MODELS[0])
ragRerankerThreshold: MutableStateFlow<Float> = MutableStateFlow(DEFAULT_RERANKER_THRESHOLD)
ragTopKInitial: MutableStateFlow<Int> = MutableStateFlow(DEFAULT_TOP_K_INITIAL)
```

**Переименование существующего:**
- `ragTopK` → `ragTopKFinal` (+ обновить все ссылки внутри файла)

### Новые методы

- `setRerankerEnabled(enabled: Boolean)` — обновляет `ragRerankerEnabled`
- `setRerankerModel(model: String)` — обновляет `ragRerankerModel`; валидация: model должна быть из `RERANKER_MODELS`
- `setRerankerThreshold(threshold: Float)` — обновляет `ragRerankerThreshold`; clamp: 0.0f..1.0f
- `setTopKInitial(k: Int)` — обновляет `ragTopKInitial`; clamp: 1..20
- `setTopKFinal(k: Int)` — переименование `setRagTopK`; clamp: 1..10

### Обновление метода send()

В блоке инъекции RAG-контекста (вызов `search_documents`) добавить параметры:

```
args:
  query = userPrompt
  strategy = selectedRagStrategy
  top_k_initial = ragTopKInitial
  top_k_final = ragTopKFinal
  reranker_enabled = ragRerankerEnabled
  reranker_model = ragRerankerModel  (если ragRerankerEnabled)
  rerank_threshold = ragRerankerThreshold  (если ragRerankerEnabled)
```

### Обновление парсинга ответа search_documents

При разборе JSON-ответа от `search_documents`:
- Парсить `reranker_score` из каждого чанка → `RagSearchResult.rerankerScore`
- Парсить `retrieved_count`, `filtered_count` → сохранить в новый StateFlow `ragLastSearchStats: StateFlow<RagSearchStats?>`

**Новый data class RagSearchStats:**
- `retrievedCount: Int` — сколько взяли из FAISS
- `filteredCount: Int` — сколько отсеяли по порогу
- `finalCount: Int` — сколько вернули

## Резюме

**Что получим:** ViewModel полностью управляет всеми reranker-параметрами, передаёт их на сервер и парсит обогащённые результаты.

**Критерии успеха:**
- Все новые StateFlows компилируются и доступны из UI
- `send()` передаёт reranker-параметры в MCP-вызов
- `RagSearchResult` содержит `rerankerScore`
- `ragLastSearchStats` содержит статистику фильтрации после каждого поиска
