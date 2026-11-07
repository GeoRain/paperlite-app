package com.codefox.paperlite.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "PaperLite 电脑版") {
        MaterialTheme {
            var text by remember { mutableStateOf("Hello PaperLite") }
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Text(text)
                Button(onClick = { text = "Clicked" }) { Text("Test") }
            }
        }
    }
}
