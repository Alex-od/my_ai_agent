package ua.com.myaiagent.data.memory

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class UserProfileStore(private val context: Context) {

    private val storeFile by lazy { File(context.filesDir, "profiles.json") }

    private val _profiles = MutableStateFlow<List<UserProfile>>(emptyList())
    val profilesFlow: StateFlow<List<UserProfile>> = _profiles.asStateFlow()

    private val _activeProfile = MutableStateFlow(UserProfile(id = "", name = ""))
    val profileFlow: StateFlow<UserProfile> = _activeProfile.asStateFlow()

    val profile: UserProfile get() = _activeProfile.value

    init { load() }

    fun setProfile(profile: UserProfile) {
        _activeProfile.value = profile
        persist()
    }

    fun setActive(id: String) {
        val found = _profiles.value.find { it.id == id } ?: return
        _activeProfile.value = found
        persist()
    }

    fun addProfile(profile: UserProfile) {
        _profiles.value = _profiles.value + profile
        if (_activeProfile.value.id.isBlank()) _activeProfile.value = profile
        persist()
    }

    fun deleteProfile(id: String) {
        _profiles.value = _profiles.value.filter { it.id != id }
        if (_activeProfile.value.id == id) {
            _activeProfile.value = _profiles.value.firstOrNull() ?: UserProfile("", "")
        }
        persist()
    }

    private fun persist() {
        val arr = JSONArray()
        _profiles.value.forEach { p ->
            arr.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("description", p.description)
            })
        }
        val root = JSONObject().apply {
            put("profiles", arr)
            put("activeId", _activeProfile.value.id)
        }
        storeFile.writeText(root.toString())
    }

    private fun load() {
        if (!storeFile.exists()) return
        try {
            val root = JSONObject(storeFile.readText())
            val arr = root.optJSONArray("profiles") ?: JSONArray()
            val list = buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    add(UserProfile(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        description = obj.optString("description", ""),
                    ))
                }
            }
            _profiles.value = list
            val activeId = root.optString("activeId", "")
            _activeProfile.value = list.find { it.id == activeId } ?: list.firstOrNull() ?: UserProfile("", "")
        } catch (_: Exception) {}
    }
}
