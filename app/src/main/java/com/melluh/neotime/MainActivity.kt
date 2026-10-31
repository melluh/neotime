package com.melluh.neotime

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.melluh.neotime.ui.NeotimeRoot
import com.melluh.neotime.ui.theme.NeotimeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as NeotimeApp).container
        setContent {
            NeotimeTheme {
                NeotimeRoot(container)
            }
        }
    }
}
