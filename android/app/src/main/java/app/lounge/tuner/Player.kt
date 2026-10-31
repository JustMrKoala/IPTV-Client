package app.lounge.tuner

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import org.json.JSONObject
import java.util.Locale

/** One press of Play: what to play, and where to save how far it got. */
class Session(val url: String, val key: String, val pos: Double, val meta: JSONObject) {
    @Volatile
    var started = false
}

/** What mpv is on the Linux version: the one player, shared by the TV interface and the phone remote. */
object Playback {
    private var activity: PlayerActivity? = null
    private var current: Session? = null

    @Synchronized
    fun playing() = current != null

    @Synchronized
    fun started() = current?.started == true

    /** Returns false when this address is already playing, so a second press does not start it again. */
    fun play(url: String, key: String, pos: Double, meta: JSONObject): Boolean {
        require(url.startsWith("http://") || url.startsWith("https://")) { "unsupported address" }
        val session = Session(url, key, pos, meta)
        synchronized(this) {
            if (current?.url == url) return false
            current = session
            val shown = activity
            if (shown == null) {
                Tuner.app.startActivity(
                    Intent(Tuner.app, PlayerActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                )
            } else {
                shown.runOnUiThread { shown.load(session) }
            }
            return true
        }
    }

    fun stop() {
        val shown: PlayerActivity?
        synchronized(this) {
            current = null
            shown = activity
            activity = null
        }
        shown?.runOnUiThread { shown.finish() }
    }

    fun command(cmd: String) = synchronized(this) { activity }?.let { it.runOnUiThread { it.control(cmd) } }

    fun osd(text: String) = synchronized(this) { activity }?.let { it.runOnUiThread { it.flash(text) } }

    @Synchronized
    fun attach(player: PlayerActivity): Session? {
        activity = player
        return current
    }

    @Synchronized
    fun detach(player: PlayerActivity) {
        if (activity === player) activity = null
    }

    fun begun(session: Session) {
        val now = synchronized(this) {
            session.started = true
            current === session
        }
        if (now) Events.broadcast("started")
    }

    fun ended(session: Session, failed: Boolean) {
        val now = synchronized(this) { (current === session).also { if (it) current = null } }
        if (now) Events.broadcast(if (failed && !session.started) "failed" else "stopped")
    }
}

/** The few words the player shows itself, in the language chosen in Tuner. */
private object Words {
    private val T = mapOf(
        "nl" to listOf("Audio en ondertiteling", "Audio", "Ondertiteling", "Uit", "Spoor {n}"),
        "de" to listOf("Audio & Untertitel", "Audio", "Untertitel", "Aus", "Spur {n}"),
        "fr" to listOf("Audio et sous-titres", "Audio", "Sous-titres", "Désactivés", "Piste {n}"),
        "es" to listOf("Audio y subtítulos", "Audio", "Subtítulos", "Desactivados", "Pista {n}"),
        "it" to listOf("Audio e sottotitoli", "Audio", "Sottotitoli", "Disattivati", "Traccia {n}"),
        "pt" to listOf("Áudio e legendas", "Áudio", "Legendas", "Desativadas", "Faixa {n}"),
        "tr" to listOf("Ses ve altyazı", "Ses", "Altyazı", "Kapalı", "Parça {n}"),
        "pl" to listOf("Audio i napisy", "Audio", "Napisy", "Wyłączone", "Ścieżka {n}"),
        "el" to listOf("Ήχος και υπότιτλοι", "Ήχος", "Υπότιτλοι", "Απενεργοποιημένοι", "Κομμάτι {n}"),
    )
    private val EN = listOf("Audio & subtitles", "Audio", "Subtitles", "Off", "Track {n}")

    val lang get() = State.read { it.s("lang") }.ifEmpty { "en" }

