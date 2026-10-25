package com.dangluci.reader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import com.dangluci.reader.ui.AppRoot
import com.dangluci.reader.ui.theme.ReaderTheme

class MainActivity : ComponentActivity() {
    /** 外部“用挡路词阅读器打开”传进来的 txt */
    private val incomingUri = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) incomingUri.value = intent.viewUri()
        val container = (application as ReaderApp).container
        setContent {
            val theme by container.settings.theme.collectAsState()
            ReaderTheme(theme) {
                AppRoot(
                    container = container,
                    incomingUri = incomingUri.value,
                    onIncomingConsumed = { incomingUri.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.viewUri()?.let { incomingUri.value = it }
    }

    private fun Intent.viewUri(): Uri? = if (action == Intent.ACTION_VIEW) data else null
}
