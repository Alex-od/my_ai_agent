# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

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

```

**Key files:**
- `AgentViewModel.kt` — all business logic; owns `UiState` and `selectedModel` StateFlows
- `OpenAiApi.kt` — sole HTTP layer; wraps Ktor client, handles serialization, checks HTTP status
- `AppModule.kt` — Koin wiring: `HttpClient` (single) → `OpenAiApi` (single) → `AgentViewModel` (viewModel)
- `AgentScreen.kt` — root composable; modal navigation drawer for screen selection + model selection; routes between Day1Screen and Day2Screen
- `Day1Screen.kt` — full parameter UI: system_prompt, user_prompt, temperature, top_p, top_k
- `Day2Screen.kt` — simplified UI: user_prompt, response format, max_tokens, stop sequences

**API key injection:** `local.properties` → `app/build.gradle.kts` `buildConfigField` → `BuildConfig.OPENAI_API_KEY` (used in `AppModule.kt`)

**Supported models** (defined in `AgentViewModel.kt`): gpt-4o-mini (default), gpt-4o, gpt-4.1, gpt-4.1-mini, gpt-4.1-nano, o3-mini

## Dependency Versions

Managed via `gradle/libs.versions.toml`. Key versions:
- Compose BOM: 2024.09.00 (Material 3)
- Ktor: 2.3.12
- Koin: 3.5.6
- kotlinx.serialization: 1.6.3
