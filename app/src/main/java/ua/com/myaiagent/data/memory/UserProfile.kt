package ua.com.myaiagent.data.memory

/**
 * Профиль пользователя — определяет стиль, формат и ограничения ответов ассистента.
 * Встраивается в системный промпт ПЕРВЫМ (наивысший приоритет), перед всеми слоями памяти.
 */

enum class ResponseStyle(val label: String, val instruction: String) {
    CONCISE("Кратко", "Give short, direct answers. No fluff. One paragraph max unless code is needed."),
    DETAILED("Подробно", "Give thorough explanations with examples, context, and step-by-step reasoning."),
    BULLET("Списком", "Structure all responses as bullet points or numbered lists. Use headers for sections."),
    CONVERSATIONAL("Диалог", "Use a friendly, informal conversational tone. Keep it light and engaging."),
}

enum class ExpertiseLevel(val label: String, val instruction: String) {
    BEGINNER("Новичок", "Use simple language. Avoid jargon. Always explain technical terms. Use real-life analogies."),
    INTERMEDIATE("Средний", "Use professional terminology. Provide balanced detail — not too basic, not too deep."),
    EXPERT("Эксперт", "Use precise technical language. Skip basics. Focus on depth, edge cases, and best practices."),
}

/**
 * Полный профиль пользователя.
 *
 * @param id                 Уникальный идентификатор (preset name или "custom")
 * @param name               Отображаемое имя профиля
 * @param role               Роль/профессия — задаёт контекст для ответов
 * @param language           Язык ответов ассистента
 * @param responseStyle      Стиль ответов
 * @param expertiseLevel     Уровень экспертизы пользователя
 * @param useMarkdown        Использовать Markdown в ответах
 * @param useEmoji           Использовать эмодзи
 * @param restrictions       Явные ограничения (чего НЕ делать)
 * @param customInstructions Дополнительные инструкции в свободной форме
 */
data class UserProfile(
    val id: String,
    val name: String,
    val role: String = "",
    val language: String = "Russian",
    val responseStyle: ResponseStyle = ResponseStyle.CONCISE,
    val expertiseLevel: ExpertiseLevel = ExpertiseLevel.INTERMEDIATE,
    val useMarkdown: Boolean = true,
    val useEmoji: Boolean = false,
    val restrictions: List<String> = emptyList(),
    val customInstructions: String = "",
) {
    /**
     * Строит секцию системного промпта для этого профиля.
     * Вставляется ПЕРВОЙ — задаёт базовый стиль всего диалога.
     */
    fun toSystemPromptSection(): String = buildString {
        append("## USER PROFILE\n")
        if (name.isNotBlank()) append("Name: $name\n")
        if (role.isNotBlank()) append("Role: $role\n")
        append("Expertise: ${expertiseLevel.label} — ${expertiseLevel.instruction}\n")
        append("\n## COMMUNICATION STYLE\n")
        append("Language: Always respond in $language.\n")
        append("Style: ${responseStyle.label} — ${responseStyle.instruction}\n")
        if (useMarkdown) {
            append("Formatting: Use Markdown (headers, bold, code blocks, lists).\n")
        } else {
            append("Formatting: Plain text only. Do NOT use Markdown.\n")
        }
        if (useEmoji) {
            append("Emoji: You may use emoji to enhance readability.\n")
        } else {
            append("Emoji: Do NOT use emoji.\n")
        }
        if (restrictions.isNotEmpty()) {
            append("\n## RESTRICTIONS (strictly follow all of these)\n")
            restrictions.forEach { append("- $it\n") }
        }
        if (customInstructions.isNotBlank()) {
            append("\n## ADDITIONAL INSTRUCTIONS\n")
            append(customInstructions)
            append("\n")
        }
    }
}

/** Предустановленные профили — демонстрируют разные режимы персонализации. */
object ProfilePresets {

    val Developer = UserProfile(
        id = "developer",
        name = "Developer",
        role = "Senior Android / Kotlin Engineer",
        language = "Russian",
        responseStyle = ResponseStyle.CONCISE,
        expertiseLevel = ExpertiseLevel.EXPERT,
        useMarkdown = true,
        useEmoji = false,
        restrictions = listOf(
            "Use Kotlin only (no Java)",
            "Prefer Jetpack Compose over XML layouts",
            "Follow MVVM + Clean Architecture",
        ),
        customInstructions = "Always include practical Kotlin code examples. Skip theory. Highlight gotchas and edge cases.",
    )

    val Student = UserProfile(
        id = "student",
        name = "Student",
        role = "Beginner programmer learning Android",
        language = "Russian",
        responseStyle = ResponseStyle.DETAILED,
        expertiseLevel = ExpertiseLevel.BEGINNER,
        useMarkdown = false,
        useEmoji = true,
        restrictions = emptyList(),
        customInstructions = "Explain everything step-by-step. Use analogies from everyday life. Encourage the learner.",
    )

    val Manager = UserProfile(
        id = "manager",
        name = "Manager",
        role = "Product / Project Manager",
        language = "Russian",
        responseStyle = ResponseStyle.BULLET,
        expertiseLevel = ExpertiseLevel.INTERMEDIATE,
        useMarkdown = true,
        useEmoji = false,
        restrictions = listOf(
            "No code snippets",
            "Business outcomes and impact only",
            "Maximum 5 bullet points per section",
        ),
        customInstructions = "Start every answer with a 1-sentence Executive Summary. End with clear Next Steps.",
    )

    val all = listOf(Developer, Student, Manager)
}
