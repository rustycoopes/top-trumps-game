package com.toptrumps.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Story 10 — load-bearing, not a nicety: mDNS is defeated by client isolation, some mesh systems
 * and any active VPN. Home networks are overwhelmingly `/24`, so a peer's own device is shown as
 * only its last octet — a two-digit code — with the full address behind an affordance for the
 * rare non-`/24` network.
 */
@Composable
public fun ManualConnectScreen(
    ownAddress: String?,
    onConnect: (host: String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showFullOwnAddress by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var fullAddress by remember { mutableStateOf("") }

    Column(modifier = modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Connect by address")

        if (ownAddress != null) {
            val lastOctet = ownAddress.substringAfterLast('.', ownAddress)
            Text("Your code: $lastOctet")
            TextButton(onClick = { showFullOwnAddress = !showFullOwnAddress }) {
                Text(if (showFullOwnAddress) "Hide full address" else "Show full address")
            }
            if (showFullOwnAddress) {
                Text(ownAddress)
            }
        } else {
            Text("Waiting for a Wi-Fi network…")
        }

        HorizontalDivider()

        Text("Enter the other player's code")
        OutlinedTextField(value = code, onValueChange = { code = it }, singleLine = true, label = { Text("Code") })
        Button(
            onClick = { onConnect(hostFromCode(ownAddress, code)) },
            enabled = ownAddress != null && code.isNotBlank(),
        ) {
            Text("Connect")
        }

        HorizontalDivider()

        Text("Different network? Enter their full address instead.")
        OutlinedTextField(value = fullAddress, onValueChange = { fullAddress = it }, singleLine = true, label = { Text("Full address") })
        Button(onClick = { onConnect(fullAddress) }, enabled = fullAddress.isNotBlank()) {
            Text("Connect to full address")
        }

        TextButton(onClick = onBack) { Text("Back") }
    }
}

/** Reconstructs a full IPv4 from the peer's two-digit code using our own subnet prefix — safe on the overwhelmingly common `/24` home network. */
private fun hostFromCode(ownAddress: String?, code: String): String {
    val octets = ownAddress?.split(".")
    return if (octets != null && octets.size == 4) "${octets[0]}.${octets[1]}.${octets[2]}.$code" else code
}
