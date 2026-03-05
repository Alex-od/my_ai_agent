package ua.com.myaiagent

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ua.com.myaiagent.data.invariants.Invariant
import ua.com.myaiagent.data.invariants.InvariantCategory
import ua.com.myaiagent.data.invariants.InvariantSeverity
import ua.com.myaiagent.data.invariants.InvariantStore

private val HardColor = Color(0xFFE53935)
private val SoftColor = Color(0xFFFFA000)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InvariantManagerSheet(
    invariantStore: InvariantStore,
    onDismiss: () -> Unit,
) {
    val invariants by invariantStore.invariantsFlow.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<Invariant?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Lock,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Инварианты · ${invariants.count { it.enabled }} активных",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(invariants, key = { it.id }) { inv ->
                InvariantRow(
                    invariant = inv,
                    onToggle = { enabled -> invariantStore.toggle(inv.id, enabled) },
                    onEdit = { editTarget = inv },
                    onDelete = { invariantStore.remove(inv.id) },
                )
                HorizontalDivider()
            }
            item {
                Row(modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)) {
                    AssistChip(
                        onClick = { showAddDialog = true },
                        label = { Text("+ Добавить", style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        InvariantEditDialog(
            initial = null,
            onDismiss = { showAddDialog = false },
            onConfirm = { category, title, rule, severity ->
                val count = invariantStore.invariants.count { it.category == category } + 1
                invariantStore.add(
                    Invariant(
                        id = if (count == 1) category.name else "${category.name}-$count",
                        category = category,
                        title = title,
                        rule = rule,
                        severity = severity,
                    )
                )
                showAddDialog = false
            },
        )
    }

    editTarget?.let { target ->
        InvariantEditDialog(
            initial = target,
            onDismiss = { editTarget = null },
            onConfirm = { category, title, rule, severity ->
                invariantStore.update(target.copy(category = category, title = title, rule = rule, severity = severity))
                editTarget = null
            },
        )
    }
}

@Composable
private fun InvariantRow(
    invariant: Invariant,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val dotColor = if (invariant.severity == InvariantSeverity.HARD) HardColor else SoftColor

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (invariant.lockedByTask) {
            Icon(
                Icons.Default.Lock,
                contentDescription = null,
                modifier = Modifier.size(10.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        } else {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "[${invariant.id}] ${invariant.title}",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = invariant.rule,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = invariant.enabled,
            onCheckedChange = { if (!invariant.lockedByTask) onToggle(it) },
            enabled = !invariant.lockedByTask,
            modifier = Modifier.size(width = 40.dp, height = 24.dp).padding(end = 0.dp),
        )
        IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Edit, contentDescription = "Редактировать", modifier = Modifier.size(16.dp))
        }
        if (!invariant.lockedByTask) {
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Удалить",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InvariantEditDialog(
    initial: Invariant?,
    onDismiss: () -> Unit,
    onConfirm: (InvariantCategory, String, String, InvariantSeverity) -> Unit,
) {
    var selectedCategory by remember { mutableStateOf(initial?.category ?: InvariantCategory.CUSTOM) }
    var categoryExpanded by remember { mutableStateOf(false) }
    var rule by remember { mutableStateOf(
        if (initial != null && initial.title != initial.rule) "${initial.title}: ${initial.rule}"
        else initial?.rule ?: ""
    ) }
    var severity by remember { mutableStateOf(initial?.severity ?: InvariantSeverity.HARD) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Новый инвариант" else "Редактировать инвариант") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {

                ExposedDropdownMenuBox(
                    expanded = categoryExpanded,
                    onExpandedChange = { categoryExpanded = it },
                ) {
                    OutlinedTextField(
                        value = selectedCategory.label,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Категория") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoryExpanded) },
                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                    )
                    ExposedDropdownMenu(
                        expanded = categoryExpanded,
                        onDismissRequest = { categoryExpanded = false },
                    ) {
                        InvariantCategory.entries.forEach { cat ->
                            DropdownMenuItem(
                                text = { Text(cat.label) },
                                onClick = {
                                    selectedCategory = cat
                                    categoryExpanded = false
                                },
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = rule,
                    onValueChange = { rule = it },
                    label = { Text("Правило") },
                    placeholder = { Text("Например: Нельзя Retrofit — только Ktor") },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(
                    text = "Строгость",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                InvariantSeverity.entries.forEach { sev ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        RadioButton(selected = severity == sev, onClick = { severity = sev })
                        Text(sev.label, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmed = rule.trim()
                    val autoTitle = trimmed.take(35).trimEnd().let { if (trimmed.length > 35) "$it…" else it }
                    onConfirm(selectedCategory, autoTitle, trimmed, severity)
                },
                enabled = rule.isNotBlank(),
            ) { Text(if (initial == null) "Добавить" else "Сохранить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}
