package com.toptrumps.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Story 2's "editable later" route — changing the name here means re-registering the NSD service the next time the lobby starts. */
@Composable
public fun SettingsScreen(
    currentName: String,
    onSave: (String) -> Unit,
    onBack: () -> Unit,
    muted: Boolean,
    onSetMuted: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var name by remember { mutableStateOf(currentName) }

    Column(modifier = modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Settings")
        OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Display name") })
        Button(onClick = {
            onSave(name.trim().ifBlank { currentName })
            onBack()
        }) { Text("Save") }
        TextButton(onClick = onBack) { Text("Cancel") }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Mute sound effects")
            Switch(checked = muted, onCheckedChange = onSetMuted)
        }
    }
}
