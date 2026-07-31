package com.toptrumps.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

public class MainActivity : ComponentActivity() {

    private lateinit var appGraph: AppGraph

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appGraph = AppGraph(assets)

        setContent {
            SoloMatchHost(appGraph)
        }
    }

    override fun onDestroy() {
        appGraph.close()
        super.onDestroy()
    }
}

/** Owns the current solo [com.toptrumps.session.MatchSession] and swaps it in for a rematch. */
@Composable
private fun SoloMatchHost(appGraph: AppGraph) {
    var session by remember { mutableStateOf(appGraph.startSoloMatch()) }

    MatchScreen(
        session = session,
        onRematch = {
            session.close()
            session = appGraph.startSoloMatch()
        },
    )
}
