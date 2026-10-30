package com.proximity.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.proximity.contracts.*
import com.proximity.core.AppCore
import com.proximity.core.identity.KeyStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.ByteArrayOutputStream
import java.time.Instant

/** A compact, original social UI that sits over the existing AppCore APIs. */
class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var feed: LinearLayout
    private lateinit var title: TextView
    private var core: AppCore? = null
    private var pendingImage: ByteArray? = null
    private val pickImage = 41

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildShell())
        scope.launch(Dispatchers.IO) {
            try {
                val opened = AppCore.open(
                    transport = PhoneTransport(),
                    keyStore = PreferenceKeyStore(getSharedPreferences("identity", MODE_PRIVATE)),
                    driver = BundledSQLiteDriver(),
                    databasePath = getDatabasePath("proximity.db").path,
                )
                opened.start()
                core = opened
                withContext(Dispatchers.Main) { showFeed() }
            } catch (error: Throwable) {
                withContext(Dispatchers.Main) { showStartupError(error) }
            }
        }
    }

    private fun buildShell(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.rgb(250, 249, 247))
        addView(LinearLayout(this@MainActivity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(12))
            addView(TextView(this@MainActivity).apply {
                text = "P"
                textSize = 19f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
                background = pill(Color.rgb(91, 80, 248)); layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
            })
            title = TextView(this@MainActivity).apply {
                text = "Home"; textSize = 22f; setTextColor(Color.rgb(17, 19, 24)); setTypeface(null, 1)
                setPadding(dp(12), 0, 0, 0); layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }
            addView(TextView(this@MainActivity).apply { text = "⌕"; textSize = 30f; setTextColor(Color.rgb(17,19,24)) })
        })
        addView(TextView(this@MainActivity).apply {
            text = "For you                                      Nearby"
            setTextColor(Color.rgb(91,80,248)); textSize = 15f; setPadding(dp(28), dp(5), dp(28), dp(12))
        })
        addView(ScrollView(this@MainActivity).apply {
            addView(LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; feed = this })
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        })
        addView(LinearLayout(this@MainActivity).apply {
            gravity = Gravity.CENTER; setPadding(dp(12), dp(10), dp(12), dp(10)); setBackgroundColor(Color.WHITE)
            nav("⌂\nHome") { showFeed() }; nav("⌕\nExplore") { showExplore() }; nav("＋\nPost") { showComposer() }
            nav("✉\nInbox") { showInbox() }; nav("◎\nYou") { showProfile() }
        })
    }

    private fun LinearLayout.nav(label: String, action: () -> Unit) { addView(TextView(this@MainActivity).apply {
        text = label; gravity = Gravity.CENTER; textSize = 12f; setTextColor(Color.rgb(75,80,91)); setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
    }) }

    private fun showFeed() {
        title.text = "Home"; feed.removeAllViews(); composerTeaser()
        scope.launch {
            core?.watchRankedEducationalFeed()?.collect { ranked ->
                feed.removeViews(1, (feed.childCount - 1).coerceAtLeast(0))
                if (ranked.isEmpty()) empty("Your neighborhood feed is quiet", "Share a helpful note to start the conversation.")
                ranked.forEach { feedPost(it.candidate) }
            }
        }
    }

    /** A startup dependency must not take down the activity before the user can see what happened. */
    private fun showStartupError(error: Throwable) {
        title.text = "Proximity"
        feed.removeAllViews()
        empty(
            "Couldn’t start local storage",
            "${error.javaClass.simpleName}: ${error.message ?: "Unknown startup error"}",
        )
    }

    private fun composerTeaser() = feed.addView(LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(14), dp(20), dp(14)); setBackgroundColor(Color.WHITE)
        addView(TextView(this@MainActivity).apply { text = "Share something useful with people nearby"; textSize = 16f; setTextColor(Color.rgb(104,112,125)); setOnClickListener { showComposer() } })
        addView(TextView(this@MainActivity).apply { text = "◫  Add photo                                      Post"; textSize = 14f; setTextColor(Color.rgb(91,80,248)); setPadding(0,dp(16),0,0); setOnClickListener { showComposer() } })
    })

    private fun feedPost(candidate: FeedCandidate) = feed.addView(LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(18), dp(20), dp(15)); setBackgroundColor(Color.WHITE)
        addView(TextView(this@MainActivity).apply { text = "${candidate.author?.displayName ?: "Nearby learner"}  ·  ${if (candidate.isMine) "You" else "Nearby"}"; textSize = 15f; setTypeface(null,1); setTextColor(Color.rgb(17,19,24)) })
        addView(TextView(this@MainActivity).apply { text = candidate.post.body; textSize = 17f; setTextColor(Color.rgb(30,34,40)); setPadding(0,dp(7),0,dp(10)) })
        candidate.post.image?.let { bytes -> addView(ImageView(this@MainActivity).apply { setImageBitmap(BitmapFactory.decodeByteArray(bytes,0,bytes.size)); scaleType = ImageView.ScaleType.CENTER_CROP; background = pill(Color.rgb(238,237,249)); layoutParams = LinearLayout.LayoutParams(-1,dp(210)) }) }
        addView(LinearLayout(this@MainActivity).apply { setPadding(0,dp(13),0,0)
            action("♡  ${candidate.reactionCounts.values.sum()}") { react(candidate.post.id) }
            action("▢  ${candidate.commentCount}") { comment(candidate.post.id) }
            action("↗  Share") { toast("Ready to share nearby") }
        })
        addView(View(this@MainActivity).apply { setBackgroundColor(Color.rgb(233,231,226)); layoutParams = LinearLayout.LayoutParams(-1,dp(1)).apply { topMargin = dp(15) } })
    })

    private fun LinearLayout.action(label: String, onClick: () -> Unit) = addView(TextView(this@MainActivity).apply { text=label; textSize=14f; setTextColor(Color.rgb(104,112,125)); setOnClickListener { onClick() }; layoutParams=LinearLayout.LayoutParams(0,-2,1f) })
    private fun empty(head: String, body: String) = feed.addView(TextView(this).apply { text="$head\n\n$body"; gravity=Gravity.CENTER; textSize=16f; setTextColor(Color.rgb(104,112,125)); setPadding(dp(40),dp(80),dp(40),0) })

    private fun showComposer() {
        val input = EditText(this).apply { hint="What are you learning or sharing?"; minLines=5; background= pill(Color.rgb(244,243,241)); setPadding(dp(16),dp(14),dp(16),dp(14)) }
        val image = TextView(this).apply { text = if (pendingImage == null) "◫  Add an image" else "✓ Image attached"; setTextColor(Color.rgb(91,80,248)); textSize=15f; setPadding(0,dp(18),0,0); setOnClickListener { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type="image/*"; addCategory(Intent.CATEGORY_OPENABLE) }, pickImage) } }
        AlertDialog.Builder(this).setTitle("Create post").setView(LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(24),0,dp(24),0); addView(input); addView(image) })
            .setNegativeButton("Cancel", null).setPositiveButton("Post") { _, _ -> scope.launch(Dispatchers.IO) { runCatching { core?.posts?.create(input.text.toString(), pendingImage); pendingImage=null }.onFailure { withContext(Dispatchers.Main) { toast(it.message ?: "Couldn't post") } } } }.show()
    }

    private fun react(id: String) { scope.launch(Dispatchers.IO) { core?.reactions?.add(id,"like") } }
    private fun comment(id: String) { val input=EditText(this).apply { hint="Write a reply" }; AlertDialog.Builder(this).setTitle("Reply").setView(input).setPositiveButton("Reply") {_,_-> scope.launch(Dispatchers.IO){ core?.comments?.add(id,input.text.toString()) }}.setNegativeButton("Cancel",null).show() }
    private fun showExplore() { title.text="Explore"; feed.removeAllViews(); empty("Learn together, nearby", "Posts here are filtered to educational content and ranked locally.") }
    private fun showInbox() { title.text="Inbox"; feed.removeAllViews(); empty("Your conversations", "Messages appear here after you connect with a nearby peer.") }
    private fun showProfile() { title.text="Your profile"; feed.removeAllViews(); empty("Your local identity", "Your posts, interests, and direct messages stay on your device.") }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) { super.onActivityResult(requestCode,resultCode,data); if (requestCode==pickImage && resultCode==RESULT_OK) pendingImage=data?.data?.let(::compressedImage) }
    private fun compressedImage(uri: Uri): ByteArray? = contentResolver.openInputStream(uri)?.use { stream -> BitmapFactory.decodeStream(stream) }?.let { bitmap -> ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 75, out); out.toByteArray().takeIf { it.size <= 256*1024 } } }
    private fun pill(color:Int)=GradientDrawable().apply { setColor(color); cornerRadius=dp(18).toFloat() }
    private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
    private fun toast(text:String)=runOnUiThread { Toast.makeText(this,text,Toast.LENGTH_SHORT).show() }
    override fun onDestroy() { scope.cancel(); runBlocking { core?.close() }; super.onDestroy() }
}

private class PreferenceKeyStore(private val preferences: SharedPreferences) : KeyStore {
    override fun read(key: String): ByteArray? = preferences.getString(key, null)?.let {
        android.util.Base64.decode(it, android.util.Base64.NO_WRAP)
    }
    override fun write(key: String, value: ByteArray) { preferences.edit().putString(key, android.util.Base64.encodeToString(value, android.util.Base64.NO_WRAP)).apply() }
}

/** Single-device transport keeps AppCore functional while the real Nearby driver is completed. */
private class PhoneTransport : Transport {
    override val peers = MutableStateFlow<List<TransportPeer>>(emptyList())
    override val onReceive = MutableSharedFlow<ReceivedPayload>()
    override val onPeerConnected = MutableSharedFlow<TransportPeer>()
    override val onPeerLost = MutableSharedFlow<String>()
    override suspend fun start() = Unit
    override suspend fun stop() = Unit
    override suspend fun send(peerId: String, bytes: ByteArray) = false
}
