package ua.com.myaiagent

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.koin.androidx.compose.koinViewModel
import ua.com.myaiagent.data.pipeline.SearchMode

// ── Step colors ───────────────────────────────────────────────────────────────

private val PendingColor = Color(0xFF9E9E9E)
private val RunningColor = Color(0xFF2196F3)
private val DoneColor = Color(0xFF4CAF50)
private val ErrorColor = Color(0xFFF44336)

private fun stepStatusColor(status: StepStatus): Color = when (status) {
    StepStatus.PENDING -> PendingColor
    StepStatus.RUNNING -> RunningColor
    StepStatus.DONE -> DoneColor
    StepStatus.ERROR -> ErrorColor
}

// ── Screen ────────────────────────────────────────────────────────────────────

@Composable
fun Week4Screen(
    viewModel: Week4ViewModel = koinViewModel(),
    modelId: String = "gpt-4.1-mini",
    showLogs: Boolean = false,
    onDismissLogs: () -> Unit = {},
    onReset: (() -> Unit)? = null,
) {
    val messages by viewModel.messages.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val pipeline by viewModel.pipeline.collectAsState()
    val toolCallLog by viewModel.toolCallLog.collectAsState()
    val searchMode by viewModel.searchMode.collectAsState()

    var userInput by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    val totalItems = messages.size + toolCallLog.size
    LaunchedEffect(totalItems) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(listState.layoutInfo.totalItemsCount.coerceAtLeast(0))
    }

    Column(modifier = Modifier.fillMaxSize()) {

        // ── Pipeline Panel ────────────────────────────────────────────────────
        Surface(shadowElevation = 2.dp) {
            PipelinePanel(pipeline)
        }

        // ── Saved file banner ────────────────────────────────────────────────
        val savedPath = pipeline.savedFilePath
        if (savedPath != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DoneColor.copy(alpha = 0.15f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Сохранено: ${savedPath.substringAfterLast("/")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = DoneColor,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        // ── Messages ──────────────────────────────────────────────────────────
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Spacer(modifier = Modifier.height(4.dp)) }

            // Interleave messages and tool calls in order
            // We show messages as chat bubbles and tool calls as cards
            items(messages) { (role, content) ->
                Week4MessageBubble(role = role, content = content)
            }

            items(toolCallLog) { (toolName, result) ->
                ToolCallCard(toolName = toolName, result = result)
            }

            if (isLoading) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Агент работает...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                        )
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(4.dp)) }
        }

        // ── Search mode selector ──────────────────────────────────────────────
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Поиск:",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SearchMode.entries.forEach { mode ->
                FilterChip(
                    selected = searchMode == mode,
                    onClick = { viewModel.searchMode.value = mode },
                    label = { Text(mode.label, style = MaterialTheme.typography.labelSmall) },
                    enabled = !isLoading,
                )
            }
            Text(
                text = searchMode.description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }

        // ── Input row ─────────────────────────────────────────────────────────
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedButton(
                onClick = {
                    viewModel.reset()
                    onReset?.invoke()
                },
                modifier = Modifier.height(56.dp),
                enabled = !isLoading,
            ) {
                Text("Сбросить")
            }
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedTextField(
                value = userInput,
                onValueChange = { userInput = it },
                placeholder = { Text("Введите тему для исследования...") },
                modifier = Modifier.weight(1f),
                maxLines = 4,
                enabled = !isLoading,
            )
            Spacer(modifier = Modifier.width(8.dp))
            if (isLoading) {
                Button(
                    onClick = { viewModel.stop() },
                    modifier = Modifier.height(56.dp),
                    colors = ButtonDefaults.buttonColors(
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

    // ── Logs Dialog ───────────────────────────────────────────────────────────
    if (showLogs) {
        AlertDialog(
            onDismissRequest = onDismissLogs,
            title = { Text("Логи — Неделя 4") },
            text = {
                SelectionContainer {
                    Text(
                        text = if (toolCallLog.isEmpty()) "Tool calls не было"
                        else toolCallLog.joinToString("\n\n") { (name, result) -> "[$name]\n$result" },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = onDismissLogs) { Text("Закрыть") }
            },
        )
    }
}

// ── Pipeline Panel ────────────────────────────────────────────────────────────

@Composable
private fun PipelinePanel(pipeline: PipelineState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        PipelineStepItem(
            emoji = "🔍",
            label = "Поиск",
            status = pipeline.steps[PipelineStep.SEARCH] ?: StepStatus.PENDING,
        )
        Text(
            text = "→",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PipelineStepItem(
            emoji = "📝",
            label = "Summary",
            status = pipeline.steps[PipelineStep.SUMMARIZE] ?: StepStatus.PENDING,
        )
        Text(
            text = "→",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PipelineStepItem(
            emoji = "💾",
            label = "Сохранить",
            status = pipeline.steps[PipelineStep.SAVE_TO_FILE] ?: StepStatus.PENDING,
        )
    }
}

@Composable
private fun PipelineStepItem(emoji: String, label: String, status: StepStatus) {
    val color = stepStatusColor(status)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = emoji, style = MaterialTheme.typography.titleMedium)
            }
            if (status == StepStatus.RUNNING) {
                CircularProgressIndicator(
                    modifier = Modifier.size(44.dp),
                    strokeWidth = 2.dp,
                    color = color,
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = if (status == StepStatus.RUNNING || status == StepStatus.DONE) FontWeight.Bold else FontWeight.Normal,
        )
        Text(
            text = when (status) {
                StepStatus.PENDING -> "ожидание"
                StepStatus.RUNNING -> "выполняется"
                StepStatus.DONE -> "готово"
                StepStatus.ERROR -> "ошибка"
            },
            style = MaterialTheme.typography.labelSmall,
            color = color.copy(alpha = 0.8f),
        )
    }
}

// ── Message Bubble ────────────────────────────────────────────────────────────

@Composable
private fun Week4MessageBubble(role: String, content: String) {
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

// ── Tool Call Card ────────────────────────────────────────────────────────────

@Composable
private fun ToolCallCard(toolName: String, result: String) {
    val isError = result.startsWith("ERROR:")
    val borderColor = if (isError) ErrorColor else RunningColor.copy(alpha = 0.4f)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isError) ErrorColor.copy(alpha = 0.05f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = "⚙ $toolName",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = if (isError) ErrorColor else MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            SelectionContainer {
                Text(
                    text = result,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 8,
                )
            }
        }
    }
}
