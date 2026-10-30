package com.proximity.app.ui.feed

import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import java.io.ByteArrayOutputStream
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.proximity.app.ui.components.AvatarBadge
import com.proximity.app.ui.components.EmptyStateView
import com.proximity.app.ui.components.ErrorBanner
import com.proximity.app.ui.components.SignalIndicator
import com.proximity.app.ui.viewmodels.FeedViewModel
import com.proximity.contracts.InteractionType
import com.proximity.contracts.RankedPost
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun FeedScreen(
    viewModel: FeedViewModel,
    onOpenComments: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    var showCreateDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
                ?.let(::compressPostImage)
        }.onSuccess { bytes ->
            if (bytes != null) viewModel.setSelectedPostImage(bytes)
        }.onFailure { viewModel.setImageError(it.message ?: "Could not load image") }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showCreateDialog = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(imageVector = Icons.Default.Add, contentDescription = "Create Post")
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Search Bar
            FeedSearchBar(
                query = uiState.searchQuery ?: "",
                onQueryChange = { viewModel.setSearchQuery(it.ifBlank { null }) },
                onClear = { viewModel.setSearchQuery(null) }
            )

            ErrorBanner(
                message = uiState.errorMessage,
                onDismiss = { viewModel.clearError() }
            )

            // Feed Content
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
            ) {
                when {
                    uiState.isLoading -> {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    }
                    uiState.posts.isEmpty() -> {
                        EmptyStateView(
                            title = if (uiState.searchQuery != null) "No matching posts" else "No posts nearby",
                            description = if (uiState.searchQuery != null) {
                                "No posts matched \"${uiState.searchQuery}\". Try another keyword."
                            } else {
                                "No one around you has posted yet. Share a thought or make sure peers have Bluetooth/Wi-Fi active!"
                            },
                            icon = Icons.Default.Home,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(uiState.posts, key = { it.candidate.post.id }) { rankedPost ->
                                PostCard(
                                    rankedPost = rankedPost,
                                    onReact = {
                                        if (rankedPost.candidate.myReaction != null) {
                                            viewModel.removeReaction(rankedPost.candidate.post.id)
                                        } else {
                                            viewModel.react(rankedPost.candidate.post.id, "like")
                                        }
                                    },
                                    onOpenComments = {
                                        viewModel.recordInteraction(
                                            rankedPost.candidate.post.id,
                                            InteractionType.OPEN
                                        )
                                        onOpenComments(rankedPost.candidate.post.id)
                                    },
                                    onDelete = { viewModel.deletePost(rankedPost.candidate.post.id) },
                                    onReport = { viewModel.reportPost(rankedPost.candidate.post.id) }
                                )
                            }
                        }
                    }
                }
            }
        }

        if (showCreateDialog) {
            CreatePostDialog(
                onDismiss = { showCreateDialog = false },
                onPickImage = { imagePicker.launch("image/*") },
                onPublish = { text, image ->
                    viewModel.createPost(text, image)
                    showCreateDialog = false
                },
                isPublishing = uiState.isCreatingPost,
                selectedImage = uiState.selectedPostImage,
                imageError = uiState.imageError,
                onClearImage = { viewModel.setSelectedPostImage(null) },
                onClearImageError = { viewModel.setImageError(null) },
            )
        }
    }
}

