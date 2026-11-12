#!/usr/bin/env python3
"""Patch the pinned mpv-android Surface lifecycle before building the audit APK."""

from pathlib import Path
from textwrap import dedent


def replace_once(path: Path, old: str, new: str) -> None:
    source = path.read_text()
    if old not in source:
        raise SystemExit(f"Expected upstream source anchor missing in {path}: {old[:80]!r}")
    path.write_text(source.replace(old, new, 1))


render = Path("mpv-android/app/src/main/jni/render.cpp")
replace_once(
    render,
    "static jobject surface;\n\njni_func(void, attachSurface, jobject surface_) {",
    dedent("""\
        static jobject surface;

        // Called only after mpv_terminate_destroy() has completed. Keep the
        // global reference until then so the native VO cannot outlive its Surface.
        void releaseSurfaceReference(JNIEnv *env) {
            if (surface) {
                env->DeleteGlobalRef(surface);
                surface = nullptr;
            }
        }

        jni_func(void, attachSurface, jobject surface_) {"""),
)
replace_once(
    render,
    "    surface = env->NewGlobalRef(surface_);\n    if (!surface)",
    dedent("""\
        if (surface) {
            ALOGE("attachSurface called while a previous Surface is still retained");
            return;
        }
        surface = env->NewGlobalRef(surface_);
        if (!surface)"""),
)
replace_once(
    render,
    "    env->DeleteGlobalRef(surface);\n    surface = NULL;",
    dedent("""\
        // Keep the Java reference until mpv_terminate_destroy() has synchronously
        // completed. releaseSurfaceReference() performs the final release."""),
)

main = Path("mpv-android/app/src/main/jni/main.cpp")
replace_once(
    main,
    "static jobject global_appctx;",
    "static jobject global_appctx;\n\nextern void releaseSurfaceReference(JNIEnv *env);",
)
replace_once(
    main,
    "    mpv_terminate_destroy(g_mpv);\n    g_mpv = NULL;",
    "    mpv_terminate_destroy(g_mpv);\n    g_mpv = NULL;\n    releaseSurfaceReference(env);",
)

view = Path("mpv-android/app/src/main/java/is/xyz/mpv/BaseMPVView.kt")
original = view.read_text()
if "MPVLib.detachSurface()" not in original or "override fun surfaceDestroyed" not in original:
    raise SystemExit("Pinned BaseMPVView lifecycle changed; refusing to patch")
view.write_text(dedent("""\
    package `is`.xyz.mpv

    import android.content.Context
    import android.util.AttributeSet
    import android.util.Log
    import android.view.SurfaceHolder
    import android.view.SurfaceView

    // Owns the native MPV context and Java Surface as one lifecycle.
    // Native destruction completes before the JNI global Surface reference is released.
    abstract class BaseMPVView(context: Context, attrs: AttributeSet) :
        SurfaceView(context, attrs), SurfaceHolder.Callback {
        private var filePath: String? = null
        private var voInUse: String = "gpu"
        private var configDir: String = ""
        private var cacheDir: String = ""
        private var initialized = false
        private var callbackRegistered = false

        fun initialize(configDir: String, cacheDir: String) {
            this.configDir = configDir
            this.cacheDir = cacheDir
            initializeMpv()
            if (!callbackRegistered) {
                holder.addCallback(this)
                callbackRegistered = true
            }
        }

        private fun initializeMpv() {
            if (initialized) return
            MPVLib.create(context.applicationContext)
            MPVLib.setOptionString("config", "yes")
            MPVLib.setOptionString("config-dir", configDir)
            for (opt in arrayOf("gpu-shader-cache-dir", "icc-cache-dir"))
                MPVLib.setOptionString(opt, cacheDir)
            initOptions()
            MPVLib.init()
            postInitOptions()
            MPVLib.setOptionString("force-window", "no")
            MPVLib.setOptionString("idle", "once")
            initialized = true
            observeProperties()
        }

        private fun destroyMpv() {
            if (!initialized) return
            // Native destroy joins the event thread and terminates libmpv.
            // JNI releases its retained Surface only after that has completed.
            MPVLib.destroy()
            initialized = false
        }

        fun destroy() {
            if (callbackRegistered) {
                holder.removeCallback(this)
                callbackRegistered = false
            }
            destroyMpv()
        }

        protected abstract fun initOptions()
        protected abstract fun postInitOptions()
        protected abstract fun observeProperties()

        fun playFile(filePath: String) {
            this.filePath = filePath
        }

        fun setVo(vo: String) {
            voInUse = vo
            if (initialized) MPVLib.setOptionString("vo", vo)
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            if (initialized)
                MPVLib.setPropertyString("android-surface-size", "${width}x$height")
        }

        override fun surfaceCreated(holder: SurfaceHolder) {
            Log.w(TAG, "attaching surface")
            if (!initialized) initializeMpv()
            MPVLib.attachSurface(holder.surface)
            MPVLib.setOptionString("force-window", "yes")
            if (filePath != null) {
                MPVLib.command(arrayOf("loadfile", filePath!!))
                filePath = null
            } else {
                MPVLib.setPropertyString("vo", voInUse)
            }
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            Log.w(TAG, "destroying MPV before Android releases Surface")
            destroyMpv()
        }

        companion object {
            private const val TAG = "mpv"
        }
    }
    """).lstrip())

print("Applied MPV Surface teardown patch to pinned upstream source.")
