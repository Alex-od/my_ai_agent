package ua.com.myaiagent.data.tasks

enum class TaskStage(val label: String, val index: Int) {
    PLANNING("Планирование", 0),
    EXECUTION("Выполнение", 1),
    VALIDATION("Проверка", 2),
    DONE("Готово", 3),
    PAUSED("Пауза", -1),
}

data class TaskStep(
    val index: Int,
    val description: String,
    val isCompleted: Boolean = false,
    val notes: String = "",
)

data class TaskState(
    val taskId: String,
    val title: String,
    val description: String,
    val stage: TaskStage,
    val steps: List<TaskStep>,
    val currentStepIndex: Int,
    val expectedAction: String,
    val pausedAtStage: TaskStage?,
    val context: Map<String, String>,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val isActive: Boolean get() = stage != TaskStage.DONE
    val isPaused: Boolean get() = stage == TaskStage.PAUSED
    val currentStep: TaskStep? get() = steps.getOrNull(currentStepIndex)
    val completedStepsCount: Int get() = steps.count { it.isCompleted }
}

sealed class TaskEvent {
    data class CreateTask(val title: String, val description: String, val steps: List<String>) : TaskEvent()
    data object StartExecution : TaskEvent()
    data class CompleteStep(val notes: String = "") : TaskEvent()
    data object StartValidation : TaskEvent()
    data object Complete : TaskEvent()
    data object Pause : TaskEvent()
    data object Resume : TaskEvent()
    data class AddContextFact(val key: String, val value: String) : TaskEvent()
    data class BackToStep(val stepIndex: Int) : TaskEvent()
}
