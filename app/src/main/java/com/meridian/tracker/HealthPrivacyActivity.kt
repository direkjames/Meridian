package com.meridian.tracker

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.meridian.tracker.ui.components.FullscreenBox
import com.meridian.tracker.ui.components.PillButton
import com.meridian.tracker.ui.theme.MType
import com.meridian.tracker.ui.theme.MeridianTheme

/**
 * Health Connect opens this when someone taps "Read privacy policy" while giving Meridian access.
 */
class HealthPrivacyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            MeridianTheme {
                FullscreenBox {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .systemBarsPadding()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 28.dp, vertical = 24.dp),
                    ) {
                        Text("MERIDIAN", style = MType.Wordmark)
                        Spacer(Modifier.height(40.dp))
                        Text("Your health data stays on this phone.", style = MType.Title)
                        Spacer(Modifier.height(18.dp))
                        Text(
                            "Meridian reads workouts, routes, heart rate, steps and distance from Health Connect so " +
                                "the activities you record with a watch (Fitbit, Garmin, Samsung, Pixel Watch and others) " +
                                "show up next to the ones you record with Meridian.\n\n" +
                                "Meridian only reads. It never writes to Health Connect, never uploads your data, and has " +
                                "no account or servers. Everything it reads is saved in Meridian's private storage on this " +
                                "phone and in backups that you choose to make.\n\n" +
                                "You can turn access off at any time in Health Connect, and delete any activity in Meridian.",
                            style = MType.Body,
                        )
                        Spacer(Modifier.height(32.dp))
                        PillButton("Done", onClick = { finish() }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}
