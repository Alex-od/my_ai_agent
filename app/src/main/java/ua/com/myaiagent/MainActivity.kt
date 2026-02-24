package ua.com.myaiagent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import ua.com.myaiagent.ui.theme.MyAiAgentTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyAiAgentTheme {
                AgentScreen()
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun AgentScreenPreview() {
    MyAiAgentTheme {
        AgentScreen()
    }
}