@Composable
fun FeedSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text("Search nearby posts...") },
        leadingIcon = {
            Icon(imageVector = Icons.Default.Search, contentDescription = null)
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = onClear) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Clear search")
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(24.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
fun PostCard(
    rankedPost: RankedPost,
    onReact: () -> Unit,
    onOpenComments: () -> Unit,
    onDelete: () -> Unit,
    onReport: () -> Unit,
    modifier: Modifier = Modifier
) {
    val candidate = rankedPost.candidate
    val post = candidate.post
    var menuExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Author info & Signal Indicator
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                AvatarBadge(name = candidate.author?.displayName ?: "Nearby Peer")
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = candidate.author?.displayName ?: "Peer",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = formatTimeAgo(post.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                SignalIndicator(
                    rssi = candidate.receivedSignalStrength,
                    hopCount = post.hopCount
                )

                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(imageVector = Icons.Default.MoreVert, contentDescription = "Options")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        if (candidate.isMine) {
                            DropdownMenuItem(
                                text = { Text("Delete post") },
                                onClick = {
                                    menuExpanded = false
                                    onDelete()
                                }
                            )
                        } else {
                            DropdownMenuItem(
                                text = { Text("Report & block author") },
                                onClick = {
                                    menuExpanded = false
                                    onReport()
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Post Body
            Text(
                text = post.body,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )

            post.image?.let { bytes ->
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { bitmap ->
                    Spacer(modifier = Modifier.height(10.dp))
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Image attached to post",
                        modifier = Modifier.fillMaxWidth().height(240.dp)
                    )
                }
            }

            // Neil's Ranking Rationale Tag
            if (rankedPost.reason.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "★ ${rankedPost.reason}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        fontSize = 11.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Bottom Actions (React & Comments)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Like Button
                val isLiked = candidate.myReaction != null
                val totalLikes = candidate.reactionCounts.values.sum()

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clickable { onReact() }
                        .padding(vertical = 4.dp, horizontal = 8.dp)
                ) {
                    Icon(
                        imageVector = if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = "Like",
                        tint = if (isLiked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (totalLikes > 0) "$totalLikes" else "Like",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isLiked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                // Comments Button
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clickable { onOpenComments() }
                        .padding(vertical = 4.dp, horizontal = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Email,
                        contentDescription = "Comments",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (candidate.commentCount > 0) "${candidate.commentCount}" else "Comment",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun CreatePostDialog(
    onDismiss: () -> Unit,
    onPickImage: () -> Unit,
    onPublish: (String, ByteArray?) -> Unit,
    isPublishing: Boolean,
    selectedImage: ByteArray?,
    imageError: String?,
    onClearImage: () -> Unit,
    onClearImageError: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val preview = remember(selectedImage) {
        selectedImage?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Proximity Post") },
        text = {
            Column {
                Text(
                    text = "Broadcasted directly to devices physically near you over P2P mesh.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("What's happening nearby?") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp),
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(onClick = onPickImage, enabled = !isPublishing) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text(if (selectedImage == null) " Add image" else " Change image")
                }
                if (preview != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Image(bitmap = preview, contentDescription = "Selected post image", modifier = Modifier.fillMaxWidth().height(160.dp))
                    TextButton(onClick = onClearImage, enabled = !isPublishing) { Text("Remove image") }
                }
                if (imageError != null) {
                    TextButton(onClick = onClearImageError) { Text(imageError, color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onPublish(text, selectedImage) },
                enabled = (text.isNotBlank() || selectedImage != null) && !isPublishing
            ) {
                if (isPublishing) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text("Broadcast")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isPublishing) {
                Text("Cancel")
            }
        }
    )
}

private fun formatTimeAgo(instant: java.time.Instant): String {
    val duration = java.time.Duration.between(instant, java.time.Instant.now())
    val minutes = duration.toMinutes()
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        duration.toHours() < 24 -> "${duration.toHours()}h ago"
        else -> instant.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MMM d"))
    }
}

private fun compressPostImage(source: Bitmap): ByteArray {
    val maxDimension = 1280
    val scale = minOf(1f, maxDimension.toFloat() / maxOf(source.width, source.height))
    val resized = if (scale < 1f) Bitmap.createScaledBitmap(
        source,
        (source.width * scale).toInt().coerceAtLeast(1),
        (source.height * scale).toInt().coerceAtLeast(1),
        true,
    ) else source
    var quality = 85
    var bytes: ByteArray
    do {
        bytes = ByteArrayOutputStream().use { output ->
            resized.compress(Bitmap.CompressFormat.JPEG, quality, output)
            output.toByteArray()
        }
        quality -= 10
    } while (bytes.size > 180 * 1024 && quality >= 35)
    if (resized !== source) resized.recycle()
    if (source !== resized) source.recycle()
    require(bytes.size <= 256 * 1024) { "Image is too large after compression" }
    return bytes
}
