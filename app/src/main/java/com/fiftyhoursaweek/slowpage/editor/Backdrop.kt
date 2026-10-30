package com.fiftyhoursaweek.slowpage.editor

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.TextureView
import androidx.core.content.ContextCompat
import uniffi.hushhour.Backdrop
import uniffi.hushhour.BackdropMotion
import uniffi.hushhour.Rgb
import uniffi.hushhour.backdropAdvance
import uniffi.hushhour.backdropIndex
import uniffi.hushhour.backdropMotion
import uniffi.hushhour.backdropTiming
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The optional animated background behind the page (Appearance → Background, E16): Waves,
 * Swell or Drift from `hushhour_core::backdrop`, drawn by [BackdropShader] a few percent from
 * the page colour towards the text colour; the iPad's `BackdropView` on Android.
 *
 * The clock is the core's `Motion` ([BackdropClock]): it eases to a stop while the user types
 * and resumes after a pause. The view ticks at the core's frame interval (15 a second), at the
 * resting interval (once a second, drawing nothing while the clock stands still) once typing
 * has stilled it, and not at all while it is off, off-screen, while the activity is not in
 * front, under Remove animations or under Battery saver, where the pattern is drawn once and
 * stays still.
 */

/**
 * The animation clock, moved on by real time and slowed by typing; on the main thread. [now]
 * is in seconds; tests replace it.
 */
class BackdropClock(private val now: () -> Double = { SystemClock.uptimeMillis() / 1000.0 }) {
    var motion: BackdropMotion = backdropMotion()
        private set
    private var lastTick: Double? = null
    private var lastTyped: Double? = null

    /** An edit: only the time is noted; the next tick does the rest (no frame per keystroke). */
    fun noteTyping() {
        lastTyped = now()
    }

    /** Time spent stopped is not animated: the next tick starts afresh. */
    fun pause() {
        lastTick = null
    }

    /** Move the clock on by the time since the last tick, slowed by recent typing. */
    fun tick() {
        val t = now()
        lastTick?.let { last ->
            motion = backdropAdvance(motion, (t - last).toFloat(), lastTyped?.let { (t - it).toFloat() })
        }
        lastTick = t
    }

    /** Milliseconds to the next tick: the frame interval while moving, the resting one at rest. */
    val intervalMs: Long
        get() = backdropTiming().let { if (motion.resting) it.restingIntervalMs.toLong() else it.frameIntervalMs.toLong() }
}

/**
 * The core's WGSL shader (`hushhour_core::backdrop`, v0.4.0) in GLSL ES 3.00, line for line
 * after the iPad's Metal port: same uniforms (five vec4: page colour, text colour, page rect,
 * (time, strength, corner radius, scale), (pattern, target is sRGB, raw)), same maths. GL's
 * window origin is the bottom left, so y is flipped to the core's top-left pixels. With `mode.z`
 * set it writes the raw intensity (over 4, in 16 bits across red and green) for the test that
 * holds the port to the core's `intensity()`.
 */
object BackdropShader {
    const val VERTEX = """#version 300 es
void main() {
    vec2 uv = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    gl_Position = vec4(uv * 2.0 - 1.0, 0.0, 1.0);
}
"""

