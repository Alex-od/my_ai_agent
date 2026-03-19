# Этап 1: Server — RerankerService (rag_server.py)

## Контекст
Сервер на FastAPI (порт 8083) использует FAISS IndexFlatL2 для поиска и Ollama для эмбеддингов.
Текущий `search_documents` принимает `query`, `strategy`, `top_k` и возвращает список чанков без реранкинга.

## Задача
Добавить cross-encoder реранкер на сервер. Реранкер — это отдельная нейросеть, которая принимает пару (запрос, чанк) и возвращает score релевантности от 0 до 1 (в отличие от bi-encoder/FAISS, который считает только расстояние между отдельными векторами).

## Зависимости

**`requirements-rag.txt`** — добавить:
```
sentence-transformers>=2.7.0
```
sentence-transformers включает класс `CrossEncoder` который берёт модели с HuggingFace.

## Новые сущности в rag_server.py

### Класс RerankerService

**Назначение:** Управляет загрузкой и кэшем cross-encoder моделей.

**Поля:**
- `_models: Dict[str, CrossEncoder]` — словарь "имя модели → объект модели"

**Методы:**
- `get_model(model_name: str) -> CrossEncoder`
  - Если модель уже в `_models` — вернуть из кэша
  - Иначе — загрузить `CrossEncoder(model_name)`, сохранить в кэш, вернуть
  - При ошибке загрузки — пробросить исключение с понятным сообщением

- `rerank(query: str, chunks: List[dict], model_name: str) -> List[dict]`
  - Взять модель через `get_model(model_name)`
  - Сформировать список пар: `[(query, chunk["text"]) for chunk in chunks]`
  - Вызвать `model.predict(pairs)` → numpy array of float scores (логиты)
  - Применить sigmoid к каждому скору: `score = 1 / (1 + exp(-logit))`
  - Добавить `reranker_score` в каждый чанк
  - Отсортировать по убыванию `reranker_score`
  - Вернуть обогащённый список

**Глобальный экземпляр:** `reranker_service = RerankerService()` (один на всё приложение)

### Обновление инструмента `search_documents`

**Новые параметры схемы (properties):**
- `reranker_enabled: bool` — включить реранкинг (default: false)
- `reranker_model: str` — имя модели (default: "cross-encoder/ms-marco-MiniLM-L-6-v2")
- `rerank_threshold: float` — порог отсечения (default: 0.3)
- `top_k_initial: int` — сколько чанков взять из FAISS (default: 10)
- `top_k_final: int` — сколько вернуть после фильтрации (default: 3)
- `top_k: int` — алиас для `top_k_final` (обратная совместимость)

**Логика выполнения:**
```
1. Прочитать top_k_initial (если не задан — использовать top_k или 10)
2. Прочитать top_k_final (если не задан — использовать top_k или 3)
3. Выполнить FAISS search с top_k_initial
4. Если reranker_enabled:
   a. Вызвать reranker_service.rerank(query, chunks, reranker_model)
   b. Отфильтровать chunk["reranker_score"] < rerank_threshold
   c. Взять первые top_k_final из отфильтрованных
5. Иначе (реранкер выключен):
   a. Взять первые top_k_final из FAISS результатов
   b. reranker_score = None для каждого чанка
6. Собрать метаданные: retrieved_count, filtered_count, final_count
7. Вернуть результат
```

**Новые поля в каждом результате:**
- `reranker_score: float | None` — score от cross-encoder (None если реранкер выключен)

**Новые поля верхнего уровня ответа:**
- `retrieved_count: int` — сколько взяли из FAISS
- `filtered_count: int` — сколько отфильтровал порог
- `final_count: int` — сколько вернули

**Обработка ошибок:**
- Если модель не загрузилась (нет интернета, опечатка в имени) — вернуть результаты без реранкинга + поле `reranker_error: str` в ответе

## Поддерживаемые модели (для документации)

| Модель | Размер | Язык | Скорость |
|--------|--------|------|----------|
| cross-encoder/ms-marco-MiniLM-L-6-v2 | ~66MB | EN | Быстрая |
| cross-encoder/ms-marco-MiniLM-L-12-v2 | ~130MB | EN | Средняя |
| BAAI/bge-reranker-base | ~278MB | Multilingual | Медленная |

Модели загружаются при первом вызове и кэшируются в памяти сервера на время сессии.

## Резюме

**Что получим:** Сервер умеет реранковать результаты через cross-encoder, фильтровать по порогу и возвращать обогащённые метаданные.

**Критерии успеха:**
- `curl`-запрос к `search_documents` с `reranker_enabled=true` возвращает `reranker_score` для каждого чанка
- При `reranker_enabled=false` поведение идентично текущему
- При недоступной модели — graceful fallback, не падает сервер
