package ua.com.myaiagent.data.invariants

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class InvariantStore(private val context: Context) {

    private val storeFile: File by lazy { File(context.filesDir, "invariants.json") }

    private val _invariantsFlow = MutableStateFlow<List<Invariant>>(emptyList())
    val invariantsFlow: StateFlow<List<Invariant>> = _invariantsFlow.asStateFlow()

    val invariants: List<Invariant> get() = _invariantsFlow.value
    val activeInvariants: List<Invariant> get() = _invariantsFlow.value.filter { it.enabled }

    init {
        load()
    }

    fun add(invariant: Invariant) {
        _invariantsFlow.value = _invariantsFlow.value + invariant
        persist()
    }

    fun remove(id: String) {
        _invariantsFlow.value = _invariantsFlow.value.filter { it.id != id }
        persist()
    }

    fun toggle(id: String, enabled: Boolean) {
        _invariantsFlow.value = _invariantsFlow.value.map {
            if (it.id == id) it.copy(enabled = enabled) else it
        }
        persist()
    }

    fun update(invariant: Invariant) {
        _invariantsFlow.value = _invariantsFlow.value.map {
            if (it.id == invariant.id) invariant else it
        }
        persist()
    }

    fun findById(id: String): Invariant? = _invariantsFlow.value.find { it.id == id }

    private fun persist() {
        val arr = JSONArray()
        _invariantsFlow.value.forEach { inv ->
            arr.put(JSONObject().apply {
                put("id", inv.id)
                put("category", inv.category.name)
                put("title", inv.title)
                put("rule", inv.rule)
                put("severity", inv.severity.name)
                put("enabled", inv.enabled)
                put("lockedByTask", inv.lockedByTask)
                put("createdAt", inv.createdAt)
            })
        }
        storeFile.writeText(arr.toString())
    }

    private fun load() {
        if (!storeFile.exists()) {
            _invariantsFlow.value = defaultInvariants()
            persist()
            return
        }
        try {
            val arr = JSONArray(storeFile.readText())
            val list = buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val category = InvariantCategory.entries.find { it.name == obj.getString("category") }
                        ?: InvariantCategory.CUSTOM
                    val severity = InvariantSeverity.entries.find { it.name == obj.getString("severity") }
                        ?: InvariantSeverity.HARD
                    add(Invariant(
                        id = obj.getString("id"),
                        category = category,
                        title = obj.getString("title"),
                        rule = obj.getString("rule"),
                        severity = severity,
                        enabled = obj.optBoolean("enabled", true),
                        lockedByTask = obj.optBoolean("lockedByTask", false),
                        createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                    ))
                }
            }
            _invariantsFlow.value = list
        } catch (_: Exception) {
            _invariantsFlow.value = defaultInvariants()
            persist()
        }
    }
}
