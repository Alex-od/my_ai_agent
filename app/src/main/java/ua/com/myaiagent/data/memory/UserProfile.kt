package ua.com.myaiagent.data.memory

data class UserProfile(
    val id: String,
    val name: String,
    val description: String = "",
) {
    fun toSystemPromptSection(): String = buildString {
        if (name.isNotBlank()) append("## Профиль: $name\n")
        if (description.isNotBlank()) {
            append(description)
            append("\n")
        }
    }
}
