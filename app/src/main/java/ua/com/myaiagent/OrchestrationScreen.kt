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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.koin.androidx.compose.koinViewModel

// ── Colors ────────────────────────────────────────────────────────────────────

private val ConnectedColor  = Color(0xFF4CAF50)
private val ConnectingColor = Color(0xFF2196F3)
private val ErrorColor      = Color(0xFFF44336)
private val DisconnectedColor = Color(0xFF9E9E9E)

private fun statusColor(status: String): Color = when {
    status == "OK" || status == "CONNECTED" -> ConnectedColor
    status == "CONNECTING"                  -> ConnectingColor
    status == "DISCONNECTED"                -> DisconnectedColor
    else                                    -> ErrorColor
}

private fun statusLabel(status: String): String = when {
    status == "OK"            -> "CONNECTED"
    status.startsWith("ERROR") -> "ERROR"
    else                       -> status
}

// ── Server name mapping ───────────────────────────────────────────────────────

private val URL_LABELS = mapOf(
    URL_WEATHER    to "Weather :8080",
    URL_FILE       to "File :8081",
    URL_CALCULATOR to "Calc :8082",
)

// ── Screen ────────────────────────────────────────────────────────────────────

@Composable
fun OrchestrationScreen(
    viewModel: OrchestrationViewModel = koinViewModel(),
    modelId: String = "gpt-4.1-mini",
) {
    val serverStatuses by viewModel.serverStatuses.collectAsState()
    val messages       by viewModel.messages.collectAsState()
    val logs           by viewModel.logs.collectAsState()
    val isLoading      by viewModel.isLoading.collectAsState()
    val error          by viewModel.error.collectAsState()

    var userInput by remember { mutableStateOf("") }

    val logListState = rememberLazyListState()
    val chatListState = rememberLazyListState()

    // Auto-scroll logs
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) logListState.animateScrollToItem(logs.size - 1)
    }
    // Auto-scroll chat
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) chatListState.animateScrollToItem(messages.size - 1)
    }

    Column(modifier = Modifier.fillMaxSize()) {

        // ── 1. Server chips panel ──────────────────────────────────────────
        ServerPanel(
            statuses = serverStatuses,
            onConnectAll = { viewModel.connectAll() },
        )

        HorizontalDivider()

        // ── 2. Tool-call log ───────────────────────────────────────────────
        Text(
            text = "Tool calls",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 4.dp),
        )

        LazyColumn(
            state = logListState,
            modifier = Modifier
                .weight(0.4f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(logs) { entry ->
                LogEntryRow(entry)
            }
        }

        HorizontalDivider()

        // ── 3. Chat messages ───────────────────────────────────────────────
        LazyColumn(
            state = chatListState,
            modifier = Modifier
                .weight(0.4f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(messages) { (role, text) ->
                ChatBubble(role = role, text = text)
            }
            if (isLoading) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Агент думает...", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            error?.let { err ->
                item {
                    Text(
                        text = "Ошибка: $err",
                        color = ErrorColor,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
        }

        // ── 4. Input row ───────────────────────────────────────────────────
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = userInput,
                onValueChange = { userInput = it },
                placeholder = { Text("Введите задачу для агента...") },
                modifier = Modifier.weight(1f),
                singleLine = false,
                maxLines = 3,
            )
            Spacer(modifier = Modifier.width(8.dp))
            if (isLoading) {
                Button(onClick = { viewModel.stop() }) {
                    Icon(Icons.Default.Stop, contentDescription = "Stop")
                }
            } else {
                Button(
                    onClick = {
                        viewModel.send(userInput, modelId)
                        userInput = ""
                    },
                    enabled = userInput.isNotBlank() && viewModel.isLoading.value.not(),
                ) {
                    Icon(Icons.Default.Send, contentDescription = "Send")
                }
            }
        }

        // Quick-fill button
        if (messages.isEmpty() && !isLoading) {
            TextButton(
                onClick = {
                    userInput = "Узнай погоду в Киеве и Одессе, посчитай разницу температур и сохрани результат в файл report.txt"
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
            ) {
                Text(
                    "Demo: погода + разница + сохранить в файл",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

// ── Server chips panel ────────────────────────────────────────────────────────

@Composable
private fun ServerPanel(
    statuses: Map<String, String>,
    onConnectAll: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        listOf(URL_WEATHER, URL_FILE, URL_CALCULATOR).forEach { url ->
            val status = statuses[url] ?: "DISCONNECTED"
            ServerChip(label = URL_LABELS[url] ?: url, status = status)
        }
        Spacer(modifier = Modifier.weight(1f))
        IconButton(onClick = onConnectAll) {
            Icon(Icons.Default.Refresh, contentDescription = "Connect All")
        }
    }
}

@Composable
private fun ServerChip(label: String, status: String) {
    val color = statusColor(status)
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = color.copy(alpha = 0.12f),
        modifier = Modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = color,
            )
        }
    }
}

// ── Tool call log entry ───────────────────────────────────────────────────────

@Composable
private fun LogEntryRow(entry: OrchestratorLogEntry) {
    val isRequest = entry.direction == OrchestratorLogEntry.Direction.REQUEST
    val arrow = if (isRequest) "→" else "←"
    val arrowColor = if (isRequest) Color(0xFF2196F3) else Color(0xFF4CAF50)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = arrow,
            color = arrowColor,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(16.dp),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Column {
            Text(
                text = "${entry.toolName}  [${entry.serverName}]",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = entry.content,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ── Chat bubble ───────────────────────────────────────────────────────────────

@Composable
private fun ChatBubble(role: String, text: String) {
    val isUser = role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Card(
            modifier = Modifier
                .padding(vertical = 2.dp)
                .fillMaxWidth(0.9f),
            colors = CardDefaults.cardColors(
                containerColor = if (isUser)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(10.dp),
            )
        }
    }
}
