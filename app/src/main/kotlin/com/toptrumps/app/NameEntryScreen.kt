package com.toptrumps.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Story 1/2: first run only — prefilled from the device name, remembered afterwards via [SettingsScreen]. */
@Composable
public fun NameEntryScreen(prefill: String, onSave: (String) -> Unit, modifier: Modifier = Modifier) {
    var name by remember { mutableStateOf(prefill) }

    Column(modifier = modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("What should other players call you?")
        OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true)
        Button(onClick = { onSave(name.trim().ifBlank { prefill }) }) {
            Text("Continue")
        }
    }
}
