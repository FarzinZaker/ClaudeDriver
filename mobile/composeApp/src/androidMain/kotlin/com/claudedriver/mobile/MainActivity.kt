package com.claudedriver.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

// The AWS-hosted backend base URL (build config / BuildConfig in a real build).
private const val BACKEND_BASE_URL = "https://claudedriver.example.com"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App(BACKEND_BASE_URL) }
    }
}
