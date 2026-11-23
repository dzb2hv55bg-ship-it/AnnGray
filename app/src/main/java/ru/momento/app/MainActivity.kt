package ru.momento.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import ru.momento.app.ui.MomentoApp
import ru.momento.app.ui.theme.MomentoTheme

class MainActivity : ComponentActivity() {
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.hasExtra("record_room_id")) recreate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MomentoTheme {
                MomentoApp()
            }
        }
    }
}
