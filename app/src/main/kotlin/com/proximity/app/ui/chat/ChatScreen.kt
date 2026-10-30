package com.proximity.app.ui.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.proximity.app.ui.components.AvatarBadge
import com.proximity.app.ui.components.EmptyStateView
import com.proximity.app.ui.components.ErrorBanner
import com.proximity.app.ui.viewmodels.ChatViewModel
import com.proximity.contracts.ChatMessage
import com.proximity.contracts.Conversation
import com.proximity.contracts.MessageStatus
import com.proximity.contracts.PeerProfile
import java.io.ByteArrayOutputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    var pendingImage by remember { mutableStateOf<ByteArray?>(null) }
    val context = LocalContext.current
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
                ?.let(::compressChatImage)
        }.onSuccess { bytes -> if (bytes != null) pendingImage = bytes }
            .onFailure { viewModel.setErrorMessage(it.message ?: "Could not load image") }
    }

    Column(modifier = modifier.fillMaxSize()) {
        ErrorBanner(
            message = uiState.errorMessage,
            onDismiss = { viewModel.clearError() }
        )

        if (uiState.activePeerId == null) {
            // Inbox View
            InboxView(
                inbox = uiState.inbox,
                nearbyPeers = uiState.nearbyPeers,
                isLoading = uiState.isLoading,
                onSelectConversation = { peerId -> viewModel.openConversation(peerId) },
                onMessageNearbyPeer = { peerId -> viewModel.openConversation(peerId) },
            )
        } else {
            // Conversation Thread
            ConversationView(
                peerId = uiState.activePeerId!!,
                messages = uiState.activeMessages,
                selectedImage = pendingImage,
                onPickImage = { imagePicker.launch("image/*") },
                onClearImage = { pendingImage = null },
                isSending = uiState.isSending,
                onBack = { viewModel.closeConversation() },
                onSendMessage = { text, image -> viewModel.sendMessage(uiState.activePeerId!!, text, image) }
            )
        }
    }
}

