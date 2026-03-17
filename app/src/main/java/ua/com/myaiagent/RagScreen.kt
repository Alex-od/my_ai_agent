package ua.com.myaiagent

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableIntStateOf
import com.mikepenz.markdown.m3.Markdown
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RagScreen(
    viewModel: AgentViewModel,
    showLogs: Boolean = false,
    onDismissLogs: () -> Unit = {},
) {
    val uiState by viewModel.state.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val lastLog by viewModel.lastRequestLog.collectAsState()
    val mcpStatus by viewModel.mcpStatus.collectAsState()
    val mcpTools by viewModel.mcpTools.collectAsState()
    val mcpUrl by viewModel.mcpUrl.collectAsState()
    val ragIndexingState by viewModel.ragIndexingState.collectAsState()
    val ragCompareStats by viewModel.ragCompareStats.collectAsState()
    val selectedRagStrategy by viewModel.selectedRagStrategy.collectAsState()
    val lastRagResults by viewModel.lastRagResults.collectAsState()
    val ragTopK by viewModel.ragTopK.collectAsState()
    val ragEnabled by viewModel.ragEnabled.collectAsState()

    var showRagCompareDialog by remember { mutableStateOf(false) }
    var ragResultsExpanded by remember { mutableStateOf(true) }
    var showMcpMenu by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var logTab by remember { mutableIntStateOf(0) }
    var serverPath by rememberSaveable { mutableStateOf("C:\\MyClaudeAgents\\forindexation") }

    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        // Input + MCP + Send
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
            val mcpColor = when (mcpStatus) {
                McpStatus.CONNECTED    -> MaterialTheme.colorScheme.primary
                McpStatus.CONNECTING   -> MaterialTheme.colorScheme.tertiary
                McpStatus.ERROR        -> MaterialTheme.colorScheme.error
                McpStatus.DISCONNECTED -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Box {
                OutlinedButton(
                    onClick = { showMcpMenu = !showMcpMenu },
                    border = BorderStroke(1.dp, mcpColor),
                ) {
                    Text(
                        text = when (mcpStatus) {
                            McpStatus.CONNECTED    -> "MCP ●"
                            McpStatus.CONNECTING   -> "MCP …"
                            McpStatus.ERROR        -> "MCP ✕"
                            McpStatus.DISCONNECTED -> "MCP"
                        },
                        color = mcpColor,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                DropdownMenu(
                    expanded = showMcpMenu,
                    onDismissRequest = { showMcpMenu = false },
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).width(260.dp)) {
                        if (mcpStatus == McpStatus.CONNECTED) {
                            // Подключено: RAG toggle + Disconnect
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("RAG", style = MaterialTheme.typography.labelMedium)
                                Switch(
                                    checked = ragEnabled,
                                    onCheckedChange = { viewModel.setRagEnabled(it) },
                                )
                            }
                            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                            if (mcpTools.isNotEmpty()) {
                                mcpTools.forEach { tool ->
                                    Text(
                                        text = tool.name,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(vertical = 1.dp),
                                    )
                                }
                                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                            }
                            Button(
                                onClick = {
                                    viewModel.disconnectMcp()
                                    showMcpMenu = false
                                },
                                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Disconnect")
                            }
                        } else {
                            // Не подключено: URL + Connect
                            OutlinedTextField(
                                value = mcpUrl,
                                onValueChange = { viewModel.mcpUrl.value = it },
                                label = { Text("MCP URL") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Button(
                                onClick = {
                                    viewModel.connectMcp(mcpUrl)
                                    showMcpMenu = false
                                },
                                enabled = mcpStatus != McpStatus.CONNECTING,
                                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                            ) {
                                Text("Connect")
                            }
                        }
                    }
                }
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

        // RAG панели
        if (mcpStatus == McpStatus.CONNECTED && !mcpTools.any { it.name == "get_indexing_status" }) {
            Text(
                text = "Сервер не поддерживает RAG. Подключитесь к rag_server.py (порт 8083).",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
        if (mcpStatus == McpStatus.CONNECTED && mcpTools.any { it.name == "get_indexing_status" }) {
            RagIndexPanel(
                state = ragIndexingState,
                selectedStrategy = selectedRagStrategy,
                onStrategyChange = { viewModel.setRagStrategy(it) },
                topK = ragTopK,
                onTopKChange = { viewModel.setRagTopK(it) },
                serverPath = serverPath,
                onServerPathChange = { serverPath = it },
                onIndexFromPath = { viewModel.indexDocumentsFromPath(serverPath) },
                onCompareClick = {
                    viewModel.loadRagCompareStats()
                    showRagCompareDialog = true
                },
            )
            lastRagResults?.let { ragResults ->
                RagResultsPanel(
                    results = ragResults,
                    expanded = ragResultsExpanded,
                    onToggle = { ragResultsExpanded = !ragResultsExpanded },
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Сообщения
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

    // RAG compare dialog
    if (showRagCompareDialog) {
        RagCompareDialog(
            statsJson = ragCompareStats,
            onDismiss = { showRagCompareDialog = false },
        )
    }

    // Log dialog
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
                                style = MaterialTheme.typography.bodySmall,
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
}