    const val FRAGMENT = """#version 300 es
precision highp float;
precision highp int;

uniform vec4 bg;
uniform vec4 fg;
uniform vec4 rect;   // origin x, y and size w, h in physical pixels
uniform vec4 misc;   // time, strength, corner radius (physical), scale factor
uniform vec4 mode;   // pattern, target is sRGB, raw intensity
out vec4 colour;

float hash(int x, int y, int z) {
    uint h = (uint(x) * 73856093u) ^ (uint(y) * 19349663u) ^ (uint(z) * 83492791u);
    h = (h ^ (h >> 13u)) * 1274126177u;
    return float(h ^ (h >> 16u)) / 4294967295.0;
}

float noise(vec3 p) {
    ivec3 i = ivec3(floor(p));
    vec3 f = p - floor(p);
    vec3 s = f * f * (3.0 - 2.0 * f);
    float a = mix(hash(i.x, i.y, i.z), hash(i.x + 1, i.y, i.z), s.x);
    float b = mix(hash(i.x, i.y + 1, i.z), hash(i.x + 1, i.y + 1, i.z), s.x);
    float c = mix(hash(i.x, i.y, i.z + 1), hash(i.x + 1, i.y, i.z + 1), s.x);
    float d = mix(hash(i.x, i.y + 1, i.z + 1), hash(i.x + 1, i.y + 1, i.z + 1), s.x);
    return mix(mix(a, b, s.y), mix(c, d, s.y), s.z);
}

float fbm(vec3 p) {
    float sum = 0.0;
    float amp = 0.5;
    float freq = 1.0;
    float norm = 0.0;
    for (int i = 0; i < 3; i++) {
        sum += amp * noise(vec3(p.xy * freq, p.z * freq + float(i) * 17.0));
        norm += amp;
        amp *= 0.5;
        freq *= 2.0;
    }
    return sum / norm;
}

float waves(float u, float v, float h, float t, float scale) {
    float k = 0.0;
    for (int i = 0; i < 9; i++) {
        float fi = float(i);
        float y = (fi + 1.0) / 10.0 + 0.03 * sin(u * 5.5 + t * 0.35 + fi * 0.7) + 0.012 * sin(u * 13.0 - t * 0.22 + fi * 1.9);
        float d = abs(v - y) * h;
        float line = clamp(0.5 * scale + 0.5 - d, 0.0, 1.0);
        k = max(k, line * (0.65 + 0.35 * sin(fi * 1.7 + t * 0.15)));
    }
    return k;
}

float swell(float u, float v, float h, float t) {
    float k = 0.0;
    float below = 0.0;
    for (int i = 0; i < 4; i++) {
        float fi = float(i);
        float y = 0.52 + fi * 0.12 + 0.035 * sin(u * (3.0 + fi) + t * (0.18 + fi * 0.05) + fi * 2.1) + 0.015 * sin(u * 9.0 - t * 0.12 + fi);
        float cover = clamp((v - y) * h + 0.5, 0.0, 1.0);
        float level = 1.2 * (fi + 1.0) / 4.0;
        k += cover * (level - below);
        below = level;
    }
    return k;
}

float drift(float x, float y, float t) {
    return 3.0 * smoothstep(0.46, 0.62, fbm(vec3(x * 2.6 + t * 0.04, y * 2.6 + t * 0.06, t * 0.09)));
}

vec3 to_linear(vec3 c) {
    return mix(pow((c + 0.055) / 1.055, vec3(2.4)), c / 12.92, vec3(lessThanEqual(c, vec3(0.04045))));
}

// The fragment's pixel, top-left origin as in the core.
vec2 pixel() {
    return vec2(gl_FragCoord.x, rect.w - gl_FragCoord.y) - rect.xy;
}

float intensity(vec2 p) {
    vec2 size = rect.zw;
    float t = misc.x;
    float u = p.x / size.x;
    float v = p.y / size.y;
    int pattern = int(mode.x + 0.5);
    if (pattern == 1) {
        return waves(u, v, size.y, t, misc.w);
    } else if (pattern == 2) {
        return swell(u, v, size.y, t);
    } else if (pattern == 3) {
        return drift(p.x / size.y, p.y / size.y, t);
    }
    return 0.0;
}

void main() {
    vec2 p = pixel();
    float k = intensity(p);
    if (mode.z > 0.5) {
        float v = floor(clamp(k / 4.0, 0.0, 1.0) * 65535.0 + 0.5);
        colour = vec4(floor(v / 256.0) / 255.0, mod(v, 256.0) / 255.0, 0.0, 1.0);
        return;
    }
    vec3 c = mix(bg.rgb, fg.rgb, clamp(k * misc.y, 0.0, 1.0));
    if (mode.y > 0.5) {
        c = to_linear(c);
    }
    // Rounded page corners: outside them the surface stays transparent (0 here, as on the iPad).
    vec2 size = rect.zw;
    float r = misc.z;
    vec2 q = abs(p - size * 0.5) - (size * 0.5 - vec2(r));
    float dist = length(max(q, vec2(0.0))) + min(max(q.x, q.y), 0.0) - r;
    float alpha = clamp(0.5 - dist, 0.0, 1.0);
    colour = vec4(c * alpha, alpha);
}
"""
}

/** What one frame draws: the uniforms of [BackdropShader]. */
data class BackdropParams(val backdrop: Backdrop, val page: Rgb, val text: Rgb, val width: Int, val height: Int, val time: Float, val scale: Float)

/**
 * OpenGL ES 3.0 on a thread of its own: one context, the shader, and the surface it draws into
 * (the view's, or an offscreen one for the tests). Nothing here exists until a pattern is
 * chosen: with the background Off the shader is never compiled. If ES 3.0 or the shader is not
 * there, it logs and the page stays plain.
 */
class BackdropRenderer {
    private val thread = HandlerThread("backdrop").apply { start() }
    private val gl = Handler(thread.looper)
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private val uniforms = IntArray(5)
    @Volatile var failed = false
        private set

    /** Run [block] on the GL thread and wait for it (attach, detach, the tests' reads). */
    private fun <T> await(block: () -> T): T? {
        var result: T? = null
        val done = CountDownLatch(1)
        gl.post {
            try {
                result = block()
            } finally {
                done.countDown()
            }
        }
        done.await(2, TimeUnit.SECONDS)
        return result
    }

