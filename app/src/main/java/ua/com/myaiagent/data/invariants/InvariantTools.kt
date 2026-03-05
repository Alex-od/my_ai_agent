package ua.com.myaiagent.data.invariants

import ua.com.myaiagent.data.tasks.ToolDefinition
import ua.com.myaiagent.data.tasks.ToolParameters
import ua.com.myaiagent.data.tasks.ToolProperty

fun invariantTools(): List<ToolDefinition> = listOf(
    ToolDefinition(
        name = "check_approach",
        description = "MANDATORY: call this before giving any technical advice. Returns all active invariants so you can check for violations.",
        parameters = ToolParameters(
            properties = mapOf(
                "approach_description" to ToolProperty("string", "Brief description of the technical approach you are about to recommend"),
            ),
            required = listOf("approach_description"),
        ),
    ),
    ToolDefinition(
        name = "report_violation",
        description = "Report that a proposed approach violates a project invariant. Call this when check_approach reveals a HARD violation.",
        parameters = ToolParameters(
            properties = mapOf(
                "invariant_id" to ToolProperty("string", "ID of the violated invariant, e.g. STACK-002"),
                "reason" to ToolProperty("string", "Explanation of why this approach violates the invariant"),
                "alternative" to ToolProperty("string", "Suggested alternative that respects the invariant"),
            ),
            required = listOf("invariant_id", "reason"),
        ),
    ),
    ToolDefinition(
        name = "add_invariant",
        description = "Add a new project invariant rule.",
        parameters = ToolParameters(
            properties = mapOf(
                "category" to ToolProperty("string", "Category: ARCH, STACK, UX, SECURITY, or CUSTOM"),
                "title" to ToolProperty("string", "Short name for the invariant chip"),
                "rule" to ToolProperty("string", "Full rule text injected into system prompt"),
                "severity" to ToolProperty("string", "HARD (prohibition) or SOFT (recommendation)"),
            ),
            required = listOf("category", "title", "rule", "severity"),
        ),
    ),
    ToolDefinition(
        name = "remove_invariant",
        description = "Remove an invariant rule by ID.",
        parameters = ToolParameters(
            properties = mapOf(
                "id" to ToolProperty("string", "ID of the invariant to remove"),
            ),
            required = listOf("id"),
        ),
    ),
    ToolDefinition(
        name = "toggle_invariant",
        description = "Enable or disable an invariant without deleting it.",
        parameters = ToolParameters(
            properties = mapOf(
                "id" to ToolProperty("string", "ID of the invariant"),
                "enabled" to ToolProperty("boolean", "true to enable, false to disable"),
            ),
            required = listOf("id", "enabled"),
        ),
    ),
)
