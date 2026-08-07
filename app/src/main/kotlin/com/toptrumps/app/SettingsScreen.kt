package com.toptrumps.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.toptrumps.app.theme.DisplayFontFamily
import com.toptrumps.app.theme.LobbyPalette

/**
 * Story 2's "editable later" route — changing the name here means re-registering the NSD service
 * the next time the lobby starts. [onOpenCardGallery], when non-null, renders a debug-only entry
 * point to the card gallery — `null` in a release build, gated at the `MainActivity` call site.
 * Themed as "The Pack" (second-round-updates#52) via the same [LobbyPalette] as [LobbyScreen],
 * reusing its [LobbyTextLink] and [LobbyPrimaryButton] rather than restyling from scratch.
 */
@Composable
public fun SettingsScreen(
    currentName: String,
    onSave: (String) -> Unit,
    onBack: () -> Unit,
    muted: Boolean,
    onSetMuted: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onOpenCardGallery: (() -> Unit)? = null,
) {
    var name by remember { mutableStateOf(currentName) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(LobbyPalette.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "Settings",
            style = TextStyle(fontFamily = DisplayFontFamily, fontWeight = FontWeight.Normal, fontSize = 34.sp),
            color = LobbyPalette.ink,
        )

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            singleLine = true,
            label = { Text("Display name") },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = LobbyPalette.ink,
                unfocusedTextColor = LobbyPalette.ink,
                focusedContainerColor = LobbyPalette.surface,
                unfocusedContainerColor = LobbyPalette.surface,
                focusedBorderColor = LobbyPalette.ink,
                unfocusedBorderColor = LobbyPalette.divider,
                focusedLabelColor = LobbyPalette.ink,
                unfocusedLabelColor = LobbyPalette.inkDim,
                cursorColor = LobbyPalette.ink,
            ),
        )

        LobbyPrimaryButton("Save", onClick = {
            onSave(name.trim().ifBlank { currentName })
            onBack()
        })
        LobbyTextLink("Cancel", onBack)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Mute sound effects", color = LobbyPalette.ink)
            Switch(
                checked = muted,
                onCheckedChange = onSetMuted,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = LobbyPalette.accent,
                    checkedTrackColor = LobbyPalette.ink,
                    checkedBorderColor = LobbyPalette.ink,
                    uncheckedThumbColor = LobbyPalette.surface,
                    uncheckedTrackColor = LobbyPalette.divider,
                    uncheckedBorderColor = LobbyPalette.divider,
                ),
            )
        }

        if (onOpenCardGallery != null) {
            LobbyTextLink("Card gallery (debug)", onOpenCardGallery)
        }
    }
}
