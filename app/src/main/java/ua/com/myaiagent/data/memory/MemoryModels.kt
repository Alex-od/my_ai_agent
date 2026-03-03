package ua.com.myaiagent.data.memory

/**
 * 🧠 Три слоя памяти агента (День 11)
 *
 * 🟢 ShortTermMemory  — текущий диалог (живёт до конца сессии, в RAM)
 * 🟡 WorkingMemory    — данные текущей задачи (пока задача активна, в RAM)
 * 🔵 LongTermMemory   — профиль, предпочтения, знания (между сессиями, в SharedPreferences)
 */

// ── Базовые структуры ─────────────────────────────────────────────────────────

data class MemoryMessage(
    val role: String,      // "user" или "assistant"
    val content: String,
)

data class WorkingDecision(
    val topic: String,
    val choice: String,
    val reasoning: String = "",
)

// ── 🟢 Краткосрочная память ───────────────────────────────────────────────────

/**
 * Хранит текущий диалог — последние N сообщений + summary старых.
 *
 * Политика вытеснения: при превышении [maxMessages] старые сообщения
 * уходят в summary (пачками по 2), чтобы не терять контекст.
 */
class ShortTermMemory(val maxMessages: Int = 10) {
    val messages: MutableList<MemoryMessage> = mutableListOf()
    var summary: String = ""
    var evictedCount: Int = 0

    /**
     * Добавляет сообщение.
     * @return вытесненные сообщения (если произошло вытеснение) или null
     */
    fun addMessage(role: String, content: String): List<MemoryMessage>? {
        messages.add(MemoryMessage(role, content))
        return if (messages.size > maxMessages) {
            val evicted = messages.take(2).toList()
            repeat(2) { messages.removeAt(0) }
            evictedCount += 2
            evicted
        } else null
    }

    fun updateSummary(newSummary: String) {
        summary = newSummary
    }

    fun clear() {
        messages.clear()
        summary = ""
        evictedCount = 0
    }
}

// ── 🟡 Рабочая память ─────────────────────────────────────────────────────────

/**
 * Хранит структурированное состояние текущей задачи:
 * - спецификацию проекта
 * - принятые решения
 * - открытые вопросы
 *
 * При смене задачи данные сбрасываются.
 */
class WorkingMemory {
    var currentTask: String? = null
    val taskData: MutableMap<String, String> = mutableMapOf()
    val decisions: MutableList<WorkingDecision> = mutableListOf()
    val openQuestions: MutableList<String> = mutableListOf()

    fun setTask(name: String) {
        currentTask = name
        taskData.clear()
        decisions.clear()
        openQuestions.clear()
    }

    fun save(key: String, value: String) {
        taskData[key] = value
    }

    fun addDecision(topic: String, choice: String, reasoning: String = "") {
        decisions.add(WorkingDecision(topic, choice, reasoning))
    }

    fun addQuestion(question: String) {
        openQuestions.add(question)
    }

    fun resolveQuestion(question: String) {
        openQuestions.remove(question)
    }

    fun clear() {
        currentTask = null
        taskData.clear()
        decisions.clear()
        openQuestions.clear()
    }
}

// ── 🔵 Долговременная память ──────────────────────────────────────────────────

/**
 * Хранит устойчивые знания о пользователе.
 * Персистентна между сессиями (сохраняется в SharedPreferences через MemoryStore).
 *
 * - [userProfile]  — факты о пользователе (профессия, имя, локация)
 * - [preferences]  — предпочтения (язык, архитектура, инструменты)
 * - [knowledge]    — усвоенные знания из прошлых задач
 */
class LongTermMemory {
    val userProfile: MutableMap<String, String> = mutableMapOf()
    val preferences: MutableMap<String, String> = mutableMapOf()
    val knowledge: MutableMap<String, String> = mutableMapOf()

    fun saveProfile(key: String, value: String) {
        userProfile[key] = value
    }

    /**
     * @return сообщение об обновлении если значение изменилось, иначе null
     */
    fun savePreference(key: String, value: String): String? {
        val old = preferences[key]
        preferences[key] = value
        return if (old != null && old != value) "Обновлено '$key': '$old' → '$value'" else null
    }

    fun saveKnowledge(key: String, value: String) {
        knowledge[key] = value
    }

    fun clear() {
        userProfile.clear()
        preferences.clear()
        knowledge.clear()
    }
}
