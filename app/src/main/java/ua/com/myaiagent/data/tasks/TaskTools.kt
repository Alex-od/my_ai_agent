package ua.com.myaiagent.data.tasks

import kotlinx.serialization.Serializable

@Serializable
data class ToolDefinition(
    val type: String = "function",
    val name: String,
    val description: String,
    val parameters: ToolParameters,
)

@Serializable
data class ToolParameters(
    val type: String = "object",
    val properties: Map<String, ToolProperty> = emptyMap(),
    val required: List<String> = emptyList(),
)

@Serializable
data class ToolProperty(
    val type: String,
    val description: String,
)

fun taskStateMachineTools(): List<ToolDefinition> = listOf(
    ToolDefinition(
        name = "complete_step",
        description = "Mark the current step as done. Use when user confirms step completion.",
        parameters = ToolParameters(
            properties = mapOf("notes" to ToolProperty("string", "Brief summary of what was done in this step")),
        ),
    ),
    ToolDefinition(
        name = "start_execution",
        description = "Move from PLANNING to EXECUTION. Use when user approves the plan.",
        parameters = ToolParameters(),
    ),
    ToolDefinition(
        name = "start_validation",
        description = "Move from EXECUTION to VALIDATION. Use when all steps are marked complete.",
        parameters = ToolParameters(),
    ),
    ToolDefinition(
        name = "complete_task",
        description = "Mark task as DONE after successful validation.",
        parameters = ToolParameters(),
    ),
    ToolDefinition(
        name = "pause_task",
        description = "Pause the current task.",
        parameters = ToolParameters(),
    ),
    ToolDefinition(
        name = "resume_task",
        description = "Resume a paused task.",
        parameters = ToolParameters(),
    ),
    ToolDefinition(
        name = "add_context_fact",
        description = "Add a key-value fact extracted from the conversation to task context.",
        parameters = ToolParameters(
            properties = mapOf(
                "key" to ToolProperty("string", "Fact name, e.g. 'platform', 'language'"),
                "value" to ToolProperty("string", "Fact value"),
            ),
            required = listOf("key", "value"),
        ),
    ),
    ToolDefinition(
        name = "back_to_step",
        description = "Go back to a previous step for rework.",
        parameters = ToolParameters(
            properties = mapOf("step_index" to ToolProperty("integer", "0-based index of step to return to")),
            required = listOf("step_index"),
        ),
    ),
)
