package ua.com.myaiagent.data.memory

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Снимок состояния всех слоёв памяти — для отображения в UI.
 */
data class MemorySnapshot(
    val shortTermMessages: List<MemoryMessage>,
    val shortTermSummary: String,
    val shortTermEvictedCount: Int,
    val workingTask: String?,
    val workingTaskData: Map<String, String>,
    val workingDecisions: List<WorkingDecision>,
    val workingOpenQuestions: List<String>,
    val longTermProfile: Map<String, String>,
    val longTermPreferences: Map<String, String>,
    val longTermKnowledge: Map<String, String>,
)

/**
 * 🧠 MemoryStore — координирует все три слоя памяти.
 *
 * Отвечает за:
 * - Маршрутизацию входящих сообщений через MemoryRouter
 * - Сохранение данных в нужные слои
 * - Сборку системного промпта из всех слоёв (с приоритизацией)
 * - Персистентность LongTermMemory через SharedPreferences
 *
 * Приоритет при сборке промпта:
 *   1. Working Memory  (текущая задача — критична)
 *   2. Long-Term Memory (профиль и предпочтения)
 *   3. Summary старых сообщений (история разговора)
 */
class MemoryStore(private val context: Context) {

    val shortTerm = ShortTermMemory(maxMessages = 10)
    val working = WorkingMemory()
    val longTerm = LongTermMemory()
    val router = MemoryRouter()

    private val _snapshot = MutableStateFlow(buildSnapshot())
    val snapshotFlow: StateFlow<MemorySnapshot> = _snapshot.asStateFlow()

    private val _routerLog = MutableStateFlow("")
    val routerLog: StateFlow<String> = _routerLog.asStateFlow()

    init {
        loadLongTerm()
        refreshSnapshot()
    }

    // ── Обработка входящего сообщения ─────────────────────────────────────────

    /**
     * Полный цикл обработки сообщения пользователя:
     * 1. Router классифицирует сообщение
     * 2. Данные сохраняются в нужные слои
     * 3. Сообщение добавляется в short-term (с возможным вытеснением)
     *
     * @return список классификаций (для отображения в UI)
     */
    fun processAndRoute(message: String): List<Classification> {
        val classifications = router.classify(message, working.currentTask != null)
        applyClassifications(classifications, message)
        val evicted = shortTerm.addMessage("user", message)
        if (evicted != null) summarizeEvicted(evicted)
        _routerLog.value = router.explain(classifications)
        refreshSnapshot()
        return classifications
    }

    /** Добавляет ответ ассистента в краткосрочную память. */
    fun addAssistantMessage(content: String) {
        val evicted = shortTerm.addMessage("assistant", content)
        if (evicted != null) summarizeEvicted(evicted)
        refreshSnapshot()
    }

    // ── Сборка системного промпта ─────────────────────────────────────────────

    /**
     * Собирает системный промпт из всех слоёв с приоритизацией.
     *
     * Приоритет 1 → Working Memory  (самый важный — текущая задача)
     * Приоритет 2 → Long-Term Memory (профиль, предпочтения)
     * Приоритет 3 → Summary старых сообщений
     */
    fun buildSystemPrompt(): String {
        val parts = mutableListOf<String>()

        // Приоритет 1: Working Memory
        if (working.currentTask != null) {
            val sb = StringBuilder("## ТЕКУЩАЯ ЗАДАЧА\n")
            sb.append("Задача: ${working.currentTask}\n")
            if (working.taskData.isNotEmpty()) {
                working.taskData.forEach { (k, v) -> sb.append("- $k: $v\n") }
            }
            if (working.decisions.isNotEmpty()) {
                sb.append("\nПринятые решения:\n")
                working.decisions.forEach { d ->
                    sb.append("- ${d.topic}: ${d.choice}")
                    if (d.reasoning.isNotBlank()) sb.append(" (причина: ${d.reasoning})")
                    sb.append("\n")
                }
            }
            if (working.openQuestions.isNotEmpty()) {
                sb.append("\nОткрытые вопросы:\n")
                working.openQuestions.forEach { q -> sb.append("- $q\n") }
            }
            parts.add(sb.toString())
        }

        // Приоритет 2: Long-Term Memory
        val hasLongTerm = longTerm.userProfile.isNotEmpty() ||
            longTerm.preferences.isNotEmpty() ||
            longTerm.knowledge.isNotEmpty()
        if (hasLongTerm) {
            val sb = StringBuilder("## ПРОФИЛЬ И ПРЕДПОЧТЕНИЯ\n")
            if (longTerm.userProfile.isNotEmpty()) {
                sb.append("Профиль пользователя:\n")
                longTerm.userProfile.forEach { (k, v) -> sb.append("- $k: $v\n") }
            }
            if (longTerm.preferences.isNotEmpty()) {
                sb.append("Предпочтения:\n")
                longTerm.preferences.forEach { (k, v) -> sb.append("- $k: $v\n") }
            }
            if (longTerm.knowledge.isNotEmpty()) {
                sb.append("Известные факты:\n")
                longTerm.knowledge.forEach { (k, v) -> sb.append("- $k: $v\n") }
            }
            parts.add(sb.toString())
        }

        // Приоритет 3: Summary старых сообщений
        if (shortTerm.summary.isNotBlank()) {
            parts.add("## ИСТОРИЯ РАЗГОВОРА\n${shortTerm.summary}")
        }

        if (parts.isEmpty()) return "You are a helpful AI assistant."

        return buildString {
            append("You are a helpful AI assistant with memory.\n")
            append("Use the following context to personalize your responses.\n\n")
            append(parts.joinToString("\n"))
        }
    }

