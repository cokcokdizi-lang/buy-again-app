package com.buyagaindontbuyagain.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var cameraUri: Uri? = null
    private var pendingFileChooser: WebChromeClient.FileChooserParams? = null

    private val openFileLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = fileCallback ?: return@registerForActivityResult
            var results: Array<Uri>? = null
            if (result.resultCode == RESULT_OK) {
                val data = result.data
                results = when {
                    data?.clipData != null -> {
                        val clip: ClipData = data.clipData!!
                        Array(clip.itemCount) { i -> clip.getItemAt(i).uri }
                    }
                    data?.data != null -> arrayOf(data.data!!)
                    cameraUri != null -> arrayOf(cameraUri!!)
                    else -> null
                }
            }
            callback.onReceiveValue(results)
            fileCallback = null
            cameraUri = null
            pendingFileChooser = null
        }

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) launchImageChooser(pendingFileChooser)
            else launchImageDocumentOnly()
        }

    private val createBackupLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            val pending = File(cacheDir, "pending_backup.json")
            var success = false
            if (uri != null && pending.exists()) {
                try {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        pending.inputStream().use { input -> input.copyTo(out) }
                    } ?: throw IllegalStateException("Could not open backup destination")
                    success = true
                } catch (_: Exception) {
                    success = false
                }
            }
            pending.delete()
            webView.post { webView.evaluateJavascript("window.androidBackupSaved&&window.androidBackupSaved(${if (success) "true" else "false"})", null) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        cleanupOldCameraFiles()

        webView = findViewById(R.id.webView)
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }

        webView.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(view: WebView, request: android.webkit.WebResourceRequest) =
                assetLoader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
                val url = request.url
                return !(url.scheme == "https" && url.host == "appassets.androidplatform.net")
            }
        }

        webView.addJavascriptInterface(AndroidBridge(), "AndroidBridge")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                pendingFileChooser = fileChooserParams

                val accepts = fileChooserParams?.acceptTypes?.joinToString(",")?.lowercase().orEmpty()
                val wantsJson = accepts.contains("json") || accepts.contains("badb")
                val wantsImage = fileChooserParams?.isCaptureEnabled == true || accepts.contains("image") || accepts.isBlank()

                if (wantsJson) {
                    launchBackupRestorePicker()
                } else if (wantsImage) {
                    if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        launchImageChooser(fileChooserParams)
                    } else {
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                } else {
                    launchGenericDocumentPicker()
                }
                return true
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finishAndRemoveTask()
            }
        })

        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html")
    }

    private inner class AndroidBridge {
        @JavascriptInterface
        fun startBarcodeScan() {
            runOnUiThread { launchBarcodeScanner() }
        }

        @JavascriptInterface
        fun closeApp() {
            runOnUiThread { finishAndRemoveTask() }
        }

        @JavascriptInterface
        fun saveBackup(json: String, filename: String) {
            try {
                File(cacheDir, "pending_backup.json").writeText(json, Charsets.UTF_8)
                runOnUiThread { createBackupLauncher.launch(filename) }
            } catch (_: Exception) {
                runOnUiThread { webView.evaluateJavascript("window.androidBackupSaved&&window.androidBackupSaved(false)", null) }
            }
        }

        // Independent safety copy of product metadata. This deliberately lives in
        // Android app-private storage rather than WebView/IndexedDB storage.
        @JavascriptInterface
        fun saveSafetyMetadata(json: String): Boolean {
            return try {
                val target = File(filesDir, "badb_safety_metadata.json")
                val temp = File(filesDir, "badb_safety_metadata.json.tmp")
                FileOutputStream(temp).use { out ->
                    out.write(json.toByteArray(Charsets.UTF_8))
                    out.fd.sync()
                }
                val backup = File(filesDir, "badb_safety_metadata.json.bak")
                if (target.exists()) target.copyTo(backup, overwrite = true)
                if (target.exists() && !target.delete()) return false
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
                true
            } catch (_: Exception) {
                false
            }
        }

        @JavascriptInterface
        fun getSafetyMetadata(): String {
            return try {
                val target = File(filesDir, "badb_safety_metadata.json")
                val backup = File(filesDir, "badb_safety_metadata.json.bak")
                when {
                    target.exists() -> target.readText(Charsets.UTF_8)
                    backup.exists() -> backup.readText(Charsets.UTF_8)
                    else -> "[]"
                }
            } catch (_: Exception) {
                try {
                    val backup = File(filesDir, "badb_safety_metadata.json.bak")
                    if (backup.exists()) backup.readText(Charsets.UTF_8) else "[]"
                } catch (_: Exception) { "[]" }
            }
        }
    }

    private fun launchBarcodeScanner() {
        val scanner = GmsBarcodeScanning.getClient(this)
        scanner.startScan()
            .addOnSuccessListener { barcode ->
                val code = barcode.rawValue
                if (code.isNullOrBlank()) {
                    webView.evaluateJavascript("window.androidBarcodeFailed&&window.androidBarcodeFailed()", null)
                } else {
                    val quoted = org.json.JSONObject.quote(code)
                    webView.evaluateJavascript("window.androidBarcodeResult($quoted)", null)
                }
            }
            .addOnCanceledListener {
                webView.evaluateJavascript("window.androidBarcodeCancelled&&window.androidBarcodeCancelled()", null)
            }
            .addOnFailureListener {
                webView.evaluateJavascript("window.androidBarcodeFailed&&window.androidBarcodeFailed()", null)
            }
    }

    private fun launchImageChooser(params: WebChromeClient.FileChooserParams?) {
        if (params?.isCaptureEnabled == true) {
            val camera = createCameraIntent()
            if (camera != null) {
                openFileLauncher.launch(camera)
                return
            }
        }

        val gallery = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        val chooser = Intent.createChooser(gallery, "Choose Existing Photo")
        createCameraIntent()?.let { chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(it)) }
        try {
            openFileLauncher.launch(chooser)
        } catch (_: ActivityNotFoundException) {
            launchImageDocumentOnly()
        }
    }

    private fun launchImageDocumentOnly() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        openFileLauncher.launch(intent)
    }

    private fun launchBackupRestorePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/json", "text/plain", "application/octet-stream"))
        }
        openFileLauncher.launch(intent)
    }

    private fun launchGenericDocumentPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        openFileLauncher.launch(intent)
    }

    private fun cleanupOldCameraFiles() {
        try {
            val dir = File(cacheDir, "camera")
            val cutoff = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
            dir.listFiles()?.filter { it.isFile && it.lastModified() < cutoff }?.forEach { it.delete() }
        } catch (_: Exception) { }
    }

    private fun createCameraIntent(): Intent? {
        val cameraDir = File(cacheDir, "camera").apply { mkdirs() }
        val photoFile = File(cameraDir, "item_${System.currentTimeMillis()}.jpg")
        cameraUri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", photoFile)
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, cameraUri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return if (intent.resolveActivity(packageManager) != null) intent else null
    }
}
