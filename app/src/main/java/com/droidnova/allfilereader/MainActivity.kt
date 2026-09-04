package com.droidnova.allfilereader

import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.droidnova.allfilereader.navigation.AllFileReaderApp
import com.droidnova.allfilereader.navigation.IncomingDocumentViewModel
import com.droidnova.allfilereader.ui.theme.AllFileReaderTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    private val incomingDocuments: IncomingDocumentViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AllFileReaderTheme {
                AllFileReaderApp(incomingDocumentViewModel = incomingDocuments)
            }
        }
        // A restored activity must not consume the launch intent for a second time.
        if (savedInstanceState == null) incomingDocuments.accept(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingDocuments.accept(intent)
    }
}