    fun tr(text: String) = (T[lang] ?: EN)[EN.indexOf(text)]
}

@OptIn(UnstableApi::class)
class PlayerActivity : Activity() {
    private lateinit var player: ExoPlayer
    private lateinit var video: PlayerView
    private lateinit var controls: View
    private lateinit var title: TextView
    private lateinit var seek: SeekBar
    private lateinit var time: TextView
    private lateinit var live: TextView
    private lateinit var playPause: ImageButton
    private lateinit var rewind: View
    private lateinit var forward: View
    private lateinit var tracks: TextView
    private lateinit var spinner: ProgressBar
    private lateinit var osd: TextView
    private lateinit var panel: View
    private lateinit var panelList: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private var session: Session? = null
    private var failed = false
    private var triedHls = false
    private var ticks = 0
    private var shown = false
    private var dragging = false
    // Where a scrub is headed while the arrows are pressed. Like Netflix, the video only jumps once they stop.
    private var target: Long? = null
    private val doSeek = Runnable {
        target?.let { player.seekTo(it) }
        target = null
    }
    private val hideOsd = Runnable { osd.visibility = View.GONE }
    private val hideControls = Runnable { if (player.playWhenReady && panel.visibility != View.VISIBLE) setControls(false) }

    // Moves the timeline along and saves the resume point every 5 seconds, like the Linux version does through mpv
    private val tick = object : Runnable {
        override fun run() {
            ticks++
            if (shown) update()
            if (ticks % 10 == 0) session?.let(::save)
            handler.postDelayed(this, 500)
        }
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            val s = session ?: return
            spinner.visibility = if (state == Player.STATE_BUFFERING || (state == Player.STATE_IDLE && !s.started)) View.VISIBLE else View.GONE
            if (state == Player.STATE_READY && !s.started) {
                Playback.begun(s)
                // The timeline and buttons depend on whether this is live, which is only known now
                update()
                if (shown && (currentFocus == null || currentFocus === playPause)) defaultFocus().requestFocus()
                scheduleHide()
            }
            if (state == Player.STATE_ENDED) quit(!s.started)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) = update()

        override fun onTracksChanged(tracks: Tracks) = update()

        override fun onPlayerError(error: PlaybackException) {
            val s = session ?: return
            // A live channel that fell too far behind picks up again at the live edge
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                player.seekToDefaultPosition()
                player.prepare()
                return
            }
            // Many IPTV addresses end in .ts or nothing at all but serve an HLS playlist
            if (error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED && !triedHls) {
                triedHls = true
                player.setMediaItem(MediaItem.Builder().setUri(s.url).setMimeType(MimeTypes.APPLICATION_M3U8).build())
                player.prepare()
                return
            }
            Log.w(TAG, "playback failed: ${error.errorCodeName}")
            quit(true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("Lounge/0.1")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20000)
            .setReadTimeoutMs(20000)
        val extractors = DefaultExtractorsFactory().setTsExtractorFlags(
            DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS,
        )
        player = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(http, extractors)).build()
        player.addListener(listener)
        setContentView(R.layout.player)
        fullscreen()

        video = findViewById(R.id.video)
        video.player = player
        video.keepScreenOn = true
        controls = findViewById(R.id.controls)
        title = findViewById(R.id.title)
        seek = findViewById(R.id.seek)
        time = findViewById(R.id.time)
        live = findViewById(R.id.live)
        playPause = findViewById(R.id.playPause)
        rewind = findViewById(R.id.rewind)
        forward = findViewById(R.id.forward)
        tracks = findViewById(R.id.tracks)
        spinner = findViewById(R.id.spinner)
        osd = findViewById(R.id.osd)
        panel = findViewById(R.id.panel)
        panelList = findViewById(R.id.panelList)
        tracks.text = Words.tr("Audio & subtitles")

