package com.droidnova.allfilereader

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import com.droidnova.allfilereader.navigation.AllFileReaderApp
import com.droidnova.allfilereader.ui.theme.AllFileReaderTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : FragmentActivity() {
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
