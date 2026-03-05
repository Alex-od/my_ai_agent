package ua.com.myaiagent.data.tasks

import java.util.UUID

object TaskStateMachine {

    fun create(event: TaskEvent.CreateTask): TaskState {
        val now = System.currentTimeMillis()
        val steps = event.steps.mapIndexed { i, desc -> TaskStep(index = i, description = desc) }
        return TaskState(
            taskId = UUID.randomUUID().toString(),
            title = event.title,
            description = event.description,
            stage = TaskStage.PLANNING,
            steps = steps,
            currentStepIndex = 0,
            expectedAction = "Review the plan and approve it to start execution.",
            pausedAtStage = null,
            context = emptyMap(),
            createdAt = now,
            updatedAt = now,
        )
    }

    fun handle(state: TaskState, event: TaskEvent): TaskState {
        val now = System.currentTimeMillis()
        return when (event) {
            is TaskEvent.CreateTask -> create(event)

            is TaskEvent.StartExecution -> {
                if (state.stage != TaskStage.PLANNING) return state
                val expected = state.steps.firstOrNull()?.description ?: "Execute steps."
                state.copy(stage = TaskStage.EXECUTION, currentStepIndex = 0, expectedAction = expected, updatedAt = now)
            }

            is TaskEvent.CompleteStep -> {
                val idx = state.currentStepIndex
                if (idx >= state.steps.size) return state
                val updatedSteps = state.steps.toMutableList()
                updatedSteps[idx] = updatedSteps[idx].copy(isCompleted = true, notes = event.notes)
                val nextIdx = idx + 1
                val nextExpected = if (nextIdx < updatedSteps.size)
                    updatedSteps[nextIdx].description
                else
                    "All steps completed. Use start_validation to proceed."
                state.copy(steps = updatedSteps, currentStepIndex = nextIdx, expectedAction = nextExpected, updatedAt = now)
            }

            is TaskEvent.StartValidation -> {
                if (state.stage != TaskStage.EXECUTION) return state
                if (!state.steps.all { it.isCompleted }) return state
                state.copy(stage = TaskStage.VALIDATION, expectedAction = "Review each completed step and verify the result.", updatedAt = now)
            }

            is TaskEvent.Complete -> {
                if (state.stage != TaskStage.VALIDATION) return state
                state.copy(stage = TaskStage.DONE, expectedAction = "Task is complete.", updatedAt = now)
            }

            is TaskEvent.Pause -> {
                if (state.stage == TaskStage.DONE || state.stage == TaskStage.PAUSED) return state
                state.copy(pausedAtStage = state.stage, stage = TaskStage.PAUSED, expectedAction = "Task is paused.", updatedAt = now)
            }

            is TaskEvent.Resume -> {
                if (state.stage != TaskStage.PAUSED) return state
                val resumedStage = state.pausedAtStage ?: TaskStage.EXECUTION
                val expected = state.currentStep?.description ?: "Continue execution."
                state.copy(stage = resumedStage, pausedAtStage = null, expectedAction = expected, updatedAt = now)
            }

            is TaskEvent.AddContextFact -> {
                state.copy(context = state.context + (event.key to event.value), updatedAt = now)
            }

            is TaskEvent.BackToStep -> {
                val i = event.stepIndex
                if (i < 0 || i >= state.steps.size) return state
                val updatedSteps = state.steps.toMutableList()
                updatedSteps[i] = updatedSteps[i].copy(isCompleted = false, notes = "")
                state.copy(steps = updatedSteps, currentStepIndex = i, expectedAction = updatedSteps[i].description, updatedAt = now)
            }
        }
    }

    fun buildSystemPrompt(state: TaskState): String = buildString {
        appendLine("## АКТИВНАЯ ЗАДАЧА: ${state.title}")
        appendLine("**Описание:** ${state.description}")
        appendLine("**Стадия:** ${state.stage.label}")
        appendLine()

        if (state.completedStepsCount > 0) {
            appendLine("## ВЫПОЛНЕННЫЕ ШАГИ")
            state.steps.filter { it.isCompleted }.forEach { step ->
                append("✓ Шаг ${step.index + 1} \"${step.description}\"")
                if (step.notes.isNotBlank()) append(" — [notes: ${step.notes}]")
                appendLine()
            }
            appendLine()
        }

        when (state.stage) {
            TaskStage.PLANNING -> {
                appendLine("## СТАДИЯ ПЛАНИРОВАНИЯ")
                appendLine("Цель: \"${state.title}\"")
                appendLine()
                appendLine("Предложи конкретный план — список шагов для достижения цели.")
                appendLine("Жди ЯВНОГО одобрения от пользователя (например: «хорошо», «давай», «начинай»).")
                appendLine("⚠️ НЕ вызывай start_execution пока пользователь не одобрил план явно.")
            }
            TaskStage.PAUSED -> {
                appendLine("## ЗАДАЧА НА ПАУЗЕ")
                appendLine("Была на стадии: ${state.pausedAtStage?.label ?: "—"}")
                appendLine("Текущий шаг: ${state.currentStepIndex + 1}/${state.steps.size} — ${state.currentStep?.description ?: "—"}")
            }
            TaskStage.VALIDATION -> {
                appendLine("## СТАДИЯ ПРОВЕРКИ")
                appendLine("Все шаги выполнены. Проверь каждый шаг по результатам из notes:")
                state.steps.forEach { step ->
                    appendLine("- Шаг ${step.index + 1} \"${step.description}\": ${step.notes.ifBlank { "нет notes" }}")
                }
                appendLine("Если всё корректно — вызови complete_task. Если есть проблемы — вызови back_to_step.")
            }
            TaskStage.DONE -> {
                appendLine("## ЗАДАЧА ЗАВЕРШЕНА")
            }
            else -> {
                val current = state.currentStep
                if (current != null) {
                    appendLine("## ТЕКУЩИЙ ШАГ (${state.currentStepIndex + 1}/${state.steps.size})")
                    appendLine("\"${current.description}\"")
                    appendLine("Ожидаемое действие: ${state.expectedAction}")
                }
            }
        }
        appendLine()

        if (state.context.isNotEmpty()) {
            appendLine("## КОНТЕКСТ ЗАДАЧИ")
            state.context.forEach { (k, v) -> appendLine("- $k: $v") }
            appendLine()
        }

        appendLine("## ИНСТРУКЦИИ ДЛЯ LLM")
        appendLine("Используй tools для управления состоянием задачи:")
        appendLine("- complete_step(notes): когда пользователь подтверждает завершение текущего шага")
        appendLine("- start_execution: когда пользователь одобряет план")
        appendLine("- start_validation: ТОЛЬКО когда все шаги выполнены (все complete_step вызваны)")
        appendLine("- complete_task: после успешной проверки")
        appendLine("- pause_task / resume_task: по запросу пользователя")
        appendLine("- add_context_fact(key, value): когда пользователь называет важный факт")
        appendLine("- back_to_step(step_index): когда нужно вернуться к предыдущему шагу")
        appendLine("Переходы стадий — только через явные события. Не меняй стадию автоматически.")
    }
}