@Composable
fun InboxView(
    inbox: List<Conversation>,
    nearbyPeers: List<PeerProfile>,
    isLoading: Boolean,
    onSelectConversation: (String) -> Unit,
    onMessageNearbyPeer: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        when {
            isLoading -> {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
            inbox.isEmpty() && nearbyPeers.isEmpty() -> {
                EmptyStateView(
                    title = "No conversations yet",
                    description = "Connect to a peer from the Mesh tab. Nearby peers will appear here so you can start an encrypted chat.",
                    icon = Icons.Default.Email,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            else -> {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    val existingPeerIds = inbox.mapTo(mutableSetOf()) { it.peerId }
                    val newNearbyPeers = nearbyPeers.filterNot { it.userId in existingPeerIds }

                    if (newNearbyPeers.isNotEmpty()) {
                        item {
                            Text(
                                text = "Nearby peers",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                            )
                        }
                        items(newNearbyPeers, key = { "nearby-${it.userId}" }) { peer ->
                            NearbyPeerItem(
                                peer = peer,
                                onClick = { onMessageNearbyPeer(peer.userId) }
                            )
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        }
                    }

                    if (inbox.isNotEmpty()) {
                        item {
                            Text(
                                text = "Conversations",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                            )
                        }
                        items(inbox, key = { it.peerId }) { conversation ->
                            ConversationItem(
                                conversation = conversation,
                                onClick = { onSelectConversation(conversation.peerId) }
                            )
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun NearbyPeerItem(
    peer: PeerProfile,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        AvatarBadge(name = peer.profile.displayName ?: "Peer", size = 48.dp)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = peer.profile.displayName ?: "Peer ${peer.userId.take(8)}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Connected nearby · Tap to message",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Button(onClick = onClick) {
            Text("Message")
        }
    }
}

@Composable
fun ConversationItem(
    conversation: Conversation,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(16.dp)
    ) {
        AvatarBadge(name = conversation.peer?.displayName ?: "Peer", size = 48.dp)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = conversation.peer?.displayName ?: "Peer ${conversation.peerId.take(8)}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = conversation.lastMessage.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationView(
    peerId: String,
    messages: List<ChatMessage>,
    selectedImage: ByteArray?,
    onPickImage: () -> Unit,
    onClearImage: () -> Unit,
    isSending: Boolean,
    onBack: () -> Unit,
    onSendMessage: (String, ByteArray?) -> Unit,
    modifier: Modifier = Modifier
) {
    var messageInput by remember { mutableStateOf("") }
    val preview = remember(selectedImage) { selectedImage?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }

    Column(modifier = modifier.fillMaxSize()) {
        // Conversation Top Bar
        TopAppBar(
            title = {
                Column {
                    Text(
                        text = "Encrypted Chat",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "X25519 • ChaCha20-Poly1305",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Back to inbox")
                }
            }
        )

        HorizontalDivider()

        // Message Thread
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages, key = { it.id }) { msg ->
                MessageBubble(message = msg)
            }
        }

        HorizontalDivider()

        // Input Box
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            OutlinedTextField(
                value = messageInput,
                onValueChange = { messageInput = it },
                placeholder = { Text("Encrypted direct message...") },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(24.dp),
                singleLine = true
            )
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                onClick = {
                    if (messageInput.isNotBlank() || selectedImage != null) {
                        onSendMessage(messageInput, selectedImage)
                        messageInput = ""
                        onClearImage()
                    }
                },
                enabled = (messageInput.isNotBlank() || selectedImage != null) && !isSending
            ) {
                if (isSending) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                } else {
                    Icon(
                        imageVector = Icons.Default.Send,
                        contentDescription = "Send encrypted message",
                        tint = if (messageInput.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onPickImage, enabled = !isSending) {
                Icon(Icons.Default.Add, contentDescription = null)
                Text(" Image")
            }
            if (preview != null) {
                Spacer(Modifier.width(8.dp))
                Image(bitmap = preview, contentDescription = "Selected chat image", modifier = Modifier.size(56.dp))
                TextButton(onClick = onClearImage) { Text("Remove") }
            }
        }
    }
}

@Composable
fun MessageBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier
) {
    val isMine = message.isMine
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = if (isMine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isMine) 16.dp else 4.dp,
                bottomEnd = if (isMine) 4.dp else 16.dp
            ),
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                message.image?.let { bytes ->
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()?.let { bitmap ->
                        Image(bitmap = bitmap, contentDescription = "Image message", modifier = Modifier.fillMaxWidth().height(200.dp))
                    }
                }
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isMine) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (isMine) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        when (message.status) {
                            MessageStatus.PENDING -> {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Relaying",
                                    modifier = Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.outline
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = "Relaying",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                            MessageStatus.DELIVERED -> {
                                Icon(
                                    imageVector = Icons.Default.Done,
                                    contentDescription = "Delivered",
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = "Delivered",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            MessageStatus.RECEIVED -> {}
                        }
                    }
                }
            }
        }
    }
}

private fun compressChatImage(source: Bitmap): ByteArray {
    val maxDimension = 1280
    val scale = minOf(1f, maxDimension.toFloat() / maxOf(source.width, source.height))
    val resized = if (scale < 1f) Bitmap.createScaledBitmap(source, (source.width * scale).toInt().coerceAtLeast(1), (source.height * scale).toInt().coerceAtLeast(1), true) else source
    var quality = 82
    var bytes: ByteArray
    do {
        bytes = ByteArrayOutputStream().use { output ->
            resized.compress(Bitmap.CompressFormat.JPEG, quality, output)
            output.toByteArray()
        }
        quality -= 10
    } while (bytes.size > 160 * 1024 && quality >= 32)
    if (resized !== source) resized.recycle()
    if (source !== resized) source.recycle()
    require(bytes.size <= 180 * 1024) { "Chat image must compress to 180 KB or less" }
    return bytes
}
