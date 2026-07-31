package com.toptrumps.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.toptrumps.session.MatchSession

public class MainActivity : ComponentActivity() {

    private lateinit var appGraph: AppGraph
    private lateinit var soloMatch: MatchSession

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appGraph = AppGraph(assets)
        soloMatch = appGraph.startSoloMatch()

        setContent {
            MatchScreen(session = soloMatch)
        }
    }

    override fun onDestroy() {
        soloMatch.close()
        appGraph.close()
        super.onDestroy()
    }
}
