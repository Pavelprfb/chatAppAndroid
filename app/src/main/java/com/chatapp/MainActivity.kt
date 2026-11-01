package com.chatapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.chatapp.api.AuthApi
import com.chatapp.api.ChatApi
import com.chatapp.data.AuthRepository
import com.chatapp.data.ChatCache
import com.chatapp.data.ChatRepository
import com.chatapp.security.RefreshTokenStore
import com.chatapp.ui.AuthViewModel
import com.chatapp.ui.ChatUiState
import com.chatapp.ui.ChatViewModel

class MainActivity : ComponentActivity() {
    private val authRepository by lazy {
        AuthRepository(
            AuthApi.create(BuildConfig.API_BASE_URL),
            RefreshTokenStore(applicationContext)
        )
    }

    private val viewModel: AuthViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(AuthViewModel::class.java))
                return AuthViewModel(authRepository) as T
            }
        }
    }

    private val chatViewModel: ChatViewModel by viewModels {
        val repository = ChatRepository(
            ChatApi.create(BuildConfig.API_BASE_URL),
            authRepository,
            BuildConfig.API_BASE_URL,
            ChatCache(applicationContext, RefreshTokenStore(applicationContext))
        )
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(ChatViewModel::class.java))
                return ChatViewModel(repository) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = colors) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppScreen(viewModel, chatViewModel)
                }
            }
        }
    }
}

@Composable
private fun AppScreen(authViewModel: AuthViewModel, chatViewModel: ChatViewModel) {
    val auth by authViewModel.state.collectAsState()
    val chat by chatViewModel.state.collectAsState()
    val userId = auth.user?.id

    LaunchedEffect(userId, auth.busy) {
        if (userId != null) {
            chatViewModel.startSession(userId)
        } else if (!auth.busy) {
            chatViewModel.clearSession()
        }
    }

    if (userId != null) {
        if (chat.selectedConversation == null) {
            ConversationListScreen(chat, userId, chatViewModel, authViewModel::logout)
        } else {
            ConversationScreen(chat, userId, chatViewModel)
        }
    } else {
        AuthScreen(authViewModel)
    }
}

@Composable
private fun AuthScreen(viewModel: AuthViewModel) {
    val state by viewModel.state.collectAsState()
    var register by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("ChatApp", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(8.dp))
        if (state.busy) {
            CircularProgressIndicator()
        } else {
            Text(
                if (register) "Create your account" else "Sign in",
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email") },
                supportingText = { Text("Use a valid email address") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            if (register) {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    supportingText = { Text("3-30 English letters, numbers, or underscores") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("Display name") },
                    supportingText = { Text("1-80 characters") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password (12-128 characters)") },
                supportingText = {
                    Text(if (register) "Choose a password with at least 12 characters" else "Enter your password")
                },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            state.message?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { viewModel.authenticate(email, username, displayName, password, register) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (register) "Create account" else "Sign in")
            }
            TextButton(onClick = { register = !register }) {
                Text(if (register) "Already have an account? Sign in" else "Create an account")
            }
        }
    }
}

@Composable
private fun ConversationListScreen(
    state: ChatUiState,
    userId: String,
    chatViewModel: ChatViewModel,
    onLogout: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Chats", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            TextButton(onClick = onLogout) { Text("Log out") }
        }
        Text(
            if (state.liveConnected) "Live messages connected" else "Connecting to live messages…",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = chatViewModel::search,
            label = { Text("Find people by username or name") },
            supportingText = { Text("Type at least 2 characters") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp))
        }
        if (state.searchResults.isNotEmpty()) {
            Text(
                "People",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 12.dp)
            )
            state.searchResults.forEach { person ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { chatViewModel.startConversation(person) }
                        .padding(vertical = 12.dp, horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(person.displayName, fontWeight = FontWeight.SemiBold)
                        Text("@${person.username}", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("Message", color = MaterialTheme.colorScheme.primary)
                }
                Text(
                    if (state.liveConnected) "Live" else "Reconnecting…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, bottom = 8.dp)
                )
                HorizontalDivider()
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("Recent conversations", style = MaterialTheme.typography.titleMedium)
        when {
            state.busy && state.conversations.isEmpty() -> CircularProgressIndicator(
                modifier = Modifier.padding(24.dp)
            )
            state.conversations.isEmpty() && state.searchResults.isEmpty() -> Text(
                "No chats yet. Search for someone above to start a conversation.",
                modifier = Modifier.padding(top = 12.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(state.conversations, key = { it.id }) { conversation ->
                    val person = conversation.otherParticipant(userId)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { chatViewModel.openConversation(conversation) }
                            .padding(vertical = 14.dp)
                    ) {
                        Text(person?.displayName ?: "Conversation", fontWeight = FontWeight.SemiBold)
                        Text(
                            conversation.lastMessage?.let { "${it.sender.displayName}: ${it.content}" }
                                ?: "No messages yet",
                            maxLines = 1,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun ConversationScreen(state: ChatUiState, userId: String, chatViewModel: ChatViewModel) {
    val conversation = state.selectedConversation ?: return
    val other = conversation.otherParticipant(userId)
    var draft by remember(conversation.id) { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = chatViewModel::closeConversation) { Text("Back") }
            Column(modifier = Modifier.padding(start = 4.dp)) {
                Text(other?.displayName ?: "Chat", fontWeight = FontWeight.Bold)
                if (other != null) {
                    Text("@${other.username}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        HorizontalDivider()
        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp))
        }
        if (state.busy && state.messages.isEmpty()) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        }
        if (state.messages.isEmpty() && !state.busy) {
            Text(
                "No messages yet. Say hello!",
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .wrapContentHeight(Alignment.CenterVertically),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.messages, key = { it.id }) { message ->
                    val mine = message.sender.id == userId
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start
                    ) {
                        Surface(
                            color = if (mine) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth(0.82f)
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                if (!mine) {
                                    Text(message.sender.displayName, style = MaterialTheme.typography.labelSmall)
                                    Spacer(Modifier.height(3.dp))
                                }
                                Text(message.content)
                            }
                        }
                    }
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { if (it.length <= 4000) draft = it },
                label = { Text("Message") },
                modifier = Modifier.weight(1f),
                maxLines = 4
            )
            Spacer(Modifier.size(8.dp))
            Button(
                enabled = draft.isNotBlank() && !state.sending,
                onClick = {
                    chatViewModel.sendMessage(draft)
                    draft = ""
                }
            ) {
                Text(if (state.sending) "…" else "Send")
            }
        }
    }
}