    // ── Ручное управление ─────────────────────────────────────────────────────

    fun setTask(name: String) {
        working.setTask(name)
        refreshSnapshot()
    }

    fun clearShortTerm() {
        shortTerm.clear()
        _routerLog.value = ""
        refreshSnapshot()
    }

    fun clearLongTerm() {
        longTerm.clear()
        prefs.edit().remove("data").apply()
        refreshSnapshot()
    }

    fun clearAll() {
        shortTerm.clear()
        working.clear()
        longTerm.clear()
        prefs.edit().remove("data").apply()
        _routerLog.value = ""
        refreshSnapshot()
    }

    // ── Внутренние методы ─────────────────────────────────────────────────────

    private fun applyClassifications(classifications: List<Classification>, message: String) {
        for (c in classifications) {
            when {
                c.category == FactCategory.TRANSIENT -> {
                    // Ничего не сохраняем — остаётся только в short-term
                }
                c.layer == MemoryLayer.LONG_TERM && c.category == FactCategory.PROFILE -> {
                    longTerm.saveProfile(c.key ?: "general", c.value ?: message)
                    saveLongTerm()
                }
                c.layer == MemoryLayer.LONG_TERM && c.category == FactCategory.PREFERENCE -> {
                    longTerm.savePreference(c.key ?: "general", c.value ?: message)
                    saveLongTerm()
                }
                c.layer == MemoryLayer.LONG_TERM && c.category == FactCategory.KNOWLEDGE -> {
                    longTerm.saveKnowledge(c.key ?: "general", c.value ?: message)
                    saveLongTerm()
                }
                c.layer == MemoryLayer.WORKING && c.category == FactCategory.TASK_DATA -> {
                    if (working.currentTask == null && c.key == "project_topic") {
                        working.setTask(c.value ?: message)
                    } else {
                        working.save(c.key ?: "data", c.value ?: message)
                    }
                }
                c.layer == MemoryLayer.WORKING && c.category == FactCategory.DECISION -> {
                    working.addDecision("from_message", c.value ?: message, "Извлечено из разговора")
                }
            }
        }
    }

    private fun summarizeEvicted(evicted: List<MemoryMessage>) {
        val evictedText = evicted.joinToString(" | ") { "${it.role}: ${it.content.take(60)}" }
        val newSummary = if (shortTerm.summary.isNotBlank()) {
            "${shortTerm.summary} | $evictedText"
        } else {
            evictedText
        }
        shortTerm.updateSummary(newSummary)
    }

    private fun refreshSnapshot() {
        _snapshot.value = buildSnapshot()
    }

    private fun buildSnapshot() = MemorySnapshot(
        shortTermMessages = shortTerm.messages.toList(),
        shortTermSummary = shortTerm.summary,
        shortTermEvictedCount = shortTerm.evictedCount,
        workingTask = working.currentTask,
        workingTaskData = working.taskData.toMap(),
        workingDecisions = working.decisions.toList(),
        workingOpenQuestions = working.openQuestions.toList(),
        longTermProfile = longTerm.userProfile.toMap(),
        longTermPreferences = longTerm.preferences.toMap(),
        longTermKnowledge = longTerm.knowledge.toMap(),
    )

    // ── Персистентность LongTermMemory ────────────────────────────────────────

    private val prefs by lazy {
        context.getSharedPreferences("long_term_memory", Context.MODE_PRIVATE)
    }

    private fun saveLongTerm() {
        val json = JSONObject().apply {
            put("profile", JSONObject(longTerm.userProfile as Map<*, *>))
            put("preferences", JSONObject(longTerm.preferences as Map<*, *>))
            put("knowledge", JSONObject(longTerm.knowledge as Map<*, *>))
        }
        prefs.edit().putString("data", json.toString()).apply()
    }

    private fun loadLongTerm() {
        val str = prefs.getString("data", null) ?: return
        try {
            val json = JSONObject(str)
            json.optJSONObject("profile")?.let { obj ->
                obj.keys().forEach { k -> longTerm.userProfile[k] = obj.getString(k) }
            }
            json.optJSONObject("preferences")?.let { obj ->
                obj.keys().forEach { k -> longTerm.preferences[k] = obj.getString(k) }
            }
            json.optJSONObject("knowledge")?.let { obj ->
                obj.keys().forEach { k -> longTerm.knowledge[k] = obj.getString(k) }
            }
        } catch (_: Exception) {
            // Corrupt data — ignore and start fresh
        }
    }
}
