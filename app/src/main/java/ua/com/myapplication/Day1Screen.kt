package ua.com.myapplication

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
fun Day1Screen(viewModel: AgentViewModel = koinViewModel()) {
    var systemPrompt by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var temperature by remember { mutableStateOf("") }
    var topP by remember { mutableStateOf("") }
    val uiState by viewModel.state.collectAsState()

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
            value = temperature,
            onValueChange = { temperature = it },
            label = { Text("Temperature (0.0 - 2.0)") },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = topP,
            onValueChange = { topP = it },
            label = { Text("Top P (0.0 - 1.0)") },
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = {
                viewModel.send(
                    prompt = query,
                    systemPrompt = systemPrompt.trim().takeIf { it.isNotEmpty() },
                    temperature = temperature.trim().toDoubleOrNull(),
                    topP = topP.trim().toDoubleOrNull(),
                )
            },
            enabled = uiState !is UiState.Loading,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
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
