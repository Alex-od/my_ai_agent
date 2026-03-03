package ua.com.myaiagent.data.memory

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Хранилище профиля пользователя.
 *
 * Персистентно через `user_profile.json` в filesDir.
 * Синглтон в Koin — разделяется между всеми ViewModel.
 */
class UserProfileStore(private val context: Context) {

    private val profileFile by lazy {
        java.io.File(context.filesDir, "user_profile.json")
    }

    private val _profile = MutableStateFlow<UserProfile>(ProfilePresets.Developer)
    val profileFlow: StateFlow<UserProfile> = _profile.asStateFlow()

    val profile: UserProfile get() = _profile.value

    init {
        load()
    }

    fun setProfile(profile: UserProfile) {
        _profile.value = profile
        save()
    }

    fun updateProfile(update: UserProfile.() -> UserProfile) {
        _profile.value = _profile.value.update()
        save()
    }

    // ── Персистентность ───────────────────────────────────────────────────────

    private fun save() {
        val p = _profile.value
        val json = JSONObject().apply {
            put("id", p.id)
            put("name", p.name)
            put("role", p.role)
            put("language", p.language)
            put("responseStyle", p.responseStyle.name)
            put("expertiseLevel", p.expertiseLevel.name)
            put("useMarkdown", p.useMarkdown)
            put("useEmoji", p.useEmoji)
            put("restrictions", JSONArray(p.restrictions))
            put("customInstructions", p.customInstructions)
        }
        profileFile.writeText(json.toString())
    }

    private fun load() {
        val str = if (profileFile.exists()) profileFile.readText() else return
        try {
            val json = JSONObject(str)
            val restrictions = buildList {
                json.optJSONArray("restrictions")?.let { arr ->
                    for (i in 0 until arr.length()) add(arr.getString(i))
                }
            }
            _profile.value = UserProfile(
                id = json.optString("id", "custom"),
                name = json.optString("name", ""),
                role = json.optString("role", ""),
                language = json.optString("language", "Russian"),
                responseStyle = ResponseStyle.entries.find { it.name == json.optString("responseStyle") }
                    ?: ResponseStyle.CONCISE,
                expertiseLevel = ExpertiseLevel.entries.find { it.name == json.optString("expertiseLevel") }
                    ?: ExpertiseLevel.INTERMEDIATE,
                useMarkdown = json.optBoolean("useMarkdown", true),
                useEmoji = json.optBoolean("useEmoji", false),
                restrictions = restrictions,
                customInstructions = json.optString("customInstructions", ""),
            )
        } catch (_: Exception) {
            // Corrupt data — keep default
        }
    }
}
