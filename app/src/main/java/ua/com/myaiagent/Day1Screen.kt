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
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.m3.Markdown
import org.koin.androidx.compose.koinViewModel
import ua.com.myaiagent.data.context.StrategyType

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(viewModel: AgentViewModel = koinViewModel()) {
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
    var query by remember { mutableStateOf("") }
    var showLogs by remember { mutableStateOf(false) }
    var logTab by remember { mutableIntStateOf(0) }
    var showBranchDialog by remember { mutableStateOf(false) }
    var showFactsExpanded by remember { mutableStateOf(false) }

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
        VoiceTextField(
            value = systemPrompt,
            onValueChange = { viewModel.systemPromptInput.value = it },
            label = "system_prompt",
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(8.dp))

        VoiceTextField(
            value = query,
            onValueChange = { query = it },
            label = "user_prompt",
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = { showLogs = true },
                enabled = lastLog != null,
                modifier = Modifier.weight(1f),
            ) {
                Text("Логи")
            }
            Button(
                onClick = {
                    val prompt = query.trim()
                    if (prompt.isNotEmpty()) {
                        viewModel.send(prompt)
                        query = ""
                    }
                },
                enabled = uiState !is UiState.Loading,
                modifier = Modifier.weight(1f),
            ) {
                Text("Отправить")
            }
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

        if (tokenStats.requestCount > 0) {
            TokenStatsBar(tokenStats, contextInfo, selectedStrategy)
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

    // Log dialog
    if (showLogs) {
        AlertDialog(
            onDismissRequest = { showLogs = false },
            title = { Text("Лог запроса") },
            text = {
                Column {
                    TabRow(selectedTabIndex = logTab) {
                        Tab(
                            selected = logTab == 0,
                            onClick = { logTab = 0 },
                            text = { Text("Лог") },
                        )
                        Tab(
                            selected = logTab == 1,
                            onClick = { logTab = 1 },
                            text = { Text("JSON") },
                        )
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
                TextButton(onClick = { showLogs = false }) {
                    Text("Закрыть")
                }
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
