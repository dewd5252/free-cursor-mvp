package com.freecursor.app.core

import android.content.Context
import com.freecursor.app.bridge.BridgeEventEmitter
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.Locale
import java.util.zip.ZipInputStream

class ModelManager private constructor(
    private val appContext: Context,
) {
    private val modelDir = File(appContext.filesDir, "models")
    private val modelBundleDir = File(modelDir, "bundle")
    private val modelFile = File(modelBundleDir, "model.onnx")

    private val requiredTokenizerFiles = listOf(
        "tokenizer.json",
        "tokenizer_config.json",
        "special_tokens_map.json",
        "generation_config.json",
    )

    @Volatile
    private var modelStatus: String = MODEL_UNAVAILABLE

    @Volatile
    private var environment: Any? = null

    @Volatile
    private var session: Any? = null

    private val tensorIoAdapter = OnnxTensorIoAdapter()

    @Synchronized
    fun ensureModelLoaded(): Boolean {
        if (session != null) {
            setStatus(MODEL_READY)
            return true
        }

        if (!modelFile.exists()) {
            setStatus(MODEL_UNAVAILABLE)
            return false
        }

        return try {
            session = tryCreateOnnxSession(modelFile.absolutePath)
            if (session != null) {
                setStatus(MODEL_READY)
                true
            } else {
                setStatus(MODEL_UNAVAILABLE)
                false
            }
        } catch (_: Throwable) {
            setStatus(MODEL_FAILED)
            false
        }
    }

    @Synchronized
    fun downloadModel(url: String) {
        setStatus(MODEL_DOWNLOADING)

        try {
            modelDir.mkdirs()
            modelBundleDir.mkdirs()

            val trimmed = url.trim()
            when {
                trimmed.endsWith(".zip", ignoreCase = true) -> {
                    val archiveFile = File(modelDir, "bundle_download.zip")
                    URL(trimmed).openStream().use { input ->
                        FileOutputStream(archiveFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    resetBundleDir()
                    unzipToBundle(archiveFile, modelBundleDir)
                    archiveFile.delete()
                }
                trimmed.endsWith(".onnx", ignoreCase = true) -> {
                    modelBundleDir.mkdirs()
                    URL(trimmed).openStream().use { input ->
                        FileOutputStream(modelFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                }
                else -> {
                    throw IllegalArgumentException(
                        "Unsupported model URL. Provide .onnx or .zip bundle URL.",
                    )
                }
            }

            ensureModelLoaded()
        } catch (_: Throwable) {
            setStatus(MODEL_FAILED)
        }
    }

    fun getModelStatus(): String {
        return modelStatus
    }

    fun markDownloading() {
        setStatus(MODEL_DOWNLOADING)
    }

    fun hasTokenizerBundle(): Boolean = hasTokenizerFiles()

    fun generateInferenceJson(request: InferenceRequestDto): String {
        var adapterFailureReason: String? = null

        val activeEnvironment = environment
        val activeSession = session
        if (activeEnvironment != null && activeSession != null) {
            val outcome = tensorIoAdapter.run(
                environment = activeEnvironment,
                session = activeSession,
                request = request,
                modelBundleDir = modelBundleDir,
            )
            if (outcome.json != null) {
                return outcome.json
            }
            adapterFailureReason = outcome.failureReason
        }

        return fallbackInferenceJson(
            request = request,
            adapterFailureReason = adapterFailureReason,
        )
    }

    private fun fallbackInferenceJson(
        request: InferenceRequestDto,
        adapterFailureReason: String?,
    ): String {
        val command = request.userCommand.lowercase(Locale.US)
        val bestNode = chooseBestNode(command, request.screenData)
        val direction = chooseDirection(command)
        val textPayload = if (containsAny(command, listOf("اكتب", "type", "write", "enter"))) {
            extractTextPayload(request.userCommand)
        } else {
            null
        }

        val requestedApp = detectAppIntent(command)

        val action = when {
            requestedApp != null -> "launch_app"
            containsAny(command, listOf("back", "ارجع", "رجوع", "عودة")) -> "back"
            containsAny(command, listOf("home", "الرئيسية", "هوم")) -> "home"
            containsAny(command, listOf("recent", "التطبيقات الأخيرة", "recent apps")) -> "recent_apps"
            containsAny(command, listOf("notification", "notifications", "الإشعارات")) -> "open_notifications"
            containsAny(command, listOf("quick settings", "الإعدادات السريعة")) -> "open_quick_settings"
            containsAny(command, listOf("اكتب", "type", "write", "enter")) -> "type"
            containsAny(command, listOf("ضغط مطول", "long", "hold")) -> "long_press"
            containsAny(command, listOf("swipe", "اسحب")) -> "swipe"
            containsAny(command, listOf("scroll", "مرر", "انزل", "اطلع")) -> "scroll"
            containsAny(command, listOf("click", "tap", "دوس", "اضغط", "افتح")) -> "click"
            else -> "noop"
        }

        val isSystemAction = action in SYSTEM_ACTIONS
        val confidence = when (action) {
            "noop" -> 0.42
            "launch_app" -> 0.88
            "back", "home", "recent_apps", "open_notifications", "open_quick_settings" -> 0.84
            else -> if (bestNode != null) 0.76 else 0.54
        }

        return JSONObject().apply {
            put("action", action)
            put("target_id", if (isSystemAction || action == "noop") JSONObject.NULL else bestNode?.id)
            put("text", if (action == "type") textPayload else JSONObject.NULL)
            put("direction", direction ?: JSONObject.NULL)
            put("start_id", if (action == "swipe") bestNode?.id else JSONObject.NULL)
            put("end_id", JSONObject.NULL)
            put("app_name", requestedApp?.appName ?: JSONObject.NULL)
            put("package_name", when {
                requestedApp != null -> requestedApp.packageName
                action in UI_ACTIONS -> bestNode?.packageName ?: request.screenData.firstOrNull()?.packageName
                else -> JSONObject.NULL
            })
            put("requires_cursor", !isSystemAction && action != "noop")
            put("execution_mode", if (isSystemAction) "system_direct" else "ui_cursor")
            put("confidence", confidence)
            put(
                "reason",
                if (session != null) {
                    "ONNX output unavailable; policy fallback used. ${adapterFailureReason.orEmpty()}".trim()
                } else {
                    "Model unavailable; deterministic fallback policy used."
                },
            )
        }.toString()
    }

    private fun detectAppIntent(command: String): AppIntent? {
        return APP_INTENTS.firstOrNull { intent ->
            command.contains(intent.appName.lowercase(Locale.US))
        }
    }

    private fun chooseBestNode(
        command: String,
        nodes: List<ScreenNodeDto>,
    ): ScreenNodeDto? {
        if (nodes.isEmpty()) {
            return null
        }

        val tokens = command
            .split(Regex("\\s+"))
            .map { it.trim().lowercase(Locale.US) }
            .filter { it.length > 1 }

        val candidates = nodes.filter { it.enabled && (it.clickable || it.editable || it.role == "button") }
        if (candidates.isEmpty()) {
            return nodes.firstOrNull()
        }

        return candidates.maxByOrNull { node ->
            var score = 0
            val targetText = "${node.text} ${node.hint} ${node.role}".lowercase(Locale.US)
            tokens.forEach { token ->
                if (targetText.contains(token)) {
                    score += 3
                }
            }
            if (node.role == "button") score += 1
            if (node.editable && containsAny(command, listOf("اكتب", "type", "write"))) score += 3
            score
        } ?: candidates.firstOrNull()
    }

    private fun chooseDirection(command: String): String? {
        return when {
            containsAny(command, listOf("up", "فوق", "اعلى")) -> "up"
            containsAny(command, listOf("left", "يسار")) -> "left"
            containsAny(command, listOf("right", "يمين")) -> "right"
            containsAny(command, listOf("down", "تحت", "اسفل")) -> "down"
            containsAny(command, listOf("scroll", "مرر", "swipe", "اسحب")) -> "down"
            else -> null
        }
    }

    private fun extractTextPayload(rawCommand: String): String? {
        val regex = Regex("\"([^\"]+)\"|'([^']+)'")
        val match = regex.find(rawCommand)
        if (match != null) {
            return match.groupValues.firstOrNull { it.isNotBlank() && it != match.value }
        }
        return null
    }

    private fun containsAny(input: String, keywords: List<String>): Boolean {
        return keywords.any { input.contains(it) }
    }

    private fun resetBundleDir() {
        if (modelBundleDir.exists()) {
            modelBundleDir.deleteRecursively()
        }
        modelBundleDir.mkdirs()
    }

    private fun unzipToBundle(archive: File, destination: File) {
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val normalized = entry.name.replace("\\", "/").trimStart('/')
                if (normalized.isNotBlank() && !normalized.contains("..")) {
                    val outFile = File(destination, normalized)
                    val canonical = outFile.canonicalPath
                    val destinationCanonical = destination.canonicalPath
                    if (!canonical.startsWith(destinationCanonical)) {
                        throw SecurityException("Blocked zip entry outside destination: ${entry.name}")
                    }

                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { output ->
                            zip.copyTo(output)
                        }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    private fun tryCreateOnnxSession(modelPath: String): Any? {
        return try {
            val envClass = Class.forName("ai.onnxruntime.OrtEnvironment")
            val sessionOptionsClass = Class.forName("ai.onnxruntime.OrtSession\$SessionOptions")

            val getEnvironment = envClass.getMethod("getEnvironment")
            val env = getEnvironment.invoke(null)
            environment = env

            val sessionOptions = sessionOptionsClass.getDeclaredConstructor().newInstance()
            val createSession = envClass.getMethod(
                "createSession",
                String::class.java,
                sessionOptionsClass,
            )
            createSession.invoke(env, modelPath, sessionOptions)
        } catch (_: Throwable) {
            null
        }
    }

    @Synchronized
    private fun setStatus(status: String) {
        modelStatus = status
        BridgeEventEmitter.emit(
            "model_state_changed",
            mapOf(
                "model_status" to modelStatus,
                "bundle_has_tokenizer" to hasTokenizerFiles(),
            ),
        )
    }

    private fun hasTokenizerFiles(): Boolean {
        return requiredTokenizerFiles.all { filename ->
            File(modelBundleDir, filename).exists()
        }
    }

    data class AppIntent(
        val appName: String,
        val packageName: String,
    )

    companion object {
        const val MODEL_UNAVAILABLE = "unavailable"
        const val MODEL_DOWNLOADING = "downloading"
        const val MODEL_READY = "ready"
        const val MODEL_FAILED = "failed"

        private val SYSTEM_ACTIONS = setOf(
            "launch_app",
            "back",
            "home",
            "recent_apps",
            "open_notifications",
            "open_quick_settings",
        )

        private val UI_ACTIONS = setOf(
            "click",
            "type",
            "scroll",
            "swipe",
            "long_press",
        )

        private val APP_INTENTS = listOf(
            AppIntent("whatsapp", "com.whatsapp"),
            AppIntent("phone", "com.google.android.dialer"),
            AppIntent("messages", "com.google.android.apps.messaging"),
            AppIntent("chrome", "com.android.chrome"),
            AppIntent("settings", "com.android.settings"),
            AppIntent("camera", "com.google.android.GoogleCamera"),
            AppIntent("youtube", "com.google.android.youtube"),
            AppIntent("gmail", "com.google.android.gm"),
            AppIntent("maps", "com.google.android.apps.maps"),
            AppIntent("play store", "com.android.vending"),
            AppIntent("واتساب", "com.whatsapp"),
            AppIntent("الكاميرا", "com.google.android.GoogleCamera"),
            AppIntent("الرسائل", "com.google.android.apps.messaging"),
            AppIntent("المتصفح", "com.android.chrome"),
            AppIntent("الإعدادات", "com.android.settings"),
        )

        @Volatile
        private var instance: ModelManager? = null

        fun get(context: Context): ModelManager {
            return instance ?: synchronized(this) {
                instance ?: ModelManager(context.applicationContext).also { manager ->
                    instance = manager
                }
            }
        }
    }
}
