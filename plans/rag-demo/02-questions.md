# 10 Тестовых вопросов

Вопросы сформулированы как естественные запросы разработчика.
Для каждого указан ожидаемый ответ и источник.

---

## Q1

**Вопрос:** Какие технологии on-device AI доступны для Android?

**Ожидаемый ответ с RAG:** Gemini Nano (AICore) для текста, MediaPipe для распознавания, TFLite/LiteRT для ML — с обязательной fallback стратегией: on-device → cloud → cached response через SmartAIRouter

**Ожидаемый ответ без RAG:** общее описание offline AI, возможно упомянет TFLite или Core ML, без конкретной стратегии и без SmartAIRouter

**Источник:** android-advanced.md

---

## Q2

**Вопрос:** Как реализовать fallback стратегию для on-device AI?

**Ожидаемый ответ с RAG:** применить fallback стратегию: on-device → lightweight cloud → cached response/graceful degradation

**Ожидаемый ответ без RAG:** покажет интернет, попросить обновить ОС — без конкретного паттерна

**Источник:** android-advanced.md

---

## Q3

**Вопрос:** Какова стратегия миграции Android проекта на KMP пошагово?

**Ожидаемый ответ с RAG:** начать с Domain Layer в shared модуле (чистый Kotlin), затем Data Layer (Retrofit → Ktor, Room → SQLDelight), затем DI (Hilt → Koin), UI опционально через Compose Multiplatform

**Ожидаемый ответ без RAG:** общие слова про Flutter или React Native, или KMP без конкретного порядка миграции

**Источник:** android-advanced.md

---

## Q4

**Вопрос:** UseCase — что должен возвращать, сколько функций иметь и от чего зависеть?

**Ожидаемый ответ с RAG:** вся обработка ошибок происходит в UseCase, UseCase возвращает Result<T>, Repository возвращает чистые данные без Result

**Ожидаемый ответ без RAG:** try-catch, sealed class, общие советы — без разделения ответственности UseCase/Repository

**Источник:** android-code-doctor.md

---

## Q5

**Вопрос:** Как правильно разделить Screen и View в Compose Multiplatform?

**Ожидаемый ответ с RAG:** паттерн Screen/View — Screen тонкий адаптер (DI, навигация), View чистый UI без логики и remember, вся логика в Decompose Component, Unidirectional Data Flow

**Ожидаемый ответ без RAG:** ViewModel + StateFlow, общие советы — без Screen/View паттерна и Decompose

**Источник:** android-code-doctor.md, android-ui-builder.md

---

## Q6

**Вопрос:** Какие spacing токены используются в Design System на Material 3?

**Ожидаемый ответ с RAG:** использовать Spacing токены (xs, sm, md, lg, xl, xxl...) из Design System, никаких хардкоженных dp значений

**Ожидаемый ответ без RAG:** использовать константы или dimen ресурсы — без конкретной системы токенов

**Источник:** android-ui-builder.md

---

## Q7

**Вопрос:** Какие требования к accessibility в Jetpack Compose?

**Ожидаемый ответ с RAG:** touch target минимум 48dp, content descriptions для иконок обязательны, semantics Role.Button для кастомных элементов, semantics(mergeDescendants=true) для составных

**Ожидаемый ответ без RAG:** общие советы про контраст и размер шрифта — без конкретного Compose API

**Источник:** android-ui-builder.md

---

## Q8

**Вопрос:** Из каких этапов состоит процесс генерации AI агентной системы?

**Ожидаемый ответ с RAG:** 8 этапов — анализ задачи, архитектурное решение, tool schemas, backend, Android интеграция, промпты, RAG/память, evaluation план

**Ожидаемый ответ без RAG:** общие шаги типа "выбрать модель, написать промпт" без структурированного процесса

**Источник:** android-ai-agent-generator.md

---

## Q9

**Вопрос:** Какую LLM модель выбрать для Android если нужен бесплатный on-device inference?

**Ожидаемый ответ с RAG:** Gemini Nano через AICore — бесплатно, работает on-device, требует Android 14+ и Pixel 8+

**Ожидаемый ответ без RAG:** общие слова про open source модели или self-hosted — без конкретики Android

**Источник:** android-ai-agent-generator.md

---

## Q10

**Вопрос:** Какие метрики и golden dataset нужны для evaluation plan AI агента?

**Ожидаемый ответ с RAG:** golden dataset минимум 20 кейсов, метрики answer_relevancy, tool_call_accuracy, p95_latency, cost_per_query, judge модель gpt-4.1-mini, CI trigger при изменении промптов

**Ожидаемый ответ без RAG:** "написать тесты", общие слова про качество — без конкретных метрик и пороговых значений

**Источник:** android-ai-agent-generator.md

---

## Распределение по файлам

| Файл | Вопросы |
|------|---------|
| android-advanced.md | Q1, Q2, Q3 |
| android-code-doctor.md | Q4, Q5 |
| android-ui-builder.md | Q5, Q6, Q7 |
| android-ai-agent-generator.md | Q8, Q9, Q10 |
