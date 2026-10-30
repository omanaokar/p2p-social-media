package com.proximity.app.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.proximity.app.AppContainer
import com.proximity.app.TransportMode
import com.proximity.app.ui.chat.ChatScreen
import com.proximity.app.ui.feed.CommentsSheet
import com.proximity.app.ui.feed.FeedScreen
import com.proximity.app.ui.mesh.MeshScreen
import com.proximity.app.ui.profile.ProfileScreen
import com.proximity.app.ui.theme.SignalImmediate
import com.proximity.app.ui.viewmodels.ChatViewModel
import com.proximity.app.ui.viewmodels.CommentsViewModel
import com.proximity.app.ui.viewmodels.FeedViewModel
import com.proximity.app.ui.viewmodels.MeshViewModel
import com.proximity.app.ui.viewmodels.ProfileViewModel
import com.proximity.app.ui.viewmodels.ViewModelFactory

enum class AppDestination(val label: String, val icon: ImageVector) {
    FEED("Feed", Icons.Default.Home),
    CHAT("Messages", Icons.Default.Email),
    MESH("Mesh", Icons.Default.Share),
    PROFILE("Profile", Icons.Default.Person)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScreen(
    container: AppContainer,
    darkMode: Boolean,
    onDarkModeChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val factory = remember(container) { ViewModelFactory(container) }
    val feedViewModel: FeedViewModel = viewModel(factory = factory)
    val chatViewModel: ChatViewModel = viewModel(factory = factory)
    val meshViewModel: MeshViewModel = viewModel(factory = factory)
    val profileViewModel: ProfileViewModel = viewModel(factory = factory)

    val meshUiState by meshViewModel.uiState.collectAsState()

    var currentDestination by remember { mutableStateOf(AppDestination.FEED) }
    var activeCommentsPostId by remember { mutableStateOf<String?>(null) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface,
                ),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Proximity",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        // Mesh status chip
                        Surface(
                            color = if (meshUiState.isCoreReady) SignalImmediate.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = if (meshUiState.isCoreReady) SignalImmediate else MaterialTheme.colorScheme.outline
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (meshUiState.isCoreReady) {
                                        "${meshUiState.nearbyProfiles.size} peers"
                                    } else {
                                        "Connecting..."
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (meshUiState.isCoreReady) SignalImmediate else MaterialTheme.colorScheme.outline,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp,
            ) {
                AppDestination.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = currentDestination == destination,
                        onClick = { currentDestination = destination },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        icon = { Icon(imageVector = destination.icon, contentDescription = destination.label) }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (currentDestination) {
                AppDestination.FEED -> {
                    FeedScreen(
                        viewModel = feedViewModel,
                        onOpenComments = { postId -> activeCommentsPostId = postId }
                    )
                }
                AppDestination.CHAT -> {
                    ChatScreen(viewModel = chatViewModel)
                }
                AppDestination.MESH -> {
                    MeshScreen(viewModel = meshViewModel)
                }
                AppDestination.PROFILE -> {
                    ProfileScreen(
                        viewModel = profileViewModel,
                        darkMode = darkMode,
                        onDarkModeChange = onDarkModeChange,
                    )
                }
            }

            // Comments Bottom Sheet overlay
            activeCommentsPostId?.let { postId ->
                val commentsViewModel = remember(postId) { CommentsViewModel(container, postId) }
                CommentsSheet(
                    viewModel = commentsViewModel,
                    onDismiss = { activeCommentsPostId = null }
                )
            }
        }
    }
}