    private fun setUp(): Boolean {
        if (failed) return false
        if (program != 0) return true
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) { "no EGL display" }
            val attributes = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) { "no ES 3.0 config" }
            config = configs[0]
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
            check(context != EGL14.EGL_NO_CONTEXT) { "no ES 3.0 context" }
            // A 1×1 offscreen surface to compile on, until a real one comes.
            val scratch = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            check(EGL14.eglMakeCurrent(display, scratch, scratch, context)) { "cannot make the context current" }
            program = link(compile(GLES30.GL_VERTEX_SHADER, BackdropShader.VERTEX), compile(GLES30.GL_FRAGMENT_SHADER, BackdropShader.FRAGMENT))
            listOf("bg", "fg", "rect", "misc", "mode").forEachIndexed { i, name -> uniforms[i] = GLES30.glGetUniformLocation(program, name) }
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, scratch)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "the background shader could not be set up (${e.message}); the page stays plain")
            failed = true
            return false
        }
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] != 0) { "shader: ${GLES30.glGetShaderInfoLog(shader)}" }
        return shader
    }

    private fun link(vertex: Int, fragment: Int): Int {
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, vertex)
        GLES30.glAttachShader(p, fragment)
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "program: ${GLES30.glGetProgramInfoLog(p)}" }
        return p
    }

    /** Draw into [texture] from now on (a view's surface came). */
    fun attach(texture: SurfaceTexture) {
        gl.post {
            if (!setUp()) return@post
            releaseSurface()
            surface = EGL14.eglCreateWindowSurface(display, config, texture, intArrayOf(EGL14.EGL_NONE), 0)
            if (surface == EGL14.EGL_NO_SURFACE) Log.e(TAG, "no window surface")
        }
    }

    /** The view's surface is going: let go of it before it does. */
    fun detach() {
        await { releaseSurface() }
    }

    private fun releaseSurface() {
        if (surface == EGL14.EGL_NO_SURFACE) return
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, surface)
        surface = EGL14.EGL_NO_SURFACE
    }

    /** Draw one frame on the GL thread; a frame asked for while one is pending replaces it. */
    fun draw(params: BackdropParams) {
        gl.removeCallbacksAndMessages(DRAW)
        gl.postAtTime({
            if (surface == EGL14.EGL_NO_SURFACE || !EGL14.eglMakeCurrent(display, surface, surface, context)) return@postAtTime
            render(params, raw = false)
            EGL14.eglSwapBuffers(display, surface)
        }, DRAW, SystemClock.uptimeMillis())
    }

    private fun render(params: BackdropParams, raw: Boolean) {
        GLES30.glViewport(0, 0, params.width, params.height)
        GLES30.glUseProgram(program)
        GLES30.glUniform4f(uniforms[0], params.page.r, params.page.g, params.page.b, 1f)
        GLES30.glUniform4f(uniforms[1], params.text.r, params.text.g, params.text.b, 1f)
        GLES30.glUniform4f(uniforms[2], 0f, 0f, params.width.toFloat(), params.height.toFloat())
        GLES30.glUniform4f(uniforms[3], params.time, backdropTiming().strength, 0f, params.scale)
        // The colours are sRGB and so is what the surface shows: nothing converted (mode.y = 0).
        GLES30.glUniform4f(uniforms[4], backdropIndex(params.backdrop).toFloat(), 0f, if (raw) 1f else 0f, 0f)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
    }

    /**
     * Render offscreen and read the pixels back, top row first (tests): RGBA bytes, or with
     * [raw] the intensity per pixel. Null if ES 3.0 is not there.
     */
    fun read(params: BackdropParams, raw: Boolean): ByteBuffer? = await {
        if (!setUp()) return@await null
        val pbuffer = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, params.width, EGL14.EGL_HEIGHT, params.height, EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
        render(params, raw)
        val pixels = ByteBuffer.allocateDirect(params.width * params.height * 4).order(ByteOrder.nativeOrder())
        GLES30.glReadPixels(0, 0, params.width, params.height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels)
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, pbuffer)
        // GL reads bottom row first.
        val rows = ByteBuffer.allocate(pixels.capacity())
        val row = params.width * 4
        for (y in params.height - 1 downTo 0) {
            val line = ByteArray(row)
            pixels.position(y * row)
            pixels.get(line)
            rows.put(line)
        }
        rows.flip()
        rows
    }

    fun release() {
        gl.post {
            releaseSurface()
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            if (display != EGL14.EGL_NO_DISPLAY) EGL14.eglTerminate(display)
            context = EGL14.EGL_NO_CONTEXT
            display = EGL14.EGL_NO_DISPLAY
            program = 0
            thread.quitSafely()
        }
    }

    companion object {
        private const val TAG = "SlowpageBackdrop"
        private const val EGL_OPENGL_ES3_BIT = 0x40
        private val DRAW = Any()

        /** The raw intensity from two bytes of [read] with `raw` (red high, green low). */
        fun intensity(hi: Byte, lo: Byte): Float = ((hi.toInt() and 0xFF) * 256 + (lo.toInt() and 0xFF)) / 65535f * 4f
    }
}

