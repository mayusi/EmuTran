package io.github.mayusi.emutran

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import io.github.mayusi.emutran.ui.EmuTranApp
import io.github.mayusi.emutran.ui.theme.EmuTranTheme

/**
 * Single host activity. All screens are Compose destinations inside [EmuTranApp].
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EmuTranTheme {
                EmuTranApp()
            }
        }
    }
}
