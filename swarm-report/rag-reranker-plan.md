# RAG Reranker — Общий план реализации

## Постановка задачи

Добавить в RAG-экран поддержку cross-encoder реранкера с настройкой:
- включение/выключение реранкера (toggle)
- выбор модели из фиксированного списка
- порог отсечения нерелевантных результатов (threshold 0.0–1.0)
- top-K до реранкинга (сколько извлечь из FAISS)
- top-K после фильтрации (сколько передать в LLM)

## Пайплайн поиска (после реализации)

```
FAISS search → top_k_initial результатов
      ↓
Cross-encoder (если включён) → score 0..1 для каждого чанка
      ↓
Threshold filter → удалить чанки со score < threshold
      ↓
top_k_final → передать в LLM как контекст
```

Если реранкер выключен — работает как раньше: FAISS → top_k_final напрямую.

## Новые сущности

### Сервер (rag_server.py)

**RerankerService**
- Поля: `_models: Dict[str, CrossEncoder]` (кэш загруженных моделей)
- Методы:
  - `get_model(model_name: str) -> CrossEncoder` — lazy loading с кэшем
  - `rerank(query: str, chunks: List[dict], model_name: str) -> List[dict]` — возвращает чанки, отсортированные по убыванию reranker_score

**Обновление инструмента `search_documents`**
- Новые параметры: `reranker_enabled: bool`, `reranker_model: str`, `rerank_threshold: float`, `top_k_initial: int`, `top_k_final: int`
- `top_k` (старый) → алиас для `top_k_final` (обратная совместимость)
- Поля ответа: добавить `reranker_score: float | None`, `retrieved_count: int`, `filtered_count: int`

### ViewModel (AgentViewModel.kt)

**RagSearchResult** (расширение)
- Добавить поле: `rerankerScore: Float?`

**Новые StateFlows**
- `ragRerankerEnabled: StateFlow<Boolean>` (default: false)
- `ragRerankerModel: StateFlow<String>` (default: "cross-encoder/ms-marco-MiniLM-L-6-v2")
- `ragRerankerThreshold: StateFlow<Float>` (default: 0.3f)
- `ragTopKInitial: StateFlow<Int>` (default: 10)
- Существующий `ragTopK` переименовать в `ragTopKFinal`

**Новые методы**
- `setRerankerEnabled(enabled: Boolean)`
- `setRerankerModel(model: String)`
- `setRerankerThreshold(threshold: Float)`
- `setTopKInitial(k: Int)`
- `setTopKFinal(k: Int)` — переименование `setRagTopK`

**Константы**
```
RERANKER_MODELS: List<String> — список поддерживаемых моделей
DEFAULT_RERANKER_THRESHOLD: Float = 0.3f
DEFAULT_TOP_K_INITIAL: Int = 10
```

### UI (ChatScreen.kt — RagIndexPanel)

**RerankerSection** — новая секция внутри RagIndexPanel
- `RerankerToggle` — Switch с подписью "Реранкер"
- `RerankerModelDropdown` — ExposedDropdownMenuBox (виден только когда toggle включён)
- `ThresholdSlider` — Slider 0.0–1.0, шаг 0.05 (виден только когда toggle включён)
- Обновление top-K: разбить на два ряда — "Извлечь" (top_k_initial) и "Вернуть" (top_k_final)

**RagResultsPanel** — расширение отображения
- Заголовок: "N из M чанков" (финальных из извлечённых)
- Чип "отфильтровано X" (если filtered_count > 0)
- В карточке чанка: строка "Reranker: 0.72" (когда реранкер включён)

## Этапы реализации

| № | Название | Файлы | Результат |
|---|----------|-------|-----------|
| 1 | Server: RerankerService | `rag_server.py`, `requirements-rag.txt` | Сервер принимает reranker-параметры и возвращает обогащённые результаты |
| 2 | ViewModel: новые стейты и методы | `AgentViewModel.kt` | ViewModel управляет всеми reranker-параметрами и передаёт их в запрос |
| 3 | UI: RagIndexPanel — настройки реранкера | `ChatScreen.kt` | Пользователь может настроить реранкер через UI |
| 4 | UI: RagResultsPanel — расширенное отображение | `ChatScreen.kt` | Результаты показывают reranker-score и статистику фильтрации |
