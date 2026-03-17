# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Role & Communication Style

- Act as an **AI Agent expert**: apply best practices from OpenAI and Google when designing agents, tools, memory, and pipelines
- Always reference official docs when relevant:
  - OpenAI: https://platform.openai.com/docs
  - Google Gemini: https://ai.google.dev/gemini-api/docs
- Explain concepts **like to a student**: simple words, analogies, no jargon without explanation
- Plans must be written in **Russian**

## Project Overview

Android app for interacting with OpenAI's Chat Completions API, built with Kotlin + Jetpack Compose. The architecture is intentionally KMP-ready (Ktor for HTTP, kotlinx.serialization for JSON, Koin for DI — all multiplatform-compatible).

## Setup

Add your OpenAI API key to `local.properties` (not committed to git):
```properties
OPENAI_API_KEY=sk-proj-your_key
```

## Build & Run

Open in Android Studio, sync Gradle, then run on a device/emulator.

Common Gradle tasks via terminal:
```bash
./gradlew assembleDebug          # Build debug APK
./gradlew assembleRelease        # Build release APK
./gradlew test                   # Run unit tests
./gradlew connectedAndroidTest   # Run instrumented tests (requires device/emulator)
./gradlew lint                   # Run lint checks
```

## Architecture

**Stack:** MVVM · Koin DI · Ktor HTTP · Coroutines + StateFlow · Compose UI · kotlinx.serialization

**Data flow:**
```
Day1Screen / Day2Screen (Compose UI)
    ↓  user input
AgentViewModel.send(...)         ← sealed UiState: Idle | Loading | Success | Error
    ↓  suspend call
OpenAiApi.ask(...)               ← Ktor POST to OpenAI Chat Completions API
    ↓  response
StateFlow emission → recomposition
```

**Key files:**
- `AgentViewModel.kt` — all business logic; owns `UiState` and `selectedModel` StateFlows
- `OpenAiApi.kt` — sole HTTP layer; wraps Ktor client, handles serialization, checks HTTP status
- `AppModule.kt` — Koin wiring: `HttpClient` (single) → `OpenAiApi` (single) → `AgentViewModel` (viewModel)
- `AgentScreen.kt` — root composable; modal navigation drawer for screen selection + model selection; routes between screens
- `ChatScreen.kt` — main chat UI: system_prompt, user_prompt, temperature, top_p, MCP, scheduler

**API key injection:** `local.properties` → `app/build.gradle.kts` `buildConfigField` → `BuildConfig.OPENAI_API_KEY` (used in `AppModule.kt`)

**Supported models** (defined in `AgentViewModel.kt`): gpt-4o-mini (default), gpt-4o, gpt-4.1, gpt-4.1-mini, gpt-4.1-nano, o3-mini

## Architecture Rules

### Clean Architecture & Layer Separation
Строго соблюдать разделение кода по слоям (presentation → domain → data). Соблюдать Clean Architecture и текущие архитектурные решения.

### Planning Workflow (обязателен перед любой новой задачей)

1. Прочитать задачу и предполагаемое решение.
2. Проанализировать существующую кодовую базу.
3. Выяви:
Неясные места — где есть двусмысленность или недостаточно контекста
Непроработанные участки — где логика описана поверхностно или пропущены шаги
Узкие места — потенциальные проблемы в масштабировании, производительности или надежности

Для каждого пункта:
- укажи проблему
- объясни, почему это проблема
- предложи конкретное улучшение
4. Добиться в процессе диалога устранения этих мест.
5. Итоговый уточнённый план сохранить в файл. Общий план **не должен содержать кусков кода** — только описание новых сущностей (имя, методы, параметры, интерфейсы, схемы взаимодействия).
6. Разбить уточнённый план на отдельные изолированные этапы (4–6).
7. Для каждого этапа создать отдельный файл с общим описанием и описанием задачи конкретного шага (+ резюме: что получим и критерии успеха).
8. Для каждого файла этапа в процессе диалога, вопросов и уточнений составить подробный план реализации этапа.
9. Сравнить подробное описание каждого этапа с его описанием в общем файле. Выявить несостыковки и неучтённые моменты. Скорректировать в процессе диалога с пользователем.

**Ожидаемый результат:**
1. Общее описание постановки задачи с поэтапным планом реализации.
2. Файлы с подробным описанием и планом реализации для каждого отдельного этапа.

**Не приступать к написанию кода без явного указания пользователя.**

## Dependency Versions

Managed via `gradle/libs.versions.toml`. Key versions:
- Compose BOM: 2024.09.00 (Material 3)
- Ktor: 2.3.12
- Koin: 3.5.6
- kotlinx.serialization: 1.6.3
