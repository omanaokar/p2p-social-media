package com.proximity.app

import android.app.*
import android.content.*
import android.graphics.*
import android.graphics.drawable.*
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import android.view.*
import android.widget.*
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.proximity.contracts.*
import com.proximity.core.AppCore
import com.proximity.core.identity.KeyStore
import com.proximity.transport.TransportConfig
import com.proximity.transport.nearby.NearbyTransport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.ByteArrayOutputStream

/** Dark, content-first social interface. AppCore remains the single source of application behaviour. */
class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var screenJob: Job? = null
    private var core: AppCore? = null
    private var nearbyTransport: NearbyTransport? = null
    @Volatile private var startingCore = false
    private lateinit var title: TextView
    private lateinit var stream: LinearLayout
    private lateinit var nav: LinearLayout
    private var pendingImage: ByteArray? = null
    private var pendingProfileAvatar: ByteArray? = null
    private var myProfile: Profile? = null
    private var messageNotificationJob: Job? = null
    private val notifiedIncomingMessageIds = mutableSetOf<String>()
    private var selectedDestination = "Home"
    private var refreshAction: () -> Unit = { home() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BLACK; window.navigationBarColor = BLACK
        createMessageNotificationChannel()
        setContentView(shell())
        if (hasNearbyPermissions()) { requestOptionalPermissionsIfNeeded(); startCore() } else requestNearbyPermissions()
    }

    private fun startCore() {
        if (core != null || startingCore) return
        startingCore = true
        scope.launch(Dispatchers.IO) {
            try {
                val preferences = getSharedPreferences("identity", MODE_PRIVATE)
                val endpointName = preferences.getString("nearby_endpoint_name", null)
                    ?: "Proximity-${java.util.UUID.randomUUID().toString().take(8)}".also {
                        preferences.edit().putString("nearby_endpoint_name", it).apply()
                    }
                val transport = NearbyTransport(
                    localEndpointName = endpointName,
                    config = TransportConfig(serviceId = packageName, dutyCycleEnabled = false),
                    driver = GoogleNearbyDriver(this@MainActivity),
                )
                nearbyTransport = transport
                val opened = AppCore.open(transport, PreferenceKeyStore(preferences), BundledSQLiteDriver(), getDatabasePath("proximity.db").path)
                opened.start(); myProfile = opened.profiles.watchMyProfile().first(); core = opened
                scope.launch { opened.profiles.watchMyProfile().collect { myProfile = it } }
                messageNotificationJob = scope.launch { watchIncomingMessageNotifications(opened) }
                withContext(Dispatchers.Main) { home() }
            } catch (error: Throwable) { withContext(Dispatchers.Main) { errorState(error) }
            } finally { startingCore = false }
        }
    }

    private fun shell() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setBackgroundColor(BLACK)
        addView(topBar())
        addView(ScrollView(this@MainActivity).apply {
            isFillViewport = true
            addView(LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; stream = this })
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        })
        nav = LinearLayout(this@MainActivity).apply { gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(5), dp(8), dp(8)); setBackgroundColor(BLACK) }
        addView(nav); bottomNavigation()
    }

    private fun topBar() = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(9), dp(16), dp(8)); setBackgroundColor(BLACK)
        addView(avatar("P", 34, ACCENT))
        title = TextView(this@MainActivity).apply { text = "Home"; textSize = 20f; setTypeface(null, 1); setTextColor(PRIMARY); gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        addView(icon("↻", "Refresh") { refreshAction() }); addView(icon("⌕", "Search") { explore() })
    }

    private fun bottomNavigation() {
        nav.removeAllViews()
        nav.addView(navItem("⌂", "Home") { home() }); nav.addView(navItem("⌕", "Explore") { explore() })
        nav.addView(navItem("✉", "Messages") { messages() }); nav.addView(navItem("◎", "Profile") { profile() })
    }
    private fun navItem(symbol: String, label: String, action: () -> Unit) = TextView(this).apply {
        text = "$symbol\n$label"; gravity = Gravity.CENTER; textSize = 12f; setTypeface(null, if (label == selectedDestination) 1 else 0)
        setTextColor(if (label == selectedDestination) ACCENT else SECONDARY); contentDescription = label
        setPadding(0, dp(7), 0, dp(5)); background = if (label == selectedDestination) underline(ACCENT) else null
        setOnClickListener { selectedDestination = label; bottomNavigation(); action() }; layoutParams = LinearLayout.LayoutParams(0, dp(58), 1f)
    }

    private fun home() {
        refreshAction = { home() }
        selectedDestination = "Home"; bottomNavigation(); title.text = "Home"; reset(); feedTabs(false); composerPrompt()
        screenJob = scope.launch { core?.watchRankedEducationalFeed()?.collect { posts ->
            clearFrom(2)
            if (posts.isEmpty()) empty("Your feed is waiting", "Share a lesson, question, or study tip with people nearby.")
            posts.forEach { post(it.candidate) }
        } }
    }
    private fun homeNearby() {
        refreshAction = { homeNearby() }
        selectedDestination = "Home"; bottomNavigation(); title.text = "Nearby"; reset(); feedTabs(true); composerPrompt()
        screenJob = scope.launch { core?.posts?.watchFeedCandidates()?.collect { candidates ->
            clearFrom(2)
            val nearby = candidates.filter { it.receivedFrom != null || !it.isMine }
            if (nearby.isEmpty()) empty("No nearby posts yet", "When another learner connects, their public educational posts will show here.")
            nearby.forEach(::post)
        } }
    }
    private fun feedTabs(nearbySelected: Boolean) = stream.addView(LinearLayout(this).apply { gravity = Gravity.CENTER; tab("For you", !nearbySelected) { home() }; tab("Nearby", nearbySelected) { homeNearby() } })
    private fun LinearLayout.tab(label: String, selected: Boolean, click: () -> Unit) = addView(TextView(this@MainActivity).apply {
        text = label; gravity = Gravity.CENTER; textSize = 15f; setTypeface(null, if (selected) 1 else 0); setTextColor(if (selected) PRIMARY else SECONDARY)
        setPadding(0, dp(11), 0, dp(11)); background = if (selected) underline(ACCENT) else null; setOnClickListener { click() }; layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
    })
    private fun composerPrompt() = stream.addView(LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(14)); background = underline(DIVIDER)
        addView(profileAvatar(myProfile, "Y", 40, SUCCESS)); addView(TextView(this@MainActivity).apply { text = "What are you learning?"; textSize = 17f; setTextColor(SECONDARY); setPadding(dp(12),0,0,0); setOnClickListener { compose() }; layoutParams = LinearLayout.LayoutParams(0,-2,1f) }); addView(icon("＋", "Create post") { compose() })
    })

    private fun post(candidate: FeedCandidate) = stream.addView(LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(15), dp(16), 0); background = underline(DIVIDER)
        addView(LinearLayout(this@MainActivity).apply {
            gravity = Gravity.TOP; addView(profileAvatar(candidate.author, "N", 42, ACCENT))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(11),0,0,dp(12)); layoutParams = LinearLayout.LayoutParams(0,-2,1f)
                addView(TextView(this@MainActivity).apply { text = "${candidate.author?.displayName ?: "Nearby learner"}  ·  ${if (candidate.isMine) "You" else "Nearby"}"; textSize = 15f; setTypeface(null,1); setTextColor(PRIMARY) })
                addView(TextView(this@MainActivity).apply { text = "Educational post"; textSize = 13f; setTextColor(SECONDARY); setPadding(0,dp(1),0,0) })
                addView(TextView(this@MainActivity).apply { text = candidate.post.body; textSize = 16f; setTextColor(PRIMARY); setLineSpacing(dp(3).toFloat(),1f); setPadding(0,dp(7),0,dp(10)) })
                candidate.post.image?.let { bytes -> addView(ImageView(this@MainActivity).apply { setImageBitmap(BitmapFactory.decodeByteArray(bytes,0,bytes.size)); scaleType = ImageView.ScaleType.CENTER_CROP; background = round(SURFACE_ELEVATED, 14); clipToOutline = true; contentDescription = "Post image"; layoutParams = LinearLayout.LayoutParams(-1,dp(210)) }) }
                addView(LinearLayout(this@MainActivity).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0,dp(13),0,0); action("◯",candidate.commentCount.toString(),"Replies"){replies(candidate.post.id)}; action("♡",candidate.reactionCounts.values.sum().toString(),"React"){react(candidate.post.id)}; action("↗","","Share"){sharePost(candidate.post.body)} })
            })
        })
    })
    private fun LinearLayout.action(symbol: String, count: String, description: String, click: () -> Unit) = addView(TextView(this@MainActivity).apply { text = if(count == "0" || count.isBlank()) symbol else "$symbol  $count"; textSize = 14f; setTextColor(SECONDARY); contentDescription = description; setOnClickListener { click() }; layoutParams = LinearLayout.LayoutParams(0,-2,1f) })

    private fun explore(query: String? = null) {
        refreshAction = { explore(query) }
        selectedDestination = "Explore"; bottomNavigation(); title.text = "Explore"; reset()
        val search = EditText(this).apply { hint="Search posts and topics"; textSize=16f; setTextColor(PRIMARY); setHintTextColor(SECONDARY); setSingleLine(true); background=round(SURFACE_ELEVATED,24); setPadding(dp(17),0,dp(17),0); setText(query ?: ""); setSelection(text.length)
            setOnEditorActionListener { _, _, _ -> explore(text.toString().trim().takeIf { it.isNotEmpty() }); true }
        }
        stream.addView(search, LinearLayout.LayoutParams(-1,dp(46)).apply{setMargins(dp(16),dp(10),dp(16),dp(16))})
        if (query.isNullOrBlank()) { heading("Topics to explore"); listOf("Programming","Data structures","Mathematics","AI & ML").forEach{topic(it){ explore(it) }}; heading("Discover nearby"); empty("Learn together, nearby","Connect with nearby learners to discover their public educational posts.")
        } else {
            heading("Results for “$query”")
            screenJob = scope.launch { core?.posts?.watchFeedCandidates()?.collect { candidates ->
                clearFrom(2)
                val matches = candidates.filter { it.post.body.contains(query, ignoreCase = true) }
                if (matches.isEmpty()) empty("No matching posts", "Try another topic or search term.") else matches.forEach(::post)
            } }
        }
    }
    private fun messages() { refreshAction = { messages() }; selectedDestination="Messages"; bottomNavigation(); title.text="Messages"; reset(); screenJob=scope.launch { val app = core ?: return@launch; combine(app.messages.watchInbox(), app.profiles.watchNearbyPeers()) { inbox, peers -> inbox to peers }.collect { (inbox, peers) -> clearFrom(0); if(inbox.isNotEmpty()) { heading("Messages"); inbox.forEach(::conversation) }; heading("Nearby learners"); if(peers.isEmpty()) empty("No nearby learners yet","Connect with another device nearby, then their profile will appear here so you can start a chat.") else peers.forEach(::nearbyPeer) } } }
    private fun conversation(item: Conversation) = stream.addView(LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL; setPadding(dp(16),dp(15),dp(16),dp(15)); background=underline(DIVIDER); addView(profileAvatar(item.peer, "N", 46, SUCCESS)); addView(LinearLayout(this@MainActivity).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(12),0,0,0); layoutParams=LinearLayout.LayoutParams(0,-2,1f); addView(TextView(this@MainActivity).apply{text=item.peer?.displayName?:"Nearby learner";textSize=16f;setTypeface(null,1);setTextColor(PRIMARY)}); addView(TextView(this@MainActivity).apply{text=item.lastMessage.text;textSize=14f;setTextColor(SECONDARY);maxLines=1}) }); setOnClickListener { chatConversation(item.peerId, item.peer?.displayName ?: "Nearby learner") } })
    private fun nearbyPeer(peer: PeerProfile) = stream.addView(LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL; setPadding(dp(16),dp(14),dp(16),dp(14)); background=underline(DIVIDER); addView(profileAvatar(peer.profile, "N", 46, SUCCESS)); addView(TextView(this@MainActivity).apply { text=peer.profile.displayName ?: "Nearby learner"; textSize=16f; setTypeface(null,1); setTextColor(PRIMARY); setPadding(dp(12),0,0,0); layoutParams=LinearLayout.LayoutParams(0,-2,1f) }); addView(TextView(this@MainActivity).apply { text="Chat"; textSize=14f; setTypeface(null,1); setTextColor(ACCENT); setPadding(dp(12),dp(8),dp(12),dp(8)); background=round(SURFACE_ELEVATED,18); setOnClickListener { chatConversation(peer.userId, peer.profile.displayName ?: "Nearby learner") } }) })
    private fun chatConversation(peerId: String, peerName: String) {
        refreshAction = { chatConversation(peerId, peerName) }
        title.text = peerName
        reset()

        val thread = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        stream.addView(thread)
        val input = EditText(this).apply {
            hint = "Write a message"
            textSize = 16f
            setTextColor(PRIMARY)
            setHintTextColor(SECONDARY)
            background = round(SURFACE_ELEVATED, 22)
            setPadding(dp(16), 0, dp(12), 0)
            setSingleLine(true)
            layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f)
        }
        val send = TextView(this).apply {
            text = "Send"
            textSize = 15f
            setTypeface(null, 1)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = round(ACCENT, 22)
            layoutParams = LinearLayout.LayoutParams(dp(70), dp(46)).apply { setMargins(dp(8), 0, 0, 0) }
            setOnClickListener {
                val message = input.text.toString().trim()
                if (message.isNotEmpty()) sendMessage(peerId, message, input)
            }
        }
        stream.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(16))
            addView(input)
            addView(send)
        })

        screenJob = scope.launch {
            core?.messages?.watchConversation(peerId)?.collect { messages ->
                thread.removeAllViews()
                if (messages.isEmpty()) {
                    thread.addView(TextView(this@MainActivity).apply {
                        text = "Start your encrypted conversation with $peerName."
                        textSize = 14f
                        setTextColor(SECONDARY)
                        setPadding(dp(20), dp(28), dp(20), dp(20))
                    })
                }
                messages.forEach { message ->
                    thread.addView(TextView(this@MainActivity).apply {
                        text = message.text
                        textSize = 16f
                        setTextColor(PRIMARY)
                        background = round(if (message.isMine) ACCENT else SURFACE_ELEVATED, 18)
                        setPadding(dp(14), dp(10), dp(14), dp(10))
                        gravity = if (message.isMine) Gravity.END else Gravity.START
                        layoutParams = LinearLayout.LayoutParams(-2, -2).apply {
                            gravity = if (message.isMine) Gravity.END else Gravity.START
                            setMargins(dp(16), dp(5), dp(16), dp(5))
                        }
                    })
                }
            }
        }
    }

    private fun sendMessage(peerId: String, message: String, input: EditText) {
        scope.launch(Dispatchers.IO) {
            runCatching { checkNotNull(core) { "Proximity is still starting" }.messages.send(peerId, message) }
                .onSuccess { withContext(Dispatchers.Main) { input.setText("") } }
                .onFailure { error ->
                    withContext(Dispatchers.Main) {
                        popup("Message wasn't sent", error.message ?: "This learner is no longer available.")
                    }
                }
        }
    }
    private fun profile() { refreshAction = { profile() }; selectedDestination="Profile"; bottomNavigation(); title.text="Profile"; reset(); screenJob=scope.launch { val app = core ?: return@launch; combine(app.profiles.watchMyProfile(), app.posts.watchFeedCandidates()) { me, posts -> me to posts }.collect { (me, posts) -> clearFrom(0); profileHeader(me); heading("Posts"); val mine = posts.filter { it.isMine }; if(mine.isEmpty()) empty("Your posts will appear here","Share what you are learning with people nearby.") else mine.forEach(::post) } } }
    private fun profileHeader(profile: Profile) = stream.addView(LinearLayout(this).apply { orientation=LinearLayout.VERTICAL
        addView(View(this@MainActivity).apply { setBackgroundColor(SURFACE_ELEVATED); layoutParams=LinearLayout.LayoutParams(-1,dp(120)) })
        addView(LinearLayout(this@MainActivity).apply { gravity=Gravity.CENTER_VERTICAL; setPadding(dp(16),dp(12),dp(16),0); addView(profileAvatar(profile,"Y",72,ACCENT)); addView(Space(this@MainActivity),LinearLayout.LayoutParams(0,1,1f)); addView(TextView(this@MainActivity).apply{text="Edit profile";textSize=14f;setTypeface(null,1);setTextColor(PRIMARY);gravity=Gravity.CENTER;background=round(BLACK,22,DIVIDER);setOnClickListener{ editProfile(profile) };layoutParams=LinearLayout.LayoutParams(dp(112),dp(42))}) })
        addView(TextView(this@MainActivity).apply{text=profile.displayName?:"Your name";textSize=20f;setTypeface(null,1);setTextColor(PRIMARY);setPadding(dp(16),dp(12),0,0)}); addView(TextView(this@MainActivity).apply{text="@${profile.publicKey.take(10)}";textSize=14f;setTextColor(SECONDARY);setPadding(dp(16),dp(3),0,dp(15))})
    })

    private fun compose() { val text=EditText(this).apply{hint="Explain a concept, ask a study question, or share notes";minLines=5;textSize=18f;setTextColor(PRIMARY);setHintTextColor(SECONDARY);setBackgroundColor(Color.TRANSPARENT)}; val hint=TextView(this).apply{this.text="Tip: name the topic and use context, e.g. “Kotlin coroutines tutorial: how does structured concurrency work?”";textSize=13f;setTextColor(SECONDARY);setPadding(dp(16),0,dp(16),dp(8))}; val photo=TextView(this).apply{this.text=if(pendingImage==null)"▧  Add photo" else "✓ Photo attached";textSize=15f;setTextColor(ACCENT);setPadding(dp(16),dp(8),0,dp(10));setOnClickListener{startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply{type="image/*";addCategory(Intent.CATEGORY_OPENABLE)},PICK_IMAGE)}}; AlertDialog.Builder(this).setTitle("Create post").setView(LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(18),dp(12),0);addView(text);addView(hint);addView(photo)}).setNegativeButton("Cancel",null).setPositiveButton("Post"){_,_->scope.launch(Dispatchers.IO){runCatching{core?.posts?.create(text.text.toString(),pendingImage);pendingImage=null}.onFailure{withContext(Dispatchers.Main){popup("Post wasn’t published",it.message?:"Only approved educational posts can be shared.")}}}}.show() }
    private fun react(id:String){scope.launch(Dispatchers.IO){core?.reactions?.add(id,"like")}}
    private fun sharePost(body: String) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, body)
        }, "Share post"))
    }
    private fun reply(id:String){val input=EditText(this).apply{hint="Post your reply"};AlertDialog.Builder(this).setTitle("Reply").setView(input).setNegativeButton("Cancel",null).setPositiveButton("Reply"){_,_->scope.launch(Dispatchers.IO){core?.comments?.add(id,input.text.toString())}}.show()}
    private fun replies(postId: String) { val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(4), dp(16), dp(4)) }; val dialog = AlertDialog.Builder(this).setTitle("Replies").setView(ScrollView(this).apply { addView(rows) }).setNegativeButton("Close", null).setPositiveButton("Reply") { _, _ -> reply(postId) }.create(); val job = scope.launch { core?.comments?.watchForPost(postId)?.collect { comments -> rows.removeAllViews(); if (comments.isEmpty()) rows.addView(TextView(this@MainActivity).apply { text="No replies yet. Start the discussion."; textSize=15f; setTextColor(SECONDARY); setPadding(0,dp(20),0,dp(20)) }); comments.forEach { view -> rows.addView(LinearLayout(this@MainActivity).apply { orientation=LinearLayout.VERTICAL; setPadding(0,dp(12),0,dp(12)); background=underline(DIVIDER); addView(TextView(this@MainActivity).apply { text=view.author?.displayName ?: "Nearby learner"; textSize=14f; setTypeface(null,1); setTextColor(PRIMARY) }); addView(TextView(this@MainActivity).apply { text=view.comment.body; textSize=15f; setTextColor(PRIMARY); setPadding(0,dp(4),0,0) }) }) } } }; dialog.setOnDismissListener { job.cancel() }; dialog.show() }
    private fun editProfile(profile: Profile) { pendingProfileAvatar = null; val input = EditText(this).apply { hint="Display name"; setText(profile.displayName ?: "") }; val photo=TextView(this).apply { text="▧  Change profile photo"; textSize=16f; setTextColor(ACCENT); setPadding(dp(8),dp(14),dp(8),dp(12)); setOnClickListener { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type="image/*"; addCategory(Intent.CATEGORY_OPENABLE) }, PICK_PROFILE_IMAGE) } }; AlertDialog.Builder(this).setTitle("Edit profile").setView(LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(16),0,dp(16),0); addView(input); addView(photo) }).setNegativeButton("Cancel",null).setPositiveButton("Save") { _, _ -> scope.launch(Dispatchers.IO) { runCatching { core?.profiles?.updateMyProfile(displayName = input.text.toString().trim().takeIf { it.isNotEmpty() }, avatar = pendingProfileAvatar) }.onSuccess { updated -> myProfile=updated; withContext(Dispatchers.Main) { if(selectedDestination == "Home") refreshAction() } }.onFailure { withContext(Dispatchers.Main) { popup("Couldn’t update profile", it.message ?: "Please try again.") } } } }.show() }

    private suspend fun watchIncomingMessageNotifications(app: AppCore) {
        var initialSnapshot = true
        app.messages.watchInbox().collect { conversations ->
            val received = conversations.filter { it.lastMessage.status == MessageStatus.RECEIVED }
            if (initialSnapshot) {
                notifiedIncomingMessageIds += received.map { it.lastMessage.id }
                initialSnapshot = false
            } else {
                received.filter { notifiedIncomingMessageIds.add(it.lastMessage.id) }.forEach(::showMessageNotification)
            }
        }
    }

    private fun createMessageNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(MESSAGE_CHANNEL, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Notifications for new nearby messages"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun showMessageNotification(conversation: Conversation) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val openApp = PendingIntent.getActivity(
            this,
            conversation.lastMessage.id.hashCode(),
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, MESSAGE_CHANNEL)
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setContentTitle(conversation.peer?.displayName ?: "New message")
            .setContentText(conversation.lastMessage.text)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(conversation.lastMessage.id.hashCode(), notification)
    }

    private fun errorState(error:Throwable){title.text="Proximity";reset();empty("Couldn’t start Proximity","${error.javaClass.simpleName}: ${error.message?:"Unknown local error"}")}
    private fun requestNearbyPermissions() {
        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
                add(Manifest.permission.POST_NOTIFICATIONS)
                add(Manifest.permission.READ_MEDIA_IMAGES)
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (permissions.isNotEmpty()) requestPermissions(permissions.toTypedArray(), NEARBY_PERMISSION_REQUEST)
        else startCore()
    }
    private fun hasNearbyPermissions(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT).all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED } else checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    private fun requestOptionalPermissionsIfNeeded() {
        val permissions = buildList {
            add(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (permissions.isNotEmpty()) requestPermissions(permissions.toTypedArray(), OPTIONAL_PERMISSION_REQUEST)
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) { super.onRequestPermissionsResult(requestCode, permissions, grantResults); if (requestCode == NEARBY_PERMISSION_REQUEST) { if (hasNearbyPermissions()) startCore() else popup("Nearby permission needed", "Allow Nearby devices permissions to discover nearby learners and sync posts or chats.") } }
    private fun reset(){screenJob?.cancel();stream.removeAllViews()}; private fun clearFrom(index:Int){while(stream.childCount>index)stream.removeViewAt(index)}
    private fun heading(value:String)=stream.addView(TextView(this).apply{text=value;textSize=20f;setTypeface(null,1);setTextColor(PRIMARY);setPadding(dp(16),dp(12),0,dp(12))})
    private fun topic(value:String, click: () -> Unit)=stream.addView(TextView(this).apply{text=value;textSize=16f;setTextColor(PRIMARY);setPadding(dp(16),dp(15),dp(16),dp(15));background=underline(DIVIDER);setOnClickListener{click()}})
    private fun empty(head:String,body:String)=stream.addView(LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding(dp(36),dp(66),dp(36),dp(30));addView(TextView(this@MainActivity).apply{text=head;textSize=20f;setTypeface(null,1);gravity=Gravity.CENTER;setTextColor(PRIMARY)});addView(TextView(this@MainActivity).apply{text=body;textSize=15f;gravity=Gravity.CENTER;setTextColor(SECONDARY);setLineSpacing(dp(3).toFloat(),1f);setPadding(0,dp(10),0,0)})})
    private fun avatar(letter:String,size:Int,color:Int)=TextView(this).apply{text=letter.uppercase();gravity=Gravity.CENTER;textSize=size/2.6f;setTypeface(null,1);setTextColor(Color.WHITE);background=round(color,size/2);layoutParams=LinearLayout.LayoutParams(dp(size),dp(size))}
    private fun profileAvatar(profile: Profile?, fallback: String, size: Int, color: Int): View = profile?.avatar?.let { bytes -> ImageView(this).apply { setImageBitmap(BitmapFactory.decodeByteArray(bytes,0,bytes.size)); scaleType=ImageView.ScaleType.CENTER_CROP; background=round(color,size/2); clipToOutline=true; contentDescription="Profile photo"; layoutParams=LinearLayout.LayoutParams(dp(size),dp(size)) } } ?: avatar(profile?.displayName?.takeIf { !it.isNullOrBlank() }?.take(1) ?: fallback, size, color)
    private fun icon(symbol:String,description:String,click:()->Unit)=TextView(this).apply{text=symbol;textSize=25f;gravity=Gravity.CENTER;setTextColor(PRIMARY);contentDescription=description;setOnClickListener{click()};layoutParams=LinearLayout.LayoutParams(dp(42),dp(42))}
    private fun underline(color:Int) = LayerDrawable(arrayOf(
        GradientDrawable().apply { setColor(Color.TRANSPARENT) },
        GradientDrawable().apply { setColor(color) },
    )).apply {
        setLayerGravity(1, Gravity.BOTTOM)
        setLayerHeight(1, dp(1))
    }
    private fun round(color:Int,radius:Int,stroke:Int?=null)=GradientDrawable().apply{setColor(color);cornerRadius=dp(radius).toFloat();stroke?.let{setStroke(dp(1),it)}}
    private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt(); private fun toast(value:String)=Toast.makeText(this,value,Toast.LENGTH_SHORT).show()
    private fun popup(title: String, message: String) { AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("OK", null).show() }
    override fun onActivityResult(code:Int,result:Int,data:Intent?){super.onActivityResult(code,result,data);if(result!=RESULT_OK)return; when(code) { PICK_IMAGE -> pendingImage=data?.data?.let(::compress); PICK_PROFILE_IMAGE -> { pendingProfileAvatar=data?.data?.let(::compressAvatar); if(pendingProfileAvatar == null) popup("Couldn’t use that photo", "Please choose another image.") } }}
    private fun compress(uri:Uri):ByteArray?=contentResolver.openInputStream(uri)?.use{BitmapFactory.decodeStream(it)}?.let{bitmap->ByteArrayOutputStream().use{out->bitmap.compress(Bitmap.CompressFormat.JPEG,75,out);out.toByteArray().takeIf{it.size<=256*1024}}}
    private fun compressAvatar(uri: Uri): ByteArray? = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }?.let { source ->
        val side = minOf(source.width, source.height)
        if (side <= 0) return@let null
        val crop = Bitmap.createBitmap(source, (source.width - side) / 2, (source.height - side) / 2, side, side)
        for (edge in listOf(256, 192, 144, 108, 80)) {
            val scaled = Bitmap.createScaledBitmap(crop, edge, edge, true)
            for (quality in listOf(85, 75, 65, 55, 45, 35)) {
                val encoded = ByteArrayOutputStream().use { out ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
                    out.toByteArray()
                }
                if (encoded.size <= MAX_AVATAR_BYTES) return@let encoded
            }
        }
        null
    }
    override fun onDestroy(){screenJob?.cancel();messageNotificationJob?.cancel();scope.cancel();runBlocking{core?.close()};nearbyTransport?.close();super.onDestroy()}
    private companion object { const val PICK_IMAGE=41; const val NEARBY_PERMISSION_REQUEST=42; const val PICK_PROFILE_IMAGE=43; const val OPTIONAL_PERMISSION_REQUEST=44; const val MESSAGE_CHANNEL="messages"; const val MAX_AVATAR_BYTES=64*1024; val BLACK=Color.BLACK; val SURFACE_ELEVATED=Color.rgb(22,24,28); val PRIMARY=Color.rgb(231,233,234); val SECONDARY=Color.rgb(113,118,123); val DIVIDER=Color.rgb(47,51,54); val ACCENT=Color.rgb(29,155,240); val SUCCESS=Color.rgb(0,186,124) }
}

private class PreferenceKeyStore(private val preferences:SharedPreferences):KeyStore{override fun read(key:String)=preferences.getString(key,null)?.let{android.util.Base64.decode(it,android.util.Base64.NO_WRAP)};override fun write(key:String,value:ByteArray){preferences.edit().putString(key,android.util.Base64.encodeToString(value,android.util.Base64.NO_WRAP)).apply()}}
