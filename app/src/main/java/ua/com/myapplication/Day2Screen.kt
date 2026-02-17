package ua.com.myapplication

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.koin.androidx.compose.koinViewModel

@Composable
fun Day2Screen(viewModel: AgentViewModel = koinViewModel()) {
    var query by remember { mutableStateOf("") }
    var responseFormat by remember { mutableStateOf("") }
    var lengthLimit by remember { mutableStateOf("") }
    var stopCondition by remember { mutableStateOf("") }
    val uiState by viewModel.state.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Введите запрос") },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = responseFormat,
            onValueChange = { responseFormat = it },
            label = { Text("Формат ответа") },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = lengthLimit,
            onValueChange = { lengthLimit = it },
            label = { Text("Ограничение длины ответа") },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = stopCondition,
            onValueChange = { stopCondition = it },
            label = { Text("Условие завершения") },
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = {
                val prompt = buildString {
                    append(query)
                    if (responseFormat.isNotBlank()) append("\nФормат ответа: $responseFormat")
                }
                val stop = stopCondition.trim()
                    .takeIf { it.isNotBlank() }
                    ?.split(",")
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                val maxTokens = lengthLimit.trim().toIntOrNull()
                viewModel.send(prompt, stop = stop, maxTokens = maxTokens)
            },
            enabled = uiState !is UiState.Loading,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
        ) {
            Text("Отправить")
        }

        Spacer(modifier = Modifier.height(16.dp))

        when (val state = uiState) {
            is UiState.Idle -> {}
            is UiState.Loading -> CircularProgressIndicator()
            is UiState.Success -> {
                SelectionContainer {
                    Text(
                        text = state.text,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
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
