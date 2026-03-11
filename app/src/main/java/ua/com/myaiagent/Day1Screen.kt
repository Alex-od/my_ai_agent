package ua.com.myaiagent

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.mikepenz.markdown.m3.Markdown
import org.koin.androidx.compose.koinViewModel
import ua.com.myaiagent.data.context.StrategyType
import ua.com.myaiagent.data.mcp.McpTool

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    viewModel: AgentViewModel = koinViewModel(),
    showLogs: Boolean = false,
    onDismissLogs: () -> Unit = {},
) {
    val systemPrompt by viewModel.systemPromptInput.collectAsState()
    val uiState by viewModel.state.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val lastLog by viewModel.lastRequestLog.collectAsState()
    val tokenStats by viewModel.tokenStats.collectAsState()
    val contextInfo by viewModel.contextInfo.collectAsState()
    val selectedStrategy by viewModel.selectedStrategy.collectAsState()
    val facts by viewModel.facts.collectAsState()
    val branches by viewModel.branches.collectAsState()
    val activeBranchName by viewModel.activeBranchName.collectAsState()
    val mcpStatus by viewModel.mcpStatus.collectAsState()
    val mcpTools by viewModel.mcpTools.collectAsState()
    val mcpServerName by viewModel.mcpServerName.collectAsState()
    val mcpUrl by viewModel.mcpUrl.collectAsState()
    val schedulerTasks by viewModel.schedulerTasks.collectAsState()
    val schedulerResults by viewModel.schedulerResults.collectAsState()
    val selectedSchedulerTaskId by viewModel.selectedSchedulerTaskId.collectAsState()
    var showSchedulerPanel by remember { mutableStateOf(false) }
    var showCreateTaskDialog by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var logTab by remember { mutableIntStateOf(0) }
    var showBranchDialog by remember { mutableStateOf(false) }
    var showFactsExpanded by remember { mutableStateOf(false) }
    var showMcpPanel by remember { mutableStateOf(false) }
    var showSystemPrompt by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { showSystemPrompt = !showSystemPrompt }) {
                Text(if (showSystemPrompt) "▲ system_prompt" else "▼ system_prompt")
            }
        }
        AnimatedVisibility(visible = showSystemPrompt) {
            VoiceTextField(
                value = systemPrompt,
                onValueChange = { viewModel.systemPromptInput.value = it },
                label = "system_prompt",
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        VoiceTextField(
            value = query,
            onValueChange = { query = it },
            label = "user_prompt",
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = {
                val prompt = query.trim()
                if (prompt.isNotEmpty()) {
                    viewModel.send(prompt)
                    query = ""
                }
            },
            enabled = uiState !is UiState.Loading,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) {
            Text("Отправить")
        }

        val lastAssistantText = messages.lastOrNull { it.role == "assistant" }?.content ?: ""
        SpeakButton(text = lastAssistantText)

        // Branch controls (visible when Branching strategy is selected)
        if (selectedStrategy == StrategyType.BRANCHING) {
            BranchControls(
                branches = branches,
                activeBranchName = activeBranchName,
                onCreateBranch = { showBranchDialog = true },
                onSwitchBranch = { viewModel.switchBranch(it) },
            )
        }

        // Facts panel (visible when StickyFacts strategy is selected and has facts)
        if (selectedStrategy == StrategyType.STICKY_FACTS && facts.isNotEmpty()) {
            FactsPanel(
                facts = facts.map { it.key to it.value },
                expanded = showFactsExpanded,
                onToggle = { showFactsExpanded = !showFactsExpanded },
            )
        }

        McpPanel(
            url = mcpUrl,
            onUrlChange = { viewModel.mcpUrl.value = it },
            status = mcpStatus,
            serverName = mcpServerName,
            tools = mcpTools,
            expanded = showMcpPanel,
            onToggle = { showMcpPanel = !showMcpPanel },
            onConnect = { viewModel.connectMcp(mcpUrl) },
        )

        if (mcpStatus == McpStatus.CONNECTED) {
            SchedulerPanel(
                tasks = schedulerTasks,
                results = schedulerResults,
                selectedTaskId = selectedSchedulerTaskId,
                expanded = showSchedulerPanel,
                onToggle = { showSchedulerPanel = !showSchedulerPanel },
                onSelectTask = { viewModel.selectSchedulerTask(it) },
                onStopTask = { viewModel.stopSchedulerTask(it) },
                onDeleteTask = { viewModel.deleteSchedulerTask(it) },
                onCreateTask = { showCreateTaskDialog = true },
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(messages) { message ->
                MessageBubble(message)
            }
            if (uiState is UiState.Loading) {
                item {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                        CircularProgressIndicator(modifier = Modifier.padding(8.dp))
                    }
                }
            }
            if (uiState is UiState.Error) {
                item {
                    SelectionContainer {
                        Text(
                            text = (uiState as UiState.Error).message,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                        )
                    }
                }
            }
        }
    }

    // Log dialog (opened from toolbar)
    if (showLogs) {
        AlertDialog(
            onDismissRequest = onDismissLogs,
            title = { Text("Лог запроса") },
            text = {
                Column {
                    TabRow(selectedTabIndex = logTab) {
                        Tab(selected = logTab == 0, onClick = { logTab = 0 }, text = { Text("Лог") })
                        Tab(selected = logTab == 1, onClick = { logTab = 1 }, text = { Text("JSON") })
                    }
                    SelectionContainer {
                        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                            Text(
                                text = when (logTab) {
                                    0 -> lastLog?.content ?: ""
                                    else -> lastLog?.rawJson ?: ""
                                },
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismissLogs) { Text("Закрыть") }
            },
        )
    }

    // Create task dialog
    if (showCreateTaskDialog) {
        CreateTaskDialog(
            onDismiss = { showCreateTaskDialog = false },
            onCreate = { taskId, description, cronExpr, toolName, toolArgs ->
                viewModel.scheduleTask(taskId, description, cronExpr, toolName, toolArgs)
                showCreateTaskDialog = false
            },
        )
    }

    // Branch creation dialog
    if (showBranchDialog) {
        var branchName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showBranchDialog = false },
            title = { Text("Создать ветку") },
            text = {
                OutlinedTextField(
                    value = branchName,
                    onValueChange = { branchName = it },
                    label = { Text("Название ветки") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (branchName.isNotBlank()) {
                            viewModel.createBranch(branchName.trim())
                            showBranchDialog = false
                        }
                    },
                ) {
                    Text("Создать")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBranchDialog = false }) {
                    Text("Отмена")
                }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BranchControls(
    branches: List<ua.com.myaiagent.data.local.BranchEntity>,
    activeBranchName: String?,
    onCreateBranch: () -> Unit,
    onSwitchBranch: (Long?) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f),
                shape = RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (activeBranchName != null) "Ветка: $activeBranchName" else "Основная ветка",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            AssistChip(
                onClick = onCreateBranch,
                label = { Text("+ Ветка", style = MaterialTheme.typography.labelSmall) },
            )
        }
        if (branches.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FilterChip(
                    selected = activeBranchName == null,
                    onClick = { onSwitchBranch(null) },
                    label = { Text("Основная", style = MaterialTheme.typography.labelSmall) },
                )
                branches.forEach { branch ->
                    FilterChip(
                        selected = branch.name == activeBranchName,
                        onClick = { onSwitchBranch(branch.id) },
                        label = { Text(branch.name, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }
        }
    }
}

@Composable
private fun FactsPanel(
    facts: List<Pair<String, String>>,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(
                color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f),
                shape = RoundedCornerShape(8.dp),
            )
            .clickable { onToggle() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = "Факты (${facts.size})  ${if (expanded) "▲" else "▼"}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.padding(top = 4.dp)) {
                facts.forEach { (key, value) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 1.dp),
                    ) {
                        Text(
                            text = key,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            modifier = Modifier.widthIn(min = 80.dp),
                        )
                        Text(
                            text = ": $value",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun McpPanel(
    url: String,
    onUrlChange: (String) -> Unit,
    status: McpStatus,
    serverName: String,
    tools: List<McpTool>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onConnect: () -> Unit,
) {
    val statusText = when (status) {
        McpStatus.DISCONNECTED -> "не подключён"
        McpStatus.CONNECTING   -> "подключение…"
        McpStatus.CONNECTED    -> "подключён: $serverName"
        McpStatus.ERROR        -> "ошибка"
    }
    val statusColor = when (status) {
        McpStatus.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
        McpStatus.CONNECTING   -> MaterialTheme.colorScheme.tertiary
        McpStatus.CONNECTED    -> MaterialTheme.colorScheme.primary
        McpStatus.ERROR        -> MaterialTheme.colorScheme.error
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(8.dp),
            )
            .clickable { onToggle() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "MCP  ${if (expanded) "▲" else "▼"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelSmall,
                color = statusColor,
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.padding(top = 6.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = onUrlChange,
                    label = { Text("MCP URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = onConnect,
                    enabled = status != McpStatus.CONNECTING,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                ) {
                    if (status == McpStatus.CONNECTING) {
                        CircularProgressIndicator(modifier = Modifier.height(18.dp).width(18.dp).padding(end = 8.dp))
                    }
                    Text("Connect")
                }
                if (tools.isNotEmpty()) {
                    Column(modifier = Modifier.padding(top = 6.dp)) {
                        tools.forEach { tool ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                            ) {
                                Text(
                                    text = tool.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    text = if (tool.description.isNotEmpty()) " — ${tool.description}" else "",
                                    style = MaterialTheme.typography.bodySmall,
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

@Composable
private fun SchedulerPanel(
    tasks: List<ScheduledTask>,
    results: List<TaskResult>,
    selectedTaskId: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
    onSelectTask: (String) -> Unit,
    onStopTask: (String) -> Unit,
    onDeleteTask: (String) -> Unit,
    onCreateTask: () -> Unit,
) {
    val dateFmt = remember { SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f),
                shape = RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Планировщик (${tasks.size})  ${if (expanded) "▲" else "▼"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier
                    .weight(1f)
                    .clickable { onToggle() },
            )
            AssistChip(
                onClick = onCreateTask,
                label = { Text("+ Задача", style = MaterialTheme.typography.labelSmall) },
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.padding(top = 4.dp)) {
                if (tasks.isEmpty()) {
                    Text(
                        text = "Нет задач. Спросите агента или создайте через '+'",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                } else {
                    tasks.forEach { task ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                                .clickable { onSelectTask(task.taskId) },
                            colors = CardDefaults.cardColors(
                                containerColor = if (task.taskId == selectedTaskId)
                                    MaterialTheme.colorScheme.secondaryContainer
                                else
                                    MaterialTheme.colorScheme.surface,
                            ),
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Text(
                                            text = task.taskId,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                        SuggestionChip(
                                            onClick = {},
                                            label = {
                                                Text(
                                                    text = task.status,
                                                    style = MaterialTheme.typography.labelSmall,
                                                )
                                            },
                                            colors = SuggestionChipDefaults.suggestionChipColors(
                                                containerColor = if (task.status == "running")
                                                    Color(0xFF2E7D32).copy(alpha = 0.2f)
                                                else
                                                    MaterialTheme.colorScheme.surfaceVariant,
                                                labelColor = if (task.status == "running")
                                                    Color(0xFF2E7D32)
                                                else
                                                    MaterialTheme.colorScheme.onSurfaceVariant,
                                            ),
                                            modifier = Modifier.height(22.dp),
                                        )
                                    }
                                    if (task.status == "running") {
                                        TextButton(onClick = { onStopTask(task.taskId) }) {
                                            Text("Стоп", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                    TextButton(onClick = { onDeleteTask(task.taskId) }) {
                                        Text(
                                            "Удалить",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                                if (task.description.isNotBlank()) {
                                    Text(
                                        text = task.description,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text(
                                    text = "${task.cronExpression}  •  ${task.toolName}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                val lastRunText = task.lastRunAt?.let { dateFmt.format(Date(it)) } ?: "—"
                                Text(
                                    text = "последний: $lastRunText  •  запусков: ${task.runCount}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                AnimatedVisibility(visible = selectedTaskId != null) {
                    Column(modifier = Modifier.padding(top = 6.dp)) {
                        Text(
                            text = "Результаты: $selectedTaskId",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                        if (results.isEmpty()) {
                            Text(
                                text = "Результатов пока нет",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                                items(results) { r ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 2.dp),
                                        verticalAlignment = Alignment.Top,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Text(
                                            text = dateFmt.format(Date(r.runAt)),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Text(
                                            text = if (r.success) "✓" else "✗",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (r.success) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                                        )
                                        Text(
                                            text = r.data.take(150),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurface,
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
}

private val FREQUENCY_OPTIONS = listOf(
    "каждую минуту" to "*/1 * * * *",
    "каждые 5 мин"  to "*/5 * * * *",
    "каждый час"    to "0 * * * *",
    "каждый день"   to "0 9 * * *",
)

@Composable
private fun CreateTaskDialog(
    onDismiss: () -> Unit,
    onCreate: (taskId: String, description: String, cronExpr: String, toolName: String, toolArgs: String) -> Unit,
) {
    var selectedTool by remember { mutableStateOf("get_current_weather") }
    var city by remember { mutableStateOf("") }
    var selectedFreq by remember { mutableStateOf(FREQUENCY_OPTIONS[1]) }

    // taskId генерируется автоматически
    val autoTaskId = remember(city, selectedTool) {
        val prefix = if (selectedTool == "get_forecast") "forecast" else "weather"
        val citySlug = city.trim().lowercase().replace(" ", "_").ifEmpty { "city" }
        "${prefix}_${citySlug}"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новая задача") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = city,
                    onValueChange = { city = it },
                    label = { Text("Город") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Инструмент:", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = selectedTool == "get_current_weather",
                        onClick = { selectedTool = "get_current_weather" },
                        label = { Text("Погода сейчас") },
                    )
                    FilterChip(
                        selected = selectedTool == "get_forecast",
                        onClick = { selectedTool = "get_forecast" },
                        label = { Text("Прогноз") },
                    )
                }
                Text("Как часто:", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FREQUENCY_OPTIONS.forEach { option ->
                        FilterChip(
                            selected = selectedFreq == option,
                            onClick = { selectedFreq = option },
                            label = { Text(option.first, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
                Text(
                    text = "ID: $autoTaskId",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = city.isNotBlank(),
                onClick = {
                    val toolArgs = if (selectedTool == "get_forecast") {
                        """{"city":"${city.trim()}","days":3}"""
                    } else {
                        """{"city":"${city.trim()}"}"""
                    }
                    onCreate(autoTaskId, "", selectedFreq.second, selectedTool, toolArgs)
                },
            ) { Text("Создать") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}

@Composable
private fun TokenStatsBar(stats: TokenStats, contextInfo: String, strategy: StrategyType) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        if (stats.lastTruncated) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp)
                    .background(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(4.dp),
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "Ответ обрезан: превышен лимит токенов",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Последний запрос",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "in: ${stats.lastInput}  out: ${stats.lastOutput}  total: ${stats.lastTotal}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 3.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Диалог (${stats.requestCount} запр.)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "in: ${stats.totalInput}  out: ${stats.totalOutput}  total: ${stats.totalAll}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        // Context strategy info
        if (contextInfo.isNotBlank()) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 3.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "${strategy.label}: $contextInfo",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            if (stats.strategyCallCount > 0) {
                Text(
                    text = "API вызовов: ${stats.strategyCallCount}  in: ${stats.strategyInput}  out: ${stats.strategyOutput}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(message: UiMessage) {
    val isUser = message.role == "user"
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .background(
                    color = if (isUser)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (isUser) {
                Text(text = message.content)
            } else {
                Markdown(
                    content = message.content,
                    modifier = Modifier.widthIn(max = 300.dp),
                )
            }
        }
    }
}
