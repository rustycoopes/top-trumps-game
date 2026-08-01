package com.toptrumps.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.toptrumps.session.Role

/**
 * This slice's entire success condition — "a connected socket and a screen that says so" — and
 * nothing more. Starting the actual match over this connection is slice 5's job.
 */
@Composable
public fun ConnectedScreen(role: Role, peerDisplayName: String, onLeave: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Connected to $peerDisplayName")
        Text(
            when (role) {
                Role.HOST -> "You are Player One."
                Role.GUEST -> "$peerDisplayName is Player One."
            },
        )
        Button(onClick = onLeave) { Text("Leave") }
    }
}
