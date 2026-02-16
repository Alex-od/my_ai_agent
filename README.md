# Мой ИИ Агент

Android-приложение для общения с OpenAI API (GPT-4o-mini). Архитектура готова к миграции на Kotlin Multiplatform (KMP).

## Стек технологий

- **Kotlin** + **Jetpack Compose** — UI
- **Ktor** — HTTP-клиент (мультиплатформенный)
- **Koin** — Dependency Injection
- **kotlinx.serialization** — JSON-парсинг
- **Coroutines + StateFlow** — асинхронность и управление состоянием

## Настройка

1. Клонируйте репозиторий:
   ```bash
   git clone https://github.com/Alex-od/my_ai_agent.git
   ```

2. Добавьте API ключ OpenAI в `local.properties`:
   ```properties
   OPENAI_API_KEY=sk-proj-ваш_ключ
   ```

3. Откройте проект в Android Studio, синхронизируйте Gradle и запустите на устройстве/эмуляторе.

## Структура проекта

```
app/src/main/java/ua/com/myapplication/
├── App.kt                  # Application — инициализация Koin
├── MainActivity.kt         # Точка входа, Scaffold + тема
├── AgentScreen.kt          # UI: поле ввода, кнопка, вывод ответа
├── AgentViewModel.kt       # ViewModel: состояние (idle/loading/success/error)
├── data/
│   └── OpenAiApi.kt        # Ktor-клиент к OpenAI Chat Completions API
└── di/
    └── AppModule.kt        # Koin-модуль: HttpClient, OpenAiApi, ViewModel
```

## Безопасность

API ключ хранится в `local.properties` (входит в `.gitignore`, не попадает в репозиторий). Для продакшена рекомендуется проксировать запросы через собственный backend-сервер.
