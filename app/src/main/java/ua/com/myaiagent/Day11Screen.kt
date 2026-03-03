package ua.com.myaiagent

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.koin.androidx.compose.koinViewModel
import ua.com.myaiagent.data.memory.ExpertiseLevel
import ua.com.myaiagent.data.memory.MemoryMessage
import ua.com.myaiagent.data.memory.MemorySnapshot
import ua.com.myaiagent.data.memory.ProfilePresets
import ua.com.myaiagent.data.memory.ResponseStyle
import ua.com.myaiagent.data.memory.UserProfile

// ── Цвета для слоёв памяти ────────────────────────────────────────────────────

private val ShortTermColor = Color(0xFF4CAF50)   // 🟢 зелёный
private val WorkingColor   = Color(0xFFFFC107)   // 🟡 жёлтый
private val LongTermColor  = Color(0xFF2196F3)   // 🔵 синий

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Day11Screen(viewModel: Day11ViewModel = koinViewModel()) {
    val chatMessages by viewModel.chatMessages.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val snapshot by viewModel.memorySnapshot.collectAsState()
    val routerLog by viewModel.routerLog.collectAsState()
    val lastSystemPrompt by viewModel.lastSystemPrompt.collectAsState()
    val lastRequestLog by viewModel.lastRequestLog.collectAsState()
    val activeProfile by viewModel.profileFlow.collectAsState()

    var userInput by remember { mutableStateOf("") }
    var memoryExpanded by remember { mutableStateOf(true) }
    var shortTermExpanded by remember { mutableStateOf(false) }
    var workingExpanded by remember { mutableStateOf(true) }
    var longTermExpanded by remember { mutableStateOf(true) }
    var showTaskDialog by remember { mutableStateOf(false) }
    var taskInput by remember { mutableStateOf("") }
    var showPromptDialog by remember { mutableStateOf(false) }
    var showLogsDialog by remember { mutableStateOf(false) }
    var logTab by remember { mutableIntStateOf(0) }
    var showCustomEditor by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()

    // Автоскролл к последнему сообщению
    LaunchedEffect(chatMessages.size) {
        if (chatMessages.isNotEmpty()) {
            listState.animateScrollToItem(chatMessages.size - 1)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {

        // ── Профиль пользователя ──────────────────────────────────────────────
        ProfileSelectorBar(
            activeProfile = activeProfile,
            showCustomEditor = showCustomEditor,
            onSelectPreset = { preset ->
                viewModel.setProfile(preset)
                showCustomEditor = false
            },
            onToggleCustom = { showCustomEditor = !showCustomEditor },
            onSaveCustom = { profile ->
                viewModel.setProfile(profile)
                showCustomEditor = false
            },
        )

        // ── Панель состояния памяти ───────────────────────────────────────────
        Surface(shadowElevation = 2.dp) {
            Column(modifier = Modifier.fillMaxWidth()) {

                // Заголовок панели с кнопкой сворачивания
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "🧠 Слои памяти",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    if (lastRequestLog != null) {
                        TextButton(
                            onClick = { showLogsDialog = true; logTab = 0 },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Text("Логи", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    IconButton(
                        onClick = { memoryExpanded = !memoryExpanded },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = if (memoryExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }

                AnimatedVisibility(visible = memoryExpanded) {
                    Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(androidx.compose.foundation.layout.IntrinsicSize.Max),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            // 🟢 Short-Term
                            MemoryLayerCard(
                                color = ShortTermColor,
                                label = "Краткосрочная",
                                summary = buildString {
                                    append("Сообщений: ${snapshot.shortTermMessages.size}")
                                    if (snapshot.shortTermEvictedCount > 0) {
                                        append("\nВытеснено: ${snapshot.shortTermEvictedCount}")
                                    }
                                    if (snapshot.shortTermSummary.isNotBlank()) {
                                        append("\nSummary: ✓")
                                    }
                                },
                                isExpanded = shortTermExpanded,
                                onToggle = { shortTermExpanded = !shortTermExpanded },
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                            )

                            // 🟡 Working
                            MemoryLayerCard(
                                color = WorkingColor,
                                label = "Рабочая",
                                summary = buildString {
                                    if (snapshot.workingTask != null) {
                                        append(snapshot.workingTask!!.take(20))
                                        if (snapshot.workingTaskData.isNotEmpty()) {
                                            append("\n${snapshot.workingTaskData.size} параметров")
                                        }
                                    } else {
                                        append("Нет задачи")
                                    }
                                },
                                isExpanded = workingExpanded,
                                onToggle = { workingExpanded = !workingExpanded },
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                            )

                            // 🔵 Long-Term
                            MemoryLayerCard(
                                color = LongTermColor,
                                label = "Долговременная",
                                summary = buildString {
                                    val total = snapshot.longTermProfile.size +
                                        snapshot.longTermPreferences.size +
                                        snapshot.longTermKnowledge.size
                                    if (total > 0) {
                                        append("фактов: $total")
                                        if (snapshot.longTermProfile.isNotEmpty()) {
                                            append("\n👤 ${snapshot.longTermProfile.size}")
                                        }
                                        if (snapshot.longTermPreferences.isNotEmpty()) {
                                            append("  ⭐ ${snapshot.longTermPreferences.size}")
                                        }
                                    } else {
                                        append("Пусто")
                                    }
                                },
                                isExpanded = longTermExpanded,
                                onToggle = { longTermExpanded = !longTermExpanded },
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                            )
                        }

                        // Детали выбранных слоёв
                        if (shortTermExpanded) {
                            ShortTermDetails(snapshot)
                        }
                        if (workingExpanded && snapshot.workingTask != null) {
                            WorkingMemoryDetails(snapshot, onSetTask = { showTaskDialog = true })
                        }
                        if (longTermExpanded && (snapshot.longTermProfile.isNotEmpty() ||
                                snapshot.longTermPreferences.isNotEmpty() ||
                                snapshot.longTermKnowledge.isNotEmpty())) {
                            LongTermDetails(snapshot, onClear = { viewModel.clearLongTerm() })
                        }

                        // Кнопки управления
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            OutlinedButton(
                                onClick = { showTaskDialog = true },
                                modifier = Modifier.weight(1f),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                            ) {
                                Text("+ Задача", style = MaterialTheme.typography.labelSmall)
                            }
                            OutlinedButton(
                                onClick = { viewModel.clearShortTerm() },
                                modifier = Modifier.weight(1f),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                            ) {
                                Text("Сброс краткосрочной", style = MaterialTheme.typography.labelSmall)
                            }
                            OutlinedButton(
                                onClick = { viewModel.clearAll() },
                                modifier = Modifier.weight(1f),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error,
                                ),
                            ) {
                                Text("Очистить всё", style = MaterialTheme.typography.labelSmall)
                            }
                        }

                        // Лог маршрутизатора
                        if (routerLog.isNotBlank()) {
                            HorizontalDivider()
                            Text(
                                text = "🔀 Последняя маршрутизация:",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp, start = 4.dp),
                            )
                            SelectionContainer {
                                Text(
                                    text = routerLog,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── Список сообщений ──────────────────────────────────────────────────
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Spacer(modifier = Modifier.height(4.dp)) }

            items(chatMessages) { message ->
                ChatMessageBubble(message)
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
                            text = "Думаю...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (error != null) {
                item {
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

            item { Spacer(modifier = Modifier.height(4.dp)) }
        }

        // ── Поле ввода ────────────────────────────────────────────────────────
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = userInput,
                onValueChange = { userInput = it },
                placeholder = { Text("Напишите что-нибудь...") },
                modifier = Modifier.weight(1f),
                maxLines = 4,
                enabled = !isLoading,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    val text = userInput.trim()
                    if (text.isNotBlank()) {
                        userInput = ""
                        viewModel.send(text)
                    }
                },
                enabled = !isLoading && userInput.isNotBlank(),
                modifier = Modifier.height(56.dp),
            ) {
                Icon(Icons.Default.Send, contentDescription = "Отправить")
            }
        }
    }

    // ── Диалог установки задачи ───────────────────────────────────────────────
    if (showTaskDialog) {
        AlertDialog(
            onDismissRequest = { showTaskDialog = false },
            title = { Text("Установить задачу") },
            text = {
                OutlinedTextField(
                    value = taskInput,
                    onValueChange = { taskInput = it },
                    label = { Text("Название задачи") },
                    placeholder = { Text("Habit Tracker App") },
                    singleLine = true,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (taskInput.isNotBlank()) {
                            viewModel.setTask(taskInput.trim())
                            taskInput = ""
                            showTaskDialog = false
                        }
                    }
                ) { Text("Установить") }
            },
            dismissButton = {
                TextButton(onClick = { showTaskDialog = false }) { Text("Отмена") }
            },
        )
    }

    // ── Диалог логов (3 вкладки: Лог | Промпт | Маршрут) ────────────────────
    if (showLogsDialog) {
        AlertDialog(
            onDismissRequest = { showLogsDialog = false },
            title = { Text("Логи") },
            text = {
                Column {
                    PrimaryTabRow(selectedTabIndex = logTab) {
                        Tab(selected = logTab == 0, onClick = { logTab = 0 }, text = { Text("Лог") })
                        Tab(selected = logTab == 1, onClick = { logTab = 1 }, text = { Text("JSON") })
                        Tab(selected = logTab == 2, onClick = { logTab = 2 }, text = { Text("Промпт") })
                        Tab(selected = logTab == 3, onClick = { logTab = 3 }, text = { Text("Маршрут") })
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    SelectionContainer {
                        Text(
                            text = when (logTab) {
                                0 -> lastRequestLog?.content ?: ""
                                1 -> lastRequestLog?.rawJson ?: ""
                                2 -> lastSystemPrompt.ifBlank { "Промпт не сформирован" }
                                else -> routerLog.ifBlank { "Маршрутизация не выполнялась" }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.verticalScroll(rememberScrollState()),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLogsDialog = false }) { Text("Закрыть") }
            },
        )
    }
}

// ── Профиль ───────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileSelectorBar(
    activeProfile: UserProfile,
    showCustomEditor: Boolean,
    onSelectPreset: (UserProfile) -> Unit,
    onToggleCustom: () -> Unit,
    onSaveCustom: (UserProfile) -> Unit,
) {
    val ProfileColor = Color(0xFF9C27B0)  // фиолетовый для профиля

    Surface(shadowElevation = 1.dp, color = ProfileColor.copy(alpha = 0.06f)) {
        Column(modifier = Modifier.fillMaxWidth()) {

            // Строка с иконкой + чипы
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = ProfileColor,
                )

                // Пресеты
                ProfilePresets.all.forEach { preset ->
                    FilterChip(
                        selected = activeProfile.id == preset.id,
                        onClick = { onSelectPreset(preset) },
                        label = { Text(preset.name, style = MaterialTheme.typography.labelSmall) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = ProfileColor.copy(alpha = 0.18f),
                            selectedLabelColor = ProfileColor,
                        ),
                    )
                }

                // Custom-чип
                FilterChip(
                    selected = activeProfile.id == "custom" || showCustomEditor,
                    onClick = onToggleCustom,
                    label = { Text("Custom", style = MaterialTheme.typography.labelSmall) },
                    trailingIcon = {
                        Icon(
                            imageVector = if (showCustomEditor) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = ProfileColor.copy(alpha = 0.18f),
                        selectedLabelColor = ProfileColor,
                    ),
                )
            }

            // Активный профиль — краткая инфо-строка
            if (!showCustomEditor) {
                Text(
                    text = "${activeProfile.role.ifBlank { activeProfile.name }} · ${activeProfile.responseStyle.label} · ${activeProfile.expertiseLevel.label}",
                    style = MaterialTheme.typography.labelSmall,
                    color = ProfileColor.copy(alpha = 0.8f),
                    modifier = Modifier.padding(start = 32.dp, bottom = 4.dp),
                )
            }

            // Редактор Custom-профиля
            AnimatedVisibility(visible = showCustomEditor) {
                CustomProfileEditor(
                    initial = if (activeProfile.id == "custom") activeProfile else UserProfile(
                        id = "custom",
                        name = "Custom",
                        role = "",
                        language = "Russian",
                    ),
                    onSave = onSaveCustom,
                )
            }
        }
    }
}

@Composable
private fun CustomProfileEditor(
    initial: UserProfile,
    onSave: (UserProfile) -> Unit,
) {
    var name by remember(initial.id) { mutableStateOf(initial.name) }
    var role by remember(initial.id) { mutableStateOf(initial.role) }
    var language by remember(initial.id) { mutableStateOf(initial.language) }
    var responseStyle by remember(initial.id) { mutableStateOf(initial.responseStyle) }
    var expertiseLevel by remember(initial.id) { mutableStateOf(initial.expertiseLevel) }
    var useMarkdown by remember(initial.id) { mutableStateOf(initial.useMarkdown) }
    var useEmoji by remember(initial.id) { mutableStateOf(initial.useEmoji) }
    var restrictionsText by remember(initial.id) { mutableStateOf(initial.restrictions.joinToString("\n")) }
    var customInstructions by remember(initial.id) { mutableStateOf(initial.customInstructions) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Настройка профиля",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Имя") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    textStyle = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = role,
                    onValueChange = { role = it },
                    label = { Text("Роль") },
                    singleLine = true,
                    modifier = Modifier.weight(2f),
                    textStyle = MaterialTheme.typography.bodySmall,
                )
            }

            OutlinedTextField(
                value = language,
                onValueChange = { language = it },
                label = { Text("Язык ответов") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall,
            )

            // Стиль ответов
            Text("Стиль:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ResponseStyle.entries.forEach { style ->
                    FilterChip(
                        selected = responseStyle == style,
                        onClick = { responseStyle = style },
                        label = { Text(style.label, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }

            // Уровень экспертизы
            Text("Уровень:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ExpertiseLevel.entries.forEach { level ->
                    FilterChip(
                        selected = expertiseLevel == level,
                        onClick = { expertiseLevel = level },
                        label = { Text(level.label, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }

            // Переключатели
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Markdown", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                Switch(checked = useMarkdown, onCheckedChange = { useMarkdown = it })
                Spacer(modifier = Modifier.width(16.dp))
                Text("Эмодзи", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                Switch(checked = useEmoji, onCheckedChange = { useEmoji = it })
            }

            // Ограничения (по одному на строку)
            OutlinedTextField(
                value = restrictionsText,
                onValueChange = { restrictionsText = it },
                label = { Text("Ограничения (по одному на строку)") },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall,
                placeholder = { Text("Use Kotlin only\nNo emoji", style = MaterialTheme.typography.bodySmall) },
            )

            // Доп. инструкции
            OutlinedTextField(
                value = customInstructions,
                onValueChange = { customInstructions = it },
                label = { Text("Дополнительные инструкции") },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall,
                placeholder = { Text("Always start with a summary...", style = MaterialTheme.typography.bodySmall) },
            )

            Button(
                onClick = {
                    val restrictions = restrictionsText.lines()
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                    onSave(
                        UserProfile(
                            id = "custom",
                            name = name.ifBlank { "Custom" },
                            role = role,
                            language = language.ifBlank { "Russian" },
                            responseStyle = responseStyle,
                            expertiseLevel = expertiseLevel,
                            useMarkdown = useMarkdown,
                            useEmoji = useEmoji,
                            restrictions = restrictions,
                            customInstructions = customInstructions,
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Сохранить профиль")
            }
        }
    }
}

// ── Компоненты ────────────────────────────────────────────────────────────────

@Composable
private fun MemoryLayerCard(
    color: Color,
    label: String,
    summary: String,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onToggle,
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = color.copy(alpha = 0.08f),
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.4f)),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(color),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = summary,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 16.sp,
            )
        }
    }
}

@Composable
private fun ShortTermDetails(snapshot: MemorySnapshot) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = ShortTermColor.copy(alpha = 0.06f)),
        border = androidx.compose.foundation.BorderStroke(1.dp, ShortTermColor.copy(alpha = 0.3f)),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                text = "🟢 Краткосрочная память",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = ShortTermColor,
            )
            Spacer(modifier = Modifier.height(4.dp))

            if (snapshot.shortTermSummary.isNotBlank()) {
                Text("Summary (вытесненные):", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    text = snapshot.shortTermSummary.take(150) + if (snapshot.shortTermSummary.length > 150) "…" else "",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(start = 8.dp),
                )
                Spacer(modifier = Modifier.height(4.dp))
            }

            Text("Текущее окно (${snapshot.shortTermMessages.size} сообщений):", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            snapshot.shortTermMessages.takeLast(4).forEach { msg ->
                Row(modifier = Modifier.padding(start = 8.dp, top = 2.dp)) {
                    Text(
                        text = if (msg.role == "user") "👤" else "🤖",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = msg.content.take(60) + if (msg.content.length > 60) "…" else "",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun WorkingMemoryDetails(snapshot: MemorySnapshot, onSetTask: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = WorkingColor.copy(alpha = 0.06f)),
        border = androidx.compose.foundation.BorderStroke(1.dp, WorkingColor.copy(alpha = 0.3f)),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "🟡 Рабочая память",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = WorkingColor,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(modifier = Modifier.height(4.dp))

            MemoryRow("Задача", snapshot.workingTask ?: "—")

            snapshot.workingTaskData.forEach { (k, v) ->
                MemoryRow(k, v)
            }

            if (snapshot.workingDecisions.isNotEmpty()) {
                Text(
                    text = "Решения:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                snapshot.workingDecisions.forEach { d ->
                    Text(
                        text = "• ${d.topic}: ${d.choice.take(50)}",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }

            if (snapshot.workingOpenQuestions.isNotEmpty()) {
                Text(
                    text = "Открытые вопросы:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                snapshot.workingOpenQuestions.forEach { q ->
                    Text(
                        text = "• $q",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun LongTermDetails(snapshot: MemorySnapshot, onClear: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = LongTermColor.copy(alpha = 0.06f)),
        border = androidx.compose.foundation.BorderStroke(1.dp, LongTermColor.copy(alpha = 0.3f)),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "🔵 Долговременная память",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = LongTermColor,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onClear, modifier = Modifier.size(24.dp)) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Очистить",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))

            if (snapshot.longTermProfile.isNotEmpty()) {
                Text("👤 Профиль:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                snapshot.longTermProfile.forEach { (k, v) -> MemoryRow(k, v) }
            }

            if (snapshot.longTermPreferences.isNotEmpty()) {
                Text(
                    "⭐ Предпочтения:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                snapshot.longTermPreferences.forEach { (k, v) -> MemoryRow(k, v) }
            }

            if (snapshot.longTermKnowledge.isNotEmpty()) {
                Text(
                    "💡 Знания:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                snapshot.longTermKnowledge.forEach { (k, v) -> MemoryRow(k, v) }
            }

            Text(
                text = "💾 Сохранено в файл (long_term_memory.json)",
                style = MaterialTheme.typography.labelSmall,
                color = LongTermColor.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun MemoryRow(key: String, value: String) {
    Row(modifier = Modifier.padding(start = 8.dp, top = 2.dp)) {
        Text(
            text = key,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(90.dp),
        )
        Text(
            text = ": $value",
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun ChatMessageBubble(message: MemoryMessage) {
    val isUser = message.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            modifier = Modifier
                .clip(
                    RoundedCornerShape(
                        topStart = 12.dp,
                        topEnd = 12.dp,
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
            SelectionContainer {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
