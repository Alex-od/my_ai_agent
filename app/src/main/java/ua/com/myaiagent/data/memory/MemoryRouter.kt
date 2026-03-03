package ua.com.myaiagent.data.memory

/**
 * 🔀 Memory Router — классификатор входящих сообщений.
 *
 * Определяет, что из сообщения нужно сохранить и в какой слой памяти.
 * Использует rule-based подход: regex-паттерны для типичных фраз.
 *
 * Примеры классификации:
 *   "Я Android разработчик"     → 🔵 LONG_TERM / PROFILE
 *   "Предпочитаю Kotlin"        → 🔵 LONG_TERM / PREFERENCE
 *   "Платформа: Android"        → 🟡 WORKING   / TASK_DATA
 *   "Решили взять Room"         → 🟡 WORKING   / DECISION
 *   "Привет, как дела?"         → 🟢 SHORT_TERM / TRANSIENT
 */

enum class MemoryLayer { SHORT_TERM, WORKING, LONG_TERM }

enum class FactCategory { PROFILE, PREFERENCE, TASK_DATA, DECISION, KNOWLEDGE, TRANSIENT }

data class Classification(
    val layer: MemoryLayer,
    val category: FactCategory,
    val key: String? = null,
    val value: String? = null,
    val confidence: Float = 0f,
)

class MemoryRouter {

    // ── Паттерны для PROFILE (Долговременная память) ──────────────────────────

    private val profilePatterns = listOf(
        Regex("(?:меня зовут|мое имя|my name is)\\s+(.+)", RegexOption.IGNORE_CASE) to "name",
        Regex("(?:я)\\s+([\\w-]+)\\s+(?:разработчик|developer|программист|инженер)", RegexOption.IGNORE_CASE) to "profession",
        Regex("(?:я|i am)\\s+(?:разработчик|developer|программист|инженер)\\s*(.*)", RegexOption.IGNORE_CASE) to "profession",
        Regex("(?:мой опыт|я работаю)\\s+(\\d+)\\s*(?:лет|год|года|years?)", RegexOption.IGNORE_CASE) to "experience_years",
        Regex("(?:я из|живу в|i'm from|i live in)\\s+(.+)", RegexOption.IGNORE_CASE) to "location",
        Regex("(?:я работаю в|работаю на|i work at)\\s+(.+)", RegexOption.IGNORE_CASE) to "company",
    )

    // ── Паттерны для PREFERENCE (Долговременная память) ───────────────────────

    private val preferencePatterns = listOf(
        Regex("(?:предпочитаю|люблю|нравится|выбираю|использую|prefer|love|like)\\s+(.+)", RegexOption.IGNORE_CASE),
        Regex("(?:я за|я выбираю|i choose|i prefer)\\s+(.+)", RegexOption.IGNORE_CASE),
        Regex("(?:мой любимый|мои любимые|my favorite)\\s+(.+)", RegexOption.IGNORE_CASE),
    )

    // ── Паттерны для TASK_DATA (Рабочая память) ───────────────────────────────

    private val taskPatterns = listOf(
        Regex("(?:платформа|platform)\\s*(?:[:\\-—]\\s*)?(.+)", RegexOption.IGNORE_CASE) to "platform",
        Regex("(?:язык|language)\\s*(?:[:\\-—]\\s*)?(.+)", RegexOption.IGNORE_CASE) to "language",
        Regex("(?:дедлайн|deadline|срок)\\s*(?:[:\\-—]\\s*)?(.+)", RegexOption.IGNORE_CASE) to "deadline",
        Regex("(?:стек|stack|технологии|tech)\\s*(?:[:\\-—]\\s*)?(.+)", RegexOption.IGNORE_CASE) to "tech_stack",
        Regex("(?:целевая аудитория|аудитория|target)\\s*(?:[:\\-—]\\s*)?(.+)", RegexOption.IGNORE_CASE) to "target_audience",
        Regex("(?:проект|задача|приложение|app)\\s+(?:для|про|о|—|:|-)\\s*(.+)", RegexOption.IGNORE_CASE) to "project_topic",
    )

    // ── Маркеры для DECISION (Рабочая память) ─────────────────────────────────

