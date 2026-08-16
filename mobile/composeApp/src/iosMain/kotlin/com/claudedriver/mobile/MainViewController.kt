package com.claudedriver.mobile

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

// The AWS-hosted backend base URL (injected from the iOS build config in a real build).
private const val BACKEND_BASE_URL = "https://claudedriver.example.com"

@Suppress("unused", "FunctionName")
fun MainViewController(): UIViewController = ComposeUIViewController { App(BACKEND_BASE_URL) }
