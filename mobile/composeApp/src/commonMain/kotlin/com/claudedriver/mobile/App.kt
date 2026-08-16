package com.claudedriver.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Minimal operator UI: passkey login → pending approvals with Approve/Deny (the headline mobile
 * action) and a session list (monitoring parity). Live updates via the operator WebSocket
 * (`approval_event` / `session_update`) are TODO — this scaffold polls on demand.
 */
@Composable
fun App(baseUrl: String) {
    val scope = rememberCoroutineScope()
    val client = remember { ClaudeDriverApi.defaultClient(httpClient()) }
    val api = remember { ClaudeDriverApi(baseUrl, client) }

    var signedIn by remember { mutableStateOf(false) }
    var approvals by remember { mutableStateOf(listOf<ApprovalSummary>()) }
    var status by remember { mutableStateOf("Not signed in") }

    suspend fun refresh() {
        approvals = runCatching { api.approvals() }.getOrDefault(emptyList())
    }

    MaterialTheme {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("ClaudeDriver — ${platformName()}", style = MaterialTheme.typography.titleLarge)
            Text(status)

            if (!signedIn) {
                Button(onClick = {
                    scope.launch {
                        signedIn = signInWithPasskey(baseUrl)
                        status = if (signedIn) "Signed in" else "Sign-in failed"
                        if (signedIn) {
                            pushToken()?.let { api.registerDevice(it, platformName().lowercase()) }
                            refresh()
                        }
                    }
                }) { Text("Sign in with passkey") }
            } else {
                Text("Pending approvals", style = MaterialTheme.typography.titleMedium)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(approvals.filter { it.status == "pending" }) { approval ->
                        ApprovalCard(
                            approval = approval,
                            onDecide = { decision ->
                                scope.launch { api.decide(approval.id, decision); refresh() }
                            },
                        )
                    }
                }
                Button(onClick = { scope.launch { refresh() } }) { Text("Refresh") }
            }
        }
    }
}

@Composable
private fun ApprovalCard(approval: ApprovalSummary, onDecide: (String) -> Unit) {
    Card(Modifier.fillMaxWidth().padding(4.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(approval.machineName, style = MaterialTheme.typography.titleSmall)
            Text(approval.summary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onDecide("approve") }) { Text("Approve") }
                Button(onClick = { onDecide("deny") }) { Text("Deny") }
            }
        }
    }
}
