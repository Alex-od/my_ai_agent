package ua.com.myaiagent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
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
    val ragIndexingState by viewModel.ragIndexingState.collectAsState()
    val ragCompareStats by viewModel.ragCompareStats.collectAsState()
    val selectedRagStrategy by viewModel.selectedRagStrategy.collectAsState()
    val lastRagResults by viewModel.lastRagResults.collectAsState()
    val ragTopKFinal by viewModel.ragTopKFinal.collectAsState()
    val ragTopKInitial by viewModel.ragTopKInitial.collectAsState()
    val ragEnabled by viewModel.ragEnabled.collectAsState()
    val ragRerankerEnabled by viewModel.ragRerankerEnabled.collectAsState()
    val ragRerankerModel by viewModel.ragRerankerModel.collectAsState()
    val ragRerankerThreshold by viewModel.ragRerankerThreshold.collectAsState()
    val ragLastSearchStats by viewModel.ragLastSearchStats.collectAsState()

    var showRagCompareDialog by remember { mutableStateOf(false) }
    var ragResultsExpanded by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var logTab by remember { mutableIntStateOf(0) }

    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }


    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        // Input + Send icon
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            VoiceTextField(
                value = query,
                onValueChange = { query = it },
                label = "user_prompt",
                modifier = Modifier.weight(1f),
            )
            androidx.compose.material3.IconButton(
                onClick = {
                    val prompt = query.trim()
                    if (prompt.isNotEmpty()) {
                        viewModel.send(prompt, useHistory = false)
                        query = ""
                    }
                },
                enabled = uiState !is UiState.Loading,
            ) {
                androidx.compose.material3.Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Отправить",
                )
            }
        }

        // RAG status
        Row(
            modifier = Modifier.padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val isReady = mcpStatus == McpStatus.CONNECTED && ragIndexingState is RagIndexingState.Done
            val isIndexing = mcpStatus == McpStatus.CONNECTED && ragIndexingState is RagIndexingState.Indexing
            val statusLabel = when {
                isReady    -> "● RAG готов"
                isIndexing -> "Индексация…"
                mcpStatus == McpStatus.CONNECTING -> "Подключение…"
                mcpStatus == McpStatus.ERROR -> "✕ Ошибка подключения"
                else -> ""
            }
            if (isReady) {
                Text(
                    text = statusLabel,
                    color = androidx.compose.ui.graphics.Color(0xFF2E7D32),
                    style = MaterialTheme.typography.labelSmall,
                )
            } else {
                if (mcpStatus != McpStatus.ERROR) {
                    CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
                }
                if (statusLabel.isNotEmpty()) {
                    Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (mcpStatus == McpStatus.ERROR)
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
                ragEnabled = ragEnabled,
                onRagEnabledChange = { viewModel.setRagEnabled(it) },
                selectedStrategy = selectedRagStrategy,
                onStrategyChange = { viewModel.setRagStrategy(it) },
                topKFinal = ragTopKFinal,
                onTopKFinalChange = { viewModel.setTopKFinal(it) },
                topKInitial = ragTopKInitial,
                onTopKInitialChange = { viewModel.setTopKInitial(it) },
                rerankerEnabled = ragRerankerEnabled,
                onRerankerEnabledChange = { viewModel.setRerankerEnabled(it) },
                rerankerModel = ragRerankerModel,
                onRerankerModelChange = { viewModel.setRerankerModel(it) },
                rerankerThreshold = ragRerankerThreshold,
                onRerankerThresholdChange = { viewModel.setRerankerThreshold(it) },
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
                    searchStats = ragLastSearchStats,
                    rerankerEnabled = ragRerankerEnabled,
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
