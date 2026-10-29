package com.swiftbrowser.engine

import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import androidx.core.content.edit
import com.swiftbrowser.core.L

/** The phone's graphics chip, used to pick how pages are drawn. */
object GpuInfo {
    private const val KEY = "gl_renderer"

    /** The OpenGL renderer name, e.g. "Adreno (TM) 308". Asked once, then remembered. */
    fun renderer(ctx: Context): String {
        val sp = ctx.getSharedPreferences("engine", Context.MODE_PRIVATE)
        sp.getString(KEY, null)?.let { return it }
        val name = query().orEmpty()
        // If it couldn't be read, it is asked again next time.
        if (name.isNotEmpty()) sp.edit { putString(KEY, name) }
        L.d("Graphics chip: $name")
        return name
    }

    /**
     * Old, slow chips (Adreno 3xx): "Show sites as designed" stays off on them. Pages are drawn with
     * the chip on every phone (2.7).
     */
    fun isSlowChip(renderer: String): Boolean = renderer.startsWith("Adreno (TM) 3")

    /** Creates a throwaway OpenGL context to read the renderer name. */
    private fun query(): String? {
        try {
            val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) return null
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            val attribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE,
            )
            if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) || count[0] == 0) return null
            val config = configs[0]
            val context = EGL14.eglCreateContext(
                display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
            )
            val surface = EGL14.eglCreatePbufferSurface(
                display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
            )
            try {
                if (!EGL14.eglMakeCurrent(display, surface, surface, context)) return null
                return GLES20.glGetString(GLES20.GL_RENDERER)
            } finally {
                // The display stays initialised: the app's own UI drawing shares it.
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(display, surface)
                EGL14.eglDestroyContext(display, context)
            }
        } catch (e: Throwable) {
            L.w("Could not read the graphics chip name", e)
            return null
        }
    }
}
