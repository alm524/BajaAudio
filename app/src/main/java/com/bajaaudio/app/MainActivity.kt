package com.bajaaudio.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var urlInput: EditText
    private lateinit var downloadBtn: Button
    private lateinit var driveBtn: Button
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var busy = false
    private var ready = false
    private var lastSaved: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        urlInput = findViewById(R.id.url)
        downloadBtn = findViewById(R.id.download)
        driveBtn = findViewById(R.id.drive)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)

        findViewById<Button>(R.id.paste).setOnClickListener { pasteFromClipboard() }
        downloadBtn.setOnClickListener { startDownload(urlInput.text.toString()) }
        driveBtn.setOnClickListener { lastSaved?.let { sendToDrive(it) } }

        prepareEngine()
        handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
    }

    /** Cuando el usuario comparte desde YouTube, llega el link aquí y se descarga solo. */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val link = extractUrl(text) ?: return
        urlInput.setText(link)
        startDownload(link)
    }

    private fun pasteFromClipboard() {
        val cm = getSystemService(ClipboardManager::class.java)
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        val link = extractUrl(text)
        if (link != null) urlInput.setText(link) else show("No hay ningún link copiado.")
    }

    private fun extractUrl(text: String): String? =
        Regex("""https?://\S+""").find(text)?.value

    // ---------- Motor (yt-dlp + ffmpeg) ----------

    private fun prepareEngine() {
        busy = true
        setWorking(true, "Preparando la app… (la primera vez tarda un poco)")
        worker.execute {
            try {
                YoutubeDL.getInstance().init(applicationContext)
                FFmpeg.getInstance().init(applicationContext)
                updateIfNeeded()
                ready = true
                main.post { setWorking(false, "Listo para descargar.") }
            } catch (e: Exception) {
                main.post { setWorking(false, "❌ No se pudo preparar la app:\n${e.message}") }
            } finally {
                busy = false
            }
        }
    }

    /** YouTube cambia seguido: actualizamos el descargador como máximo una vez al día. */
    private fun updateIfNeeded() {
        val prefs = getSharedPreferences("app", MODE_PRIVATE)
        val last = prefs.getLong("lastUpdate", 0)
        if (System.currentTimeMillis() - last < 24 * 60 * 60 * 1000L) return
        main.post { status.text = "Actualizando el descargador…" }
        try {
            YoutubeDL.getInstance().updateYoutubeDL(applicationContext, YoutubeDL.UpdateChannel.STABLE)
            prefs.edit().putLong("lastUpdate", System.currentTimeMillis()).apply()
        } catch (_: Exception) {
            // Sin internet o falla temporal: seguimos con la versión que ya hay.
        }
    }

    // ---------- Descarga ----------

    private fun startDownload(raw: String) {
        val link = extractUrl(raw.trim())
        if (link == null) { show("Pega primero un link de YouTube."); return }
        if (busy) {
            // Si aún se está preparando, esperamos y lo intentamos de nuevo.
            if (!ready) main.postDelayed({ startDownload(link) }, 1000) else show("Espera, ya hay una descarga en curso.")
            return
        }
        hideKeyboard()
        busy = true
        lastSaved = null
        driveBtn.visibility = View.GONE
        setWorking(true, "Buscando el video…")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        worker.execute {
            val workDir = File(cacheDir, "descargas").apply { deleteRecursively(); mkdirs() }
            var error: String? = null
            try {
                val request = YoutubeDLRequest(link).apply {
                    addOption("--no-playlist")
                    addOption("-x")
                    addOption("--audio-format", "mp3")
                    addOption("--audio-quality", "0")
                    addOption("--embed-metadata")
                    addOption("--embed-thumbnail")
                    addOption("--convert-thumbnails", "jpg")
                    addOption("--windows-filenames")
                    addOption("--no-mtime")
                    addOption("-o", File(workDir, "%(title).120B.%(ext)s").absolutePath)
                }
                YoutubeDL.getInstance().execute(request, "descarga") { pct, _, _ ->
                    main.post {
                        if (pct >= 0) {
                            progress.isIndeterminate = false
                            progress.progress = pct.toInt()
                            status.text = "Descargando… ${pct.toInt()}%"
                        }
                        if (pct >= 100) {
                            progress.isIndeterminate = true
                            status.text = "Convirtiendo a MP3…"
                        }
                    }
                }
            } catch (e: Exception) {
                error = e.message
            }

            // Aunque haya un aviso (por ejemplo con la carátula), si el MP3 existe lo guardamos.
            val mp3 = workDir.listFiles()?.firstOrNull { it.extension.equals("mp3", true) }
            if (mp3 != null) {
                try {
                    val uri = saveToMusic(mp3)
                    main.post {
                        lastSaved = uri
                        driveBtn.visibility = View.VISIBLE
                        setWorking(false, "✅ ¡Listo!\n${mp3.nameWithoutExtension}\n\nGuardado en tu teléfono, carpeta Música/BajaAudio.")
                    }
                } catch (e: Exception) {
                    main.post { setWorking(false, "❌ No se pudo guardar el archivo:\n${e.message}") }
                }
            } else {
                main.post { setWorking(false, friendlyError(error)) }
            }
            workDir.deleteRecursively()
            busy = false
            main.post { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }
    }

    /** Copia el MP3 a la carpeta pública Música/BajaAudio (visible en cualquier reproductor). */
    private fun saveToMusic(file: File): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
            put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/BajaAudio")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Android no permitió crear el archivo")
        contentResolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
        values.clear()
        values.put(MediaStore.Audio.Media.IS_PENDING, 0)
        contentResolver.update(uri, values, null, null)
        return uri
    }

    /** Abre la app de Google Drive para subir el MP3 (ahí eliges la carpeta). */
    private fun sendToDrive(uri: Uri) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "audio/mpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent(send).setPackage("com.google.android.apps.docs"))
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent.createChooser(send, "Guardar en…"))
        }
    }

    private fun friendlyError(msg: String?): String {
        val m = msg.orEmpty()
        return when {
            m.contains("Unable to resolve host", true) || m.contains("Network", true) ->
                "❌ Sin conexión a internet. Revisa tu wifi o datos e inténtalo de nuevo."
            m.contains("Private video", true) -> "❌ Ese video es privado."
            m.contains("not a bot", true) || m.contains("Sign in", true) ->
                "❌ YouTube pidió verificación. Espera unos minutos e inténtalo de nuevo."
            m.contains("Unsupported URL", true) || m.contains("not a valid URL", true) ->
                "❌ Ese link no es de un video válido."
            else -> "❌ No se pudo descargar.\n\nDetalle: ${m.lines().lastOrNull { it.isNotBlank() } ?: "desconocido"}"
        }
    }

    // ---------- UI ----------

    private fun setWorking(working: Boolean, message: String) {
        downloadBtn.isEnabled = !working
        downloadBtn.alpha = if (working) 0.5f else 1f
        progress.visibility = if (working) View.VISIBLE else View.GONE
        progress.isIndeterminate = true
        status.text = message
    }

    private fun show(message: String) { status.text = message }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)
            .hideSoftInputFromWindow(urlInput.windowToken, 0)
    }
}
