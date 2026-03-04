package ua.com.myaiagent.data.tasks

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class TaskStore(private val context: Context) {

    private val storeFile: File by lazy { File(context.filesDir, "task_state.json") }

    private val _taskFlow = MutableStateFlow<TaskState?>(null)
    val taskFlow: StateFlow<TaskState?> = _taskFlow.asStateFlow()

    val currentTask: TaskState? get() = _taskFlow.value

    init {
        load()
    }

    fun save(state: TaskState) {
        _taskFlow.value = state
        persist(state)
    }

    fun clear() {
        _taskFlow.value = null
        storeFile.delete()
    }

    private fun persist(state: TaskState) {
        val stepsJson = JSONArray().apply {
            state.steps.forEach { step ->
                put(JSONObject().apply {
                    put("index", step.index)
                    put("description", step.description)
                    put("isCompleted", step.isCompleted)
                    put("notes", step.notes)
                })
            }
        }
        val contextJson = JSONObject().apply {
            state.context.forEach { (k, v) -> put(k, v) }
        }
        val json = JSONObject().apply {
            put("taskId", state.taskId)
            put("title", state.title)
            put("description", state.description)
            put("stage", state.stage.name)
            put("steps", stepsJson)
            put("currentStepIndex", state.currentStepIndex)
            put("expectedAction", state.expectedAction)
            state.pausedAtStage?.let { put("pausedAtStage", it.name) }
            put("context", contextJson)
            put("createdAt", state.createdAt)
            put("updatedAt", state.updatedAt)
        }
        storeFile.writeText(json.toString())
    }

    private fun load() {
        val str = if (storeFile.exists()) storeFile.readText() else return
        try {
            val json = JSONObject(str)
            val stepsArr = json.optJSONArray("steps") ?: JSONArray()
            val steps = buildList {
                for (i in 0 until stepsArr.length()) {
                    val s = stepsArr.getJSONObject(i)
                    add(TaskStep(
                        index = s.getInt("index"),
                        description = s.getString("description"),
                        isCompleted = s.optBoolean("isCompleted", false),
                        notes = s.optString("notes", ""),
                    ))
                }
            }
            val contextObj = json.optJSONObject("context") ?: JSONObject()
            val taskContext = buildMap<String, String> {
                contextObj.keys().forEach { k -> put(k, contextObj.getString(k)) }
            }
            val stage = TaskStage.entries.find { it.name == json.getString("stage") } ?: TaskStage.PLANNING
            val pausedAtStageName = json.optString("pausedAtStage", "")
            val pausedAtStage = TaskStage.entries.find { it.name == pausedAtStageName }

            _taskFlow.value = TaskState(
                taskId = json.getString("taskId"),
                title = json.getString("title"),
                description = json.getString("description"),
                stage = stage,
                steps = steps,
                currentStepIndex = json.getInt("currentStepIndex"),
                expectedAction = json.optString("expectedAction", ""),
                pausedAtStage = pausedAtStage,
                context = taskContext,
                createdAt = json.getLong("createdAt"),
                updatedAt = json.getLong("updatedAt"),
            )
        } catch (_: Exception) {
            // Corrupt state — start fresh
        }
    }
}
