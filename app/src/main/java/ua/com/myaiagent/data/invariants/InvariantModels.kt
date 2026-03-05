package ua.com.myaiagent.data.invariants

enum class InvariantSeverity(val label: String) { HARD("Запрет"), SOFT("Рекомендация") }

enum class InvariantCategory(val label: String) {
    ARCH("Архитектура"), STACK("Стек"), UX("UX / UI"), SECURITY("Безопасность"), CUSTOM("Своё")
}

data class Invariant(
    val id: String,
    val category: InvariantCategory,
    val title: String,
    val rule: String,
    val severity: InvariantSeverity,
    val enabled: Boolean = true,
    val lockedByTask: Boolean = false,  // true = создан из TaskStateMachine, нельзя менять через UI
    val createdAt: Long = System.currentTimeMillis(),
)

data class ViolationRecord(
    val invariantId: String,
    val reason: String,
    val alternative: String = "",
    val timestamp: Long = System.currentTimeMillis(),
)

fun defaultInvariants(): List<Invariant> = emptyList()
