package com.cabgon.blackhawk.ui.gallery

import android.os.Bundle
import android.content.Intent
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.cabgon.blackhawk.databinding.ActivityGalleryVideoFullscreenBinding
import com.cabgon.blackhawk.util.SessionFlags
import com.cabgon.blackhawk.ui.StartupActivity
import com.google.firebase.auth.FirebaseAuth

@OptIn(UnstableApi::class)
class GalleryVideoFullscreenActivity : AppCompatActivity() {

    private lateinit var b: ActivityGalleryVideoFullscreenBinding
    private var player: ExoPlayer? = null

    private var startPositionMs: Long = 0L
    private var startPlayWhenReady: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        val authenticated = SessionFlags.loginCompleted && FirebaseAuth.getInstance().currentUser != null
        super.onCreate(savedInstanceState.takeIf { authenticated })
        if (!authenticated) {
            startActivity(Intent(this, StartupActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
            finish()
            return
        }
        b = ActivityGalleryVideoFullscreenBinding.inflate(layoutInflater)
        setContentView(b.root)

        enterImmersiveMode()

        val url = intent.getStringExtra(EXTRA_VIDEO_URL).orEmpty()
        if (url.isBlank()) {
            Toast.makeText(this, "No se encontró el video.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        startPositionMs = intent.getLongExtra(EXTRA_POSITION_MS, 0L)
        startPlayWhenReady = intent.getBooleanExtra(EXTRA_PLAY_WHEN_READY, false)

        b.btnClose.setOnClickListener { finish() }

        b.playerView.apply {
            setUseController(true)
            controllerAutoShow = true
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        }

        val exo = ExoPlayer.Builder(this).build().also { created ->
            b.playerView.player = created
            created.setMediaItem(MediaItem.fromUri(url))
            created.prepare()
            if (startPositionMs > 0L) {
                created.seekTo(startPositionMs)
            }
            created.playWhenReady = startPlayWhenReady
        }
        player = exo
    }

    private fun enterImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    override fun onStop() {
        releasePlayer()
        super.onStop()
    }

    override fun onDestroy() {
        releasePlayer()
        super.onDestroy()
    }

    private fun releasePlayer() {
        if (::b.isInitialized) b.playerView.player = null
        player?.release()
        player = null
    }

    companion object {
        const val EXTRA_VIDEO_URL = "video_url"
        const val EXTRA_POSITION_MS = "position_ms"
        const val EXTRA_PLAY_WHEN_READY = "play_when_ready"
    }
}
