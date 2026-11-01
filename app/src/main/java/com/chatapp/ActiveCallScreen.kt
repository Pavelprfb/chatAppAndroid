package com.chatapp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.chatapp.data.CallUiState
import com.chatapp.data.WebRtcCallManager

@Composable
fun ActiveCallScreen(
    state: CallUiState?,
    callManager: WebRtcCallManager,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onEnd: () -> Unit,
    onToggleMicrophone: () -> Unit,
    onToggleCamera: () -> Unit,
    onSwitchCamera: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onDismiss: () -> Unit
) {
    val call = state ?: return
    DisposableEffect(Unit) {
        onDispose { callManager.releaseRenderers() }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            call.participantName,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            if (call.mediaType == "video") "Video call" else "Audio call",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 8.dp)
        )
        Text(
            call.error ?: call.status,
            color = if (call.error != null) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 12.dp)
        )
        if (call.mediaType == "video" && call.error == null) {
            AndroidView(
                factory = { callManager.createRenderer(it, false) },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
            )
            AndroidView(
                factory = { callManager.createRenderer(it, true) },
                modifier = Modifier
                    .size(width = 140.dp, height = 190.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .align(Alignment.End)
            )
        } else {
            Spacer(modifier = Modifier.weight(1f))
        }
        when {
            call.incoming && call.status == "Incoming call" -> Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = onAccept) { Text("Accept") }
                TextButton(onClick = onDecline) { Text("Decline") }
            }
            call.status in setOf("Call ended", "Call declined", "Call error", "No answer") ->
                Button(onClick = onDismiss) { Text("Close") }
            else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onToggleMicrophone) {
                        Text(if (call.muted) "Unmute" else "Mute")
                    }
                    TextButton(onClick = onToggleSpeaker) {
                        Text(if (call.speakerEnabled) "Earpiece" else "Speaker")
                    }
                }
                if (call.mediaType == "video") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onToggleCamera) {
                            Text(if (call.cameraEnabled) "Camera off" else "Camera on")
                        }
                        TextButton(onClick = onSwitchCamera) { Text("Flip") }
                    }
                }
                Button(onClick = onEnd) { Text("End") }
            }
        }
    }
}