    private val decisionMarkers = listOf(
        "решили", "выбрали", "остановились на", "будем использовать",
        "давай возьмём", "берём", "утвердили", "согласились",
        "decided", "let's go with", "we'll use", "going with",
    )

    /**
     * Классифицирует сообщение.
     * Может вернуть несколько результатов — одно сообщение может содержать
     * и факт о профиле, и данные задачи одновременно.
     */
    fun classify(message: String, hasActiveTask: Boolean = false): List<Classification> {
        val results = mutableListOf<Classification>()

        // 1. Проверяем профильные данные → LONG_TERM
        for ((pattern, key) in profilePatterns) {
            val match = pattern.find(message) ?: continue
            val value = match.groupValues.getOrNull(1)?.trim()
            if (!value.isNullOrBlank()) {
                results.add(
                    Classification(MemoryLayer.LONG_TERM, FactCategory.PROFILE, key, value, 0.8f)
                )
            }
        }

        // 2. Проверяем предпочтения → LONG_TERM
        for (pattern in preferencePatterns) {
            val match = pattern.find(message) ?: continue
            val value = match.groupValues.getOrNull(1)?.trim()
            if (!value.isNullOrBlank()) {
                val key = inferPreferenceKey(message.lowercase(), value.lowercase())
                results.add(
                    Classification(MemoryLayer.LONG_TERM, FactCategory.PREFERENCE, key, value, 0.7f)
                )
            }
        }

        // 3. Проверяем данные задачи → WORKING
        for ((pattern, key) in taskPatterns) {
            val match = pattern.find(message) ?: continue
            val value = match.groupValues.getOrNull(1)?.trim()
            if (!value.isNullOrBlank()) {
                results.add(
                    Classification(MemoryLayer.WORKING, FactCategory.TASK_DATA, key, value, 0.8f)
                )
            }
        }

        // 4. Проверяем решения → WORKING
        val messageLower = message.lowercase()
        if (decisionMarkers.any { it in messageLower }) {
            results.add(
                Classification(MemoryLayer.WORKING, FactCategory.DECISION, "decision", message, 0.7f)
            )
        }

        // 5. Ничего не нашли → TRANSIENT (остаётся только в short-term)
        if (results.isEmpty()) {
            results.add(Classification(MemoryLayer.SHORT_TERM, FactCategory.TRANSIENT, confidence = 1.0f))
        }

        return results
    }

    /** Пытается определить категорию предпочтения по контексту сообщения. */
    private fun inferPreferenceKey(message: String, value: String): String {
        val keywords = mapOf(
            "язык" to "language", "language" to "language",
            "kotlin" to "language", "котлин" to "language",
            "swift" to "language", "свифт" to "language",
            "python" to "language", "питон" to "language",
            "java" to "language", "джава" to "language",
            "dart" to "language", "typescript" to "language",
            "архитектур" to "architecture", "mvvm" to "architecture",
            "mvi" to "architecture", "mvc" to "architecture",
            "compose" to "ui_framework", "xml" to "ui_framework",
            "flutter" to "ui_framework",
            "фреймворк" to "framework", "framework" to "framework",
            "ide" to "editor", "редактор" to "editor",
            "android studio" to "editor", "vs code" to "editor",
            "тема" to "theme", "стиль" to "style",
        )
        return keywords.entries
            .firstOrNull { (kw, _) -> kw in message || kw in value }
            ?.value ?: value.take(20)
    }

    /** Человекочитаемое объяснение классификации для отображения в UI. */
    fun explain(classifications: List<Classification>): String {
        if (classifications.isEmpty()) return "Нет классификации"
        return classifications.joinToString("\n") { c ->
            val icon = when (c.layer) {
                MemoryLayer.SHORT_TERM -> "🟢"
                MemoryLayer.WORKING -> "🟡"
                MemoryLayer.LONG_TERM -> "🔵"
            }
            buildString {
                append("$icon ${c.layer.name} → ${c.category.name}")
                c.key?.let { append(" | key='$it'") }
                c.value?.let { append(" | '${it.take(35)}'") }
                append(" (${(c.confidence * 100).toInt()}%)")
            }
        }
    }
}
