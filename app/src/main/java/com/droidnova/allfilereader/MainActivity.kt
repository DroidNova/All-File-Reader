package com.droidnova.allfilereader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.droidnova.allfilereader.navigation.AllFileReaderApp
import com.droidnova.allfilereader.ui.theme.AllFileReaderTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AllFileReaderTheme {
                AllFileReaderApp()
            }
        }
    }
}
