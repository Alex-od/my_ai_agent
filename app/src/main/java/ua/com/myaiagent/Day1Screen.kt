package ua.com.myaiagent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import com.mikepenz.markdown.m3.Markdown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.koin.androidx.compose.koinViewModel

@Composable
fun ChatScreen(viewModel: AgentViewModel = koinViewModel()) {
    var systemPrompt by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var responseFormat by remember { mutableStateOf("") }
    val uiState by viewModel.state.collectAsState()
    val lastLog by viewModel.lastRequestLog.collectAsState()
    var showLogs by remember { mutableStateOf(false) }
    val responseText = (uiState as? UiState.Success)?.text ?: ""

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        VoiceTextField(
            value = systemPrompt,
            onValueChange = { systemPrompt = it },
            label = "system_prompt",
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(12.dp))

        VoiceTextField(
            value = query,
            onValueChange = { query = it },
            label = "user_prompt",
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = responseFormat,
            onValueChange = { responseFormat = it },
            label = { Text("Формат ответа") },
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
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
                    val prompt = buildString {
                        append(query)
                        if (responseFormat.isNotBlank()) append("\nФормат ответа: $responseFormat")
                    }
                    viewModel.send(
                        prompt = prompt,
                        systemPrompt = systemPrompt.trim().takeIf { it.isNotEmpty() },
                    )
                },
                enabled = uiState !is UiState.Loading,
                modifier = Modifier.weight(1f),
            ) {
                Text("Отправить")
            }
        }

        SpeakButton(text = responseText)

        if (showLogs) {
            AlertDialog(
                onDismissRequest = { showLogs = false },
                title = { Text("Лог запроса") },
                text = {
                    SelectionContainer {
                        Text(
                            text = lastLog?.content ?: "",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            modifier = Modifier.verticalScroll(rememberScrollState()),
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showLogs = false }) {
                        Text("Закрыть")
                    }
                },
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        when (val state = uiState) {
            is UiState.Idle -> {}
            is UiState.Loading -> CircularProgressIndicator()
            is UiState.Success -> {
                Markdown(
                    content = state.text,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            is UiState.Error -> {
                SelectionContainer {
                    Text(
                        text = state.message,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