/**
 * The pattern, behind everything on the page. Created only for a pattern (never while Off). The
 * screen sets [backdrop], the colours and [inFront]; [typedAt] gives the time of the last edit.
 */
@SuppressLint("ViewConstructor")
class BackdropView(context: Context, private val clock: BackdropClock = BackdropClock()) : TextureView(context), TextureView.SurfaceTextureListener {
    private val main = Handler(Looper.getMainLooper())
    private var renderer: BackdropRenderer? = null
    private var width0 = 0
    private var height0 = 0
    private var hasSurface = false
    /** Attached to a window (isAttachedToWindow is still true while being detached). */
    private var attached = false
    /** The clock of the last frame drawn: a tick draws only when it moved. */
    private var drawnClock: Float? = null

    var backdrop: Backdrop = Backdrop.OFF
        set(value) {
            if (field == value) return
            field = value
            refresh()
        }
    private var page = Rgb(1f, 1f, 1f)
    private var text = Rgb(0f, 0f, 0f)

    /** The time of the last edit (SystemClock.uptimeMillis), or 0 for none. */
    var typedAt: () -> Long = { 0L }
    private var noted = 0L

    /** What stops the animation; the screen and the system keep them current, tests set them. */
    var inFront = true
        set(value) {
            if (field == value) return
            field = value
            refresh()
        }
    var reduceMotion = false
        set(value) {
            if (field == value) return
            field = value
            refresh()
        }
    var lowPower = false
        set(value) {
            if (field == value) return
            field = value
            refresh()
        }
    /** Frames drawn so far (tests). */
    var frames = 0
        private set
    var isAnimating = false
        private set
    /** The tick interval while animating, in ms. */
    var tickInterval: Long? = null
        private set

    private val ticker = Runnable { tick() }
    private val animationScale = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) {
            reduceMotion = animationsRemoved()
        }
    }
    private val powerSave = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            lowPower = (c.getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode
        }
    }

    init {
        isOpaque = true
        surfaceTextureListener = this
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        reduceMotion = animationsRemoved()
        lowPower = (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode
    }

    /** Remove animations (Settings › Accessibility): Android's Reduce Motion. */
    private fun animationsRemoved() = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

    fun setColours(page: Rgb, text: Rgb) {
        if (page == this.page && text == this.text) return
        this.page = page
        this.text = text
        draw()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        context.contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, animationScale)
        ContextCompat.registerReceiver(context, powerSave, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        attached = true
        refresh()
    }

    override fun onDetachedFromWindow() {
        context.contentResolver.unregisterContentObserver(animationScale)
        context.unregisterReceiver(powerSave)
        attached = false
        refresh()
        renderer?.release()
        renderer = null
        super.onDetachedFromWindow()
    }

    /** Whether the clock runs at all. */
    val shouldAnimate get() = backdrop != Backdrop.OFF && attached && inFront && !reduceMotion && !lowPower

    /** Start, stop or retime the ticks for the current state, and draw. */
    private fun refresh() {
        main.removeCallbacks(ticker)
        if (shouldAnimate) {
            isAnimating = true
            schedule()
        } else {
            isAnimating = false
            tickInterval = null
            clock.pause()
        }
        draw()
    }

    private fun schedule() {
        main.removeCallbacks(ticker)
        tickInterval = clock.intervalMs
        main.postDelayed(ticker, clock.intervalMs)
    }

    /** Move the clock on and draw if it moved. */
    fun tick() {
        val typed = typedAt()
        if (typed != noted && typed != 0L) {
            noted = typed
            clock.noteTyping()
        }
        clock.tick()
        if (shouldAnimate) schedule()
        if (clock.motion.clock == drawnClock) return
        draw()
    }

    /** Draw the current state, once there is a surface (ticks skip it while the pattern has not moved). */
    private fun draw() {
        if (backdrop == Backdrop.OFF) return
        drawnClock = clock.motion.clock
        frames += 1
        val r = renderer?.takeIf { hasSurface && width0 > 0 && height0 > 0 } ?: return
        r.draw(BackdropParams(backdrop, page, text, width0, height0, clock.motion.clock, resources.displayMetrics.density))
    }

    internal fun clockForTests() = clock.motion.clock
    internal fun restingForTests() = clock.motion.resting

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        val r = renderer ?: BackdropRenderer().also { renderer = it }
        r.attach(surface)
        hasSurface = true
        width0 = width
        height0 = height
        draw()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        width0 = width
        height0 = height
        draw()
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        hasSurface = false
        renderer?.detach()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
}
