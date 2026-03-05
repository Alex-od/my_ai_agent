package ua.com.myaiagent

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.koin.androidx.compose.koinViewModel
import ua.com.myaiagent.data.tasks.TaskState
import ua.com.myaiagent.data.tasks.TaskStage
import ua.com.myaiagent.data.tasks.TaskStep

// ── Stage colors ──────────────────────────────────────────────────────────────
private val StagePlanningColor  = Color(0xFF9C27B0) // Purple
private val StageExecutionColor = Color(0xFF2196F3) // Blue
private val StageValidateColor  = Color(0xFFFF9800) // Orange
private val StageDoneColor      = Color(0xFF4CAF50) // Green
private val StagePausedColor    = Color(0xFFFFC107) // Amber

private fun stageColor(stage: TaskStage): Color = when (stage) {
    TaskStage.PLANNING   -> StagePlanningColor
    TaskStage.EXECUTION  -> StageExecutionColor
    TaskStage.VALIDATION -> StageValidateColor
    TaskStage.DONE       -> StageDoneColor
    TaskStage.PAUSED     -> StagePausedColor
}

// ── Screen ────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun Week3Screen(
    viewModel: Week3ViewModel = koinViewModel(),
    showLogs: Boolean = false,
    onDismissLogs: () -> Unit = {},
    modelId: String = "gpt-4.1-mini",
) {
    val chatMessages by viewModel.chatMessages.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val taskState by viewModel.taskStore.taskFlow.collectAsState()
    val lastSystemPrompt by viewModel.lastSystemPrompt.collectAsState()
    val toolCallLog by viewModel.toolCallLog.collectAsState()
    val lastRawResponse by viewModel.lastRawResponse.collectAsState()
    val lastUserContent by viewModel.lastUserContent.collectAsState()

    var userInput by remember { mutableStateOf("") }
    var panelExpanded by remember { mutableStateOf(true) }
    var completedExpanded by remember { mutableStateOf(false) }
    var logTab by remember { mutableIntStateOf(0) }
    var showInvariantsSheet by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()

    LaunchedEffect(chatMessages.size) {
        if (chatMessages.isNotEmpty()) listState.animateScrollToItem(chatMessages.size - 1)
    }

    Column(modifier = Modifier.fillMaxSize()) {

        // ── Task State Panel ──────────────────────────────────────────────────
        Surface(shadowElevation = 2.dp) {
            Column(modifier = Modifier.fillMaxWidth()) {

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val state = taskState
                    if (state != null) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(stageColor(state.stage)),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = state.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = state.stage.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = stageColor(state.stage),
                            )
                        }
                    } else {
                        Text(
                            text = "Опишите задачу внизу",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    OutlinedButton(
                        onClick = { showInvariantsSheet = true },
                        modifier = Modifier.height(28.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp),
                    ) {
                        Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Инварианты", style = MaterialTheme.typography.labelSmall)
                    }

                    if (taskState != null) {
                        IconButton(onClick = { panelExpanded = !panelExpanded }, modifier = Modifier.size(32.dp)) {
                            Icon(
                                imageVector = if (panelExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }

                AnimatedVisibility(visible = panelExpanded && taskState != null) {
                    val state = taskState
                    if (state != null) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {

                            // Stage timeline
                            StageTimeline(state)

                            Spacer(modifier = Modifier.height(8.dp))

                            // Steps
                            if (state.stage == TaskStage.EXECUTION || state.stage == TaskStage.VALIDATION) {
                                if (state.steps.isNotEmpty()) {
                                    StepsListCard(state)
                                    Spacer(modifier = Modifier.height(6.dp))
                                }
                            } else if (state.stage != TaskStage.DONE) {
                                CurrentStepCard(state)
                                Spacer(modifier = Modifier.height(6.dp))
                            }

                            // Context facts
                            if (state.context.isNotEmpty()) {
                                ContextFactsCard(state.context)
                                Spacer(modifier = Modifier.height(6.dp))
                            }

                            Spacer(modifier = Modifier.height(4.dp))
                        }
                    }
                }

            }
        }

        // ── Chat messages ─────────────────────────────────────────────────────
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Spacer(modifier = Modifier.height(4.dp)) }

            items(chatMessages) { (role, content) ->
                Day13MessageBubble(role = role, content = content)
            }

            if (isLoading) {
                item {
                    Row(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Думаю...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            if (error != null) {
                item {
                    SelectionContainer {
                        Text(
                            text = "Ошибка: $error",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                        )
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(4.dp)) }
        }

        // ── Input row ─────────────────────────────────────────────────────────
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = userInput,
                onValueChange = { userInput = it },
                placeholder = { Text(if (taskState == null) "Опишите вашу задачу..." else "Напишите что-нибудь...") },
                modifier = Modifier.weight(1f),
                maxLines = 4,
                enabled = !isLoading,
            )
            Spacer(modifier = Modifier.width(8.dp))
            if (isLoading) {
                Button(
                    onClick = { viewModel.stop() },
                    modifier = Modifier.height(56.dp),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Icon(Icons.Default.Stop, contentDescription = "Стоп")
                }
            } else {
                Button(
                    onClick = {
                        val text = userInput.trim()
                        if (text.isNotBlank()) {
                            userInput = ""
                            viewModel.send(text, modelId)
                        }
                    },
                    enabled = userInput.isNotBlank(),
                    modifier = Modifier.height(56.dp),
                ) {
                    Icon(Icons.Default.Send, contentDescription = "Отправить")
                }
            }
        }
    }

    // ── Invariants Sheet ──────────────────────────────────────────────────────
    if (showInvariantsSheet) {
        InvariantManagerSheet(
            invariantStore = viewModel.invariantStore,
            onDismiss = { showInvariantsSheet = false },
        )
    }

    // ── Logs Dialog ───────────────────────────────────────────────────────────
    if (showLogs) {
        AlertDialog(
            onDismissRequest = onDismissLogs,
            title = { Text("Логи — День 13") },
            text = {
                Column {
                    PrimaryTabRow(selectedTabIndex = logTab) {
                        Tab(selected = logTab == 0, onClick = { logTab = 0 }, text = { Text("Лог") })
                        Tab(selected = logTab == 1, onClick = { logTab = 1 }, text = { Text("JSON") })
                        Tab(selected = logTab == 2, onClick = { logTab = 2 }, text = { Text("Промпт") })
                        Tab(selected = logTab == 3, onClick = { logTab = 3 }, text = { Text("Сообщение") })
                        Tab(selected = logTab == 4, onClick = { logTab = 4 }, text = { Text("Автомат") })
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    SelectionContainer {
                        Text(
                            text = when (logTab) {
                                0 -> toolCallLog.joinToString("\n\n").ifBlank { "Tool calls не было" }
                                1 -> lastRawResponse.ifBlank { "Нет данных" }
                                2 -> lastSystemPrompt.ifBlank { "Промпт не сформирован" }
                                3 -> lastUserContent.ifBlank { "Нет данных" }
                                else -> buildStateMachineDump(taskState)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.verticalScroll(rememberScrollState()),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismissLogs) { Text("Закрыть") }
            },
        )
    }
}

// ── Stage Timeline ────────────────────────────────────────────────────────────

@Composable
private fun StageTimeline(state: TaskState) {
    val mainStages = listOf(TaskStage.PLANNING, TaskStage.EXECUTION, TaskStage.VALIDATION, TaskStage.DONE)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        mainStages.forEachIndexed { i, stage ->
            val isActive = if (state.stage == TaskStage.PAUSED) {
                state.pausedAtStage == stage
            } else {
                state.stage == stage
            }
            val isDone = if (state.stage == TaskStage.PAUSED) {
                (state.pausedAtStage?.index ?: -1) > stage.index
            } else {
                state.stage.index > stage.index
            }
            val circleColor = when {
                isActive && state.stage == TaskStage.PAUSED -> StagePausedColor
                isActive -> stageColor(stage)
                isDone   -> stageColor(stage).copy(alpha = 0.7f)
                else     -> MaterialTheme.colorScheme.outlineVariant
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(circleColor),
                ) {
                    if (isActive && state.stage == TaskStage.PAUSED) {
                        Icon(
                            Icons.Default.Pause,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = Color.White,
                        )
                    } else if (isDone) {
                        Text("✓", style = MaterialTheme.typography.labelSmall, color = Color.White)
                    } else {
                        Text(
                            text = "${stage.index + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isActive) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    text = stage.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isActive || isDone) stageColor(if (state.stage == TaskStage.PAUSED && isActive) TaskStage.PAUSED else stage)
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }

            if (i < mainStages.size - 1) {
                HorizontalDivider(
                    modifier = Modifier.width(8.dp).padding(bottom = 16.dp),
                    thickness = 1.5.dp,
                    color = if (isDone) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    else MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }
    }
}

// ── Steps List Card (Execution / Validation) ──────────────────────────────────

@Composable
private fun StepsListCard(state: TaskState) {
    val color = stageColor(state.stage)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.06f)),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.3f)),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = "${state.stage.label} · ${state.completedStepsCount}/${state.steps.size}",
                style = MaterialTheme.typography.labelSmall,
                color = color,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            state.steps.forEach { step ->
                val isCurrent = !step.isCompleted && step.index == state.currentStepIndex
                Row(
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier.padding(vertical = 2.dp),
                ) {
                    Text(
                        text = if (step.isCompleted) "✓" else if (isCurrent) "→" else "○",
                        style = MaterialTheme.typography.labelSmall,
                        color = when {
                            step.isCompleted -> MaterialTheme.colorScheme.primary
                            isCurrent -> color
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.width(18.dp),
                    )
                    Text(
                        text = step.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            step.isCompleted -> MaterialTheme.colorScheme.onSurfaceVariant
                            isCurrent -> MaterialTheme.colorScheme.onSurface
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontWeight = if (isCurrent) FontWeight.Medium else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

// ── Current Step Card ─────────────────────────────────────────────────────────

@Composable
private fun CurrentStepCard(state: TaskState) {
    val color = stageColor(state.stage)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.08f)),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.4f)),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${state.stage.label}",
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (state.stage == TaskStage.PAUSED) "⏸ Пауза"
                    else "${state.currentStepIndex + 1}/${state.steps.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                )
            }
            if (state.stage == TaskStage.PAUSED) {
                Text(
                    text = "Задача на паузе. Напишите «продолжи» для возобновления.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            } else {
                val current = state.currentStep
                if (current != null) {
                    Text(
                        text = current.description,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (state.expectedAction.isNotBlank()) {
                    Text(
                        text = state.expectedAction,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

// ── Completed Steps Card ──────────────────────────────────────────────────────

@Composable
private fun CompletedStepsCard(steps: List<TaskStep>, expanded: Boolean, onToggle: () -> Unit) {
    Card(
        onClick = onToggle,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = StageDoneColor.copy(alpha = 0.06f)),
        border = androidx.compose.foundation.BorderStroke(1.dp, StageDoneColor.copy(alpha = 0.3f)),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Выполнено: ${steps.size}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = StageDoneColor,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = StageDoneColor,
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 6.dp)) {
                    steps.forEach { step ->
                        Row(
                            modifier = Modifier.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Text(
                                text = "✓",
                                style = MaterialTheme.typography.labelSmall,
                                color = StageDoneColor,
                                modifier = Modifier.width(16.dp),
                            )
                            Column {
                                Text(
                                    text = step.description,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Medium,
                                )
                                if (step.notes.isNotBlank()) {
                                    Text(
                                        text = step.notes,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Context Facts Card ────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ContextFactsCard(context: Map<String, String>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = "Контекст",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                context.forEach { (k, v) ->
                    AssistChip(
                        onClick = {},
                        label = { Text("$k: $v", style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }
        }
    }
}

// ── Message Bubble ────────────────────────────────────────────────────────────

@Composable
private fun Day13MessageBubble(role: String, content: String) {
    val isUser = role == "user"
    SelectionContainer {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        ) {
            Box(
                modifier = Modifier
                    .clip(
                        RoundedCornerShape(
                            topStart = 12.dp, topEnd = 12.dp,
                            bottomStart = if (isUser) 12.dp else 2.dp,
                            bottomEnd = if (isUser) 2.dp else 12.dp,
                        )
                    )
                    .background(
                        if (isUser) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .fillMaxWidth(0.85f),
            ) {
                Text(
                    text = content,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ── Dialogs ───────────────────────────────────────────────────────────────────

@Composable
private fun CreateTaskDialog(onDismiss: () -> Unit, onCreate: (String, String, List<String>) -> Unit) {
    var title by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новая задача") },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Цель") },
                placeholder = { Text("Например: сделать лендинг для продукта") },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(
                onClick = { if (title.isNotBlank()) onCreate(title.trim(), "", emptyList()) },
                enabled = title.isNotBlank(),
            ) { Text("Создать") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}


// ── Helpers ───────────────────────────────────────────────────────────────────

private fun buildStateMachineDump(state: TaskState?): String {
    if (state == null) return "Нет активной задачи"
    return buildString {
        appendLine("=== Task State Machine Dump ===")
        appendLine("ID:        ${state.taskId}")
        appendLine("Title:     ${state.title}")
        appendLine("Stage:     ${state.stage.name} (${state.stage.label})")
        if (state.pausedAtStage != null) appendLine("Paused at: ${state.pausedAtStage.name}")
        appendLine("Step:      ${state.currentStepIndex}/${state.steps.size}")
        appendLine("Action:    ${state.expectedAction}")
        appendLine()
        appendLine("--- Steps ---")
        state.steps.forEach { step ->
            val mark = if (step.isCompleted) "✓" else "○"
            append("$mark [${step.index}] ${step.description}")
            if (step.notes.isNotBlank()) append(" | notes: ${step.notes}")
            appendLine()
        }
        if (state.context.isNotEmpty()) {
            appendLine()
            appendLine("--- Context ---")
            state.context.forEach { (k, v) -> appendLine("$k = $v") }
        }
        appendLine()
        appendLine("Created:   ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(state.createdAt))}")
        appendLine("Updated:   ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(state.updatedAt))}")
    }
}
