package com.droidnova.allfilereader

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.droidnova.allfilereader.navigation.AllFileReaderApp
import com.droidnova.allfilereader.ui.theme.AllFileReaderTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
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