        findViewById<View>(R.id.back).setOnClickListener { quit(false) }
        playPause.setOnClickListener { togglePause() }
        rewind.setOnClickListener { seekBy(-10000) }
        forward.setOnClickListener { seekBy(10000) }
        tracks.setOnClickListener { openPanel() }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                target = progress.toLong()
                showTime()
                handler.removeCallbacks(doSeek)
                if (!dragging) handler.postDelayed(doSeek, 600)
                scheduleHide()
            }

            override fun onStartTrackingTouch(bar: SeekBar) {
                dragging = true
                handler.removeCallbacks(hideControls)
            }

            override fun onStopTrackingTouch(bar: SeekBar) {
                dragging = false
                doSeek.run()
                scheduleHide()
            }
        })
        // On a phone: a tap shows or hides the controls, and a double tap on either side skips 10 seconds
        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (panel.visibility == View.VISIBLE) closePanel() else setControls(!shown)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val third = video.width / 3
                when {
                    e.x < third -> seekBy(-10000)
                    e.x > 2 * third -> seekBy(10000)
                    else -> togglePause()
                }
                return true
            }
        })
        video.setOnTouchListener { _, e -> gestures.onTouchEvent(e) }

        val first = Playback.attach(this)
        if (first == null) {
            finish()
        } else {
            load(first)
            handler.postDelayed(tick, 500)
        }
    }

    @Suppress("DEPRECATION")
    private fun fullscreen() {
        // Phones hide their status and navigation bars while a video plays; a swipe from the edge brings them back
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) fullscreen()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Playback.attach(this)?.let(::load)
    }

    fun load(next: Session) {
        if (next === session || isFinishing) return
        session?.let(::save)
        session = next
        triedHls = false
        target = null
        val item = MediaItem.fromUri(next.url)
        if (next.pos > 30) player.setMediaItem(item, (next.pos * 1000).toLong()) else player.setMediaItem(item)
        player.prepare()
        player.playWhenReady = true
        title.text = next.meta.s("name")
        spinner.visibility = View.VISIBLE
        closePanel()
        // The title shows while the video opens, then the controls fade away once it plays
        setControls(true)
    }

    private fun save(s: Session) {
        val dur = player.duration
        if (!s.started || dur == C.TIME_UNSET) return
        Tuner.saveProgress(s.key, s.meta, player.currentPosition / 1000.0, dur / 1000.0)
    }

    private fun quit(failed: Boolean) {
        this.failed = failed
        finish()
    }

    // A live channel has no timeline, and nothing to skip through
    private fun isLive() = player.isCurrentMediaItemLive || (session?.started == true && player.duration == C.TIME_UNSET)

    private fun defaultFocus(): View = if (seek.visibility == View.VISIBLE) seek else playPause

    private fun setControls(show: Boolean) {
        handler.removeCallbacks(hideControls)
        if (show == shown) return
        shown = show
        // Subtitles move up out of the way of the controls, as on Netflix
        video.subtitleView?.setBottomPaddingFraction(if (show) 0.3f else SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION)
        controls.animate().cancel()
        if (show) {
            controls.alpha = 0f
            controls.visibility = View.VISIBLE
            controls.animate().alpha(1f).setDuration(150).start()
            update()
            defaultFocus().requestFocus()
            scheduleHide()
        } else {
            controls.animate().alpha(0f).setDuration(300).withEndAction { if (!shown) controls.visibility = View.INVISIBLE }.start()
        }
    }

    /** Hides the controls after 4 seconds without a press while the video plays. Paused, they stay. */
    private fun scheduleHide() {
        handler.removeCallbacks(hideControls)
        if (player.playWhenReady && session?.started == true && !dragging) handler.postDelayed(hideControls, 4000)
    }

    private fun update() {
        val liveNow = isLive()
        val started = session?.started == true
        live.visibility = if (liveNow) View.VISIBLE else View.GONE
        val timeline = if (!liveNow && started) View.VISIBLE else if (liveNow) View.GONE else View.INVISIBLE
        seek.visibility = timeline
        time.visibility = timeline
        rewind.visibility = if (liveNow) View.GONE else View.VISIBLE
        forward.visibility = rewind.visibility
        playPause.setImageResource(if (player.playWhenReady) R.drawable.ic_pause else R.drawable.ic_play)
        val groups = player.currentTracks.groups
        val choices = groups.filter { it.type == C.TRACK_TYPE_AUDIO }.sumOf { it.length }
        tracks.visibility = if (choices > 1 || groups.any { it.type == C.TRACK_TYPE_TEXT }) View.VISIBLE else View.GONE
        val dur = player.duration
        if (!liveNow && dur != C.TIME_UNSET && dur > 0) {
            seek.max = dur.toInt()
            if (target == null && !dragging) seek.progress = player.currentPosition.toInt()
            seek.secondaryProgress = player.bufferedPosition.toInt()
            showTime()
        }
    }

    // Netflix shows the time that is left
    private fun showTime() {
        val dur = player.duration
        if (dur == C.TIME_UNSET) return
        time.text = clock((dur - (target ?: player.currentPosition)).coerceAtLeast(0))
    }

    private fun togglePause() {
        if (session?.started != true) return
        player.playWhenReady = !player.playWhenReady
        update()
        if (player.playWhenReady) scheduleHide() else handler.removeCallbacks(hideControls)
    }

    private fun seekBy(by: Long) {
        if (session?.started != true || isLive() || !player.isCurrentMediaItemSeekable) return
        val dur = player.duration.takeIf { it != C.TIME_UNSET } ?: return
        target = ((target ?: player.currentPosition) + by).coerceIn(0, dur)
        seek.progress = target!!.toInt()
        showTime()
        handler.removeCallbacks(doSeek)
        handler.postDelayed(doSeek, 600)
        setControls(true)
        scheduleHide()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        val down = event.action == KeyEvent.ACTION_DOWN
        val first = down && event.repeatCount == 0
        when (code) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_MEDIA_STOP -> {
                if (first) if (panel.visibility == View.VISIBLE) closePanel() else quit(false)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> if (first) { togglePause(); setControls(true) }
            KeyEvent.KEYCODE_MEDIA_PLAY -> if (first) { player.playWhenReady = true; update(); setControls(true) }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> if (first) { player.playWhenReady = false; update(); setControls(true) }
            KeyEvent.KEYCODE_MEDIA_REWIND -> if (down) seekBy(-10000)
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> if (down) seekBy(10000)
            else -> {
                // Channel numbers go to the TV interface, which collects them and switches the channel
                digit(code)?.let { if (first) Events.broadcast(it); return true }
                if (panel.visibility == View.VISIBLE) return super.dispatchKeyEvent(event)
                val select = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER
                val arrow = code in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_RIGHT
                if (!select && !arrow) return super.dispatchKeyEvent(event)
                if (!shown) {
                    // The first press brings the controls up; OK also pauses and the side arrows already skip, like Netflix
                    if (down) {
                        when (code) {
                            KeyEvent.KEYCODE_DPAD_LEFT -> seekBy(-10000)
                            KeyEvent.KEYCODE_DPAD_RIGHT -> seekBy(10000)
                            else -> if (select && first) togglePause()
                        }
                        setControls(true)
                    }
                    return true
                }
                scheduleHide()
                if (currentFocus === seek) {
                    // OK on the timeline pauses, as it does with the controls hidden
                    if (select) {
                        if (first) togglePause()
                        return true
                    }
                    // The side arrows skip 10 seconds a press, faster the longer they are held
                    if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                        if (down) {
                            val step = when {
                                event.repeatCount > 30 -> 60000L
                                event.repeatCount > 10 -> 30000L
                                else -> 10000L
                            }
                            seekBy(if (code == KeyEvent.KEYCODE_DPAD_LEFT) -step else step)
                        }
                        return true
                    }
                }
                return super.dispatchKeyEvent(event)
            }
        }
        return true
    }

    private fun digit(code: Int) = when (code) {
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> (code - KeyEvent.KEYCODE_0).toString()
        in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> (code - KeyEvent.KEYCODE_NUMPAD_0).toString()
        else -> null
    }

    /** Buttons on the phone remote while a video plays: the pad works like the TV remote's. */
    fun control(cmd: String) {
        when (cmd) {
            "ok", "playpause" -> { togglePause(); setControls(true) }
            "play" -> { player.playWhenReady = true; update() }
            "pause" -> { player.playWhenReady = false; update(); setControls(true) }
            "left", "seek_back" -> seekBy(-10000)
            "right", "seek_fwd" -> seekBy(10000)
            "up", "down" -> setControls(true)
            "volup", "voldown", "mute" -> Tuner.systemVolume(cmd)
        }
    }

    /** The Audio & subtitles panel: every audio track and subtitle the stream has, with the chosen ones ticked. */
    private fun openPanel() {
        val groups = player.currentTracks.groups
        val lang = Words.lang
        panelList.removeAllViews()
        var focus: View? = null
        // Two tracks with the same name, which streams often have, get a number to tell them apart
        val seen = HashMap<String, Int>()
        fun name(f: Format, n: Int, kind: String) = trackName(f, n, lang).let { base ->
            val k = (seen["$kind|$base"] ?: 0) + 1
            seen["$kind|$base"] = k
            if (k > 1) "$base ($k)" else base
        }
        heading(Words.tr("Audio"))
        var n = 0
        for (group in groups.filter { it.type == C.TRACK_TYPE_AUDIO }) {
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                n++
                val row = row(name(group.getTrackFormat(i), n, "audio"), group.isTrackSelected(i)) {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i)).build()
                }
                if (focus == null || group.isTrackSelected(i)) focus = row
            }
        }
        val text = groups.filter { it.type == C.TRACK_TYPE_TEXT }
        if (text.isNotEmpty()) {
            heading(Words.tr("Subtitles"))
            row(Words.tr("Off"), text.none { it.isSelected }) {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            }
            n = 0
            for (group in text) {
                for (i in 0 until group.length) {
                    if (!group.isTrackSupported(i)) continue
                    n++
                    row(name(group.getTrackFormat(i), n, "text"), group.isTrackSelected(i)) {
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i)).build()
                    }
                }
            }
        }
        handler.removeCallbacks(hideControls)
        panel.visibility = View.VISIBLE
        (focus ?: panelList.getChildAt(1))?.requestFocus()
    }

    private fun closePanel() {
        if (panel.visibility != View.VISIBLE) return
        panel.visibility = View.GONE
        if (shown) tracks.requestFocus()
        scheduleHide()
    }

    private fun trackName(f: Format, n: Int, lang: String): String {
        val language = f.language?.takeIf { it.isNotEmpty() && it != "und" }
            ?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.forLanguageTag(lang)) }
            ?.replaceFirstChar { it.titlecase(Locale.forLanguageTag(lang)) }
        val channels = when (f.channelCount) {
            1 -> "Mono"
            6 -> "5.1"
            8 -> "7.1"
            else -> null
        }
        // Subtitles that also describe sounds, for viewers who cannot hear them
        val captions = if (f.roleFlags and (C.ROLE_FLAG_CAPTION or C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND) != 0) "CC" else null
        return listOfNotNull(f.label ?: language, channels, captions).joinToString(" · ").ifEmpty { Words.tr("Track {n}").replace("{n}", n.toString()) }
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun heading(text: String) {
        panelList.addView(TextView(this).apply {
            this.text = text
            setTextColor(0xffffffff.toInt())
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(14), if (panelList.childCount > 0) dp(28) else 0, 0, dp(10))
        })
    }

    private fun row(text: String, selected: Boolean, pick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            setBackgroundResource(R.drawable.row_bg)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setOnClickListener {
                pick()
                closePanel()
            }
        }
        val tint = resources.getColorStateList(R.color.on_focus, theme)
        row.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_check)
            imageTintList = tint
            isDuplicateParentStateEnabled = true
            visibility = if (selected) View.VISIBLE else View.INVISIBLE
        }, LinearLayout.LayoutParams(dp(22), dp(22)))
        row.addView(TextView(this).apply {
            this.text = text
            setTextColor(tint)
            textSize = 17f
            isDuplicateParentStateEnabled = true
            setPadding(dp(14), 0, 0, 0)
        })
        panelList.addView(row)
        return row
    }

    private fun clock(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) String.format("%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) else String.format("%d:%02d", s / 60, s % 60)
    }

    fun flash(text: String, ms: Long = 1600) {
        if (text.isEmpty()) return
        osd.text = text
        osd.visibility = View.VISIBLE
        handler.removeCallbacks(hideOsd)
        handler.postDelayed(hideOsd, ms)
    }

    // Leaving the player for the home screen stops playback, like closing the mpv window
    override fun onStop() {
        super.onStop()
        if (!isFinishing && !isChangingConfigurations) quit(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        session?.let {
            save(it)
            Playback.ended(it, failed)
        }
        Playback.detach(this)
        player.release()
    }
}
