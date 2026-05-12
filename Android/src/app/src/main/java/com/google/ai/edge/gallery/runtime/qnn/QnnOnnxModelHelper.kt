package com.google.ai.edge.gallery.runtime.qnn

import ai.onnxruntime.NodeInfo
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.runtime.CleanUpListener
import com.google.ai.edge.gallery.runtime.LlmModelHelper
import com.google.ai.edge.gallery.runtime.ResultListener
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ToolProvider
import java.io.File
import kotlinx.coroutines.CoroutineScope

object QnnOnnxModelHelper : LlmModelHelper {
  private const val TAG = "AGQnnOnnxHelper"

  private val environment: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

  private data class QnnOnnxModelInstance(
    val session: OrtSession,
    val sessionOptions: OrtSession.SessionOptions,
    val accelerator: Accelerator,
    val inputSummary: String,
    val outputSummary: String,
  )

  override fun initialize(
    context: Context,
    model: Model,
    supportImage: Boolean,
    supportAudio: Boolean,
    onDone: (String) -> Unit,
    systemInstruction: Contents?,
    tools: List<ToolProvider>,
    enableConversationConstrainedDecoding: Boolean,
    coroutineScope: CoroutineScope?,
  ) {
    cleanUp(model) {}

    val modelPath = model.getPath(context = context)
    val modelFile = File(modelPath)
    if (!modelFile.exists()) {
      onDone("ONNX model file not found at $modelPath")
      return
    }

    if (!modelFile.name.endsWith(".onnx", ignoreCase = true)) {
      onDone("QNN_ONNX expects a .onnx model file, found ${modelFile.name}")
      return
    }

    val accelerator = resolveAccelerator(model)
    if (accelerator == null) {
      onDone("QNN_ONNX only supports CPU, GPU, or NPU accelerator selections")
      return
    }

    try {
      val sessionOptions = OrtSession.SessionOptions()
      sessionOptions.setLoggerId("AGQnnOnnxHelper-${model.name}")
      sessionOptions.setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_VERBOSE)
      sessionOptions.setSessionLogVerbosityLevel(1)
      val providerOptions = buildProviderOptions(accelerator)
      sessionOptions.addQnn(providerOptions)
      if (accelerator == Accelerator.NPU) {
        sessionOptions.addConfigEntry("session.disable_cpu_ep_fallback", "1")
      }
      val session = environment.createSession(modelPath, sessionOptions)
      val inputSummary = formatNodeInfo(session.inputInfo)
      val outputSummary = formatNodeInfo(session.outputInfo)

      model.instance =
        QnnOnnxModelInstance(
          session = session,
          sessionOptions = sessionOptions,
          accelerator = accelerator,
          inputSummary = inputSummary,
          outputSummary = outputSummary,
        )
      Log.d(TAG, "Initialized QNN ONNX session for ${model.name} on ${accelerator.label}: $inputSummary")
      onDone("")
    } catch (e: Throwable) {
      model.instance = null
      Log.e(TAG, "Failed to initialize QNN ONNX session for ${model.name}", e)
      onDone(toUserFacingInitError(e))
    }
  }

  override fun resetConversation(
    model: Model,
    supportImage: Boolean,
    supportAudio: Boolean,
    systemInstruction: Contents?,
    tools: List<ToolProvider>,
    enableConversationConstrainedDecoding: Boolean,
  ) = Unit

  override fun cleanUp(model: Model, onDone: () -> Unit) {
    val instance = model.instance as? QnnOnnxModelInstance
    if (instance != null) {
      closeQuietly(instance.session, "session")
      closeQuietly(instance.sessionOptions, "sessionOptions")
    }
    model.instance = null
    onDone()
  }

  override fun runInference(
    model: Model,
    input: String,
    resultListener: ResultListener,
    cleanUpListener: CleanUpListener,
    onError: (message: String) -> Unit,
    images: List<Bitmap>,
    audioClips: List<ByteArray>,
    coroutineScope: CoroutineScope?,
    extraContext: Map<String, String>?,
  ) {
    val instance = model.instance as? QnnOnnxModelInstance
    if (instance == null) {
      onError("QNN ONNX model is not initialized")
      return
    }

    onError(
      "QNN ONNX session is initialized on ${instance.accelerator.label}, but text generation is not wired yet. " +
        "Expected inputs: ${instance.inputSummary}. Outputs: ${instance.outputSummary}."
    )
  }

  override fun stopResponse(model: Model) = Unit

  private fun resolveAccelerator(model: Model): Accelerator? {
    val configuredLabel = model.configValues[ConfigKeys.ACCELERATOR.label] as? String
    val configured = Accelerator.entries.firstOrNull { it.label.equals(configuredLabel, ignoreCase = true) }
    val supported = configured ?: model.accelerators.firstOrNull()
    return when (supported) {
      Accelerator.CPU,
      Accelerator.GPU,
      Accelerator.NPU -> supported
      else -> null
    }
  }

  private fun buildProviderOptions(accelerator: Accelerator): Map<String, String> {
    return linkedMapOf<String, String>().apply {
      when (accelerator) {
        Accelerator.NPU -> {
          put("backend_path", "libQnnHtp.so")
          put("htp_performance_mode", "sustained_high_performance")
          put("htp_graph_finalization_optimization_mode", "3")
        }
        Accelerator.GPU -> put("backend_path", "libQnnGpu.so")
        Accelerator.CPU -> put("backend_path", "libQnnCpu.so")
        else -> error("Unsupported accelerator for QNN backend: ${accelerator.label}")
      }
    }
  }

  private fun formatNodeInfo(nodes: Map<String, NodeInfo>): String {
    return nodes.entries.joinToString(separator = "; ") { (name, nodeInfo) ->
      val info = nodeInfo.info
      if (info is TensorInfo) {
        "$name(type=${info.type}, onnxType=${info.onnxType}, shape=${info.shape.contentToString()})"
      } else {
        "$name(${info})"
      }
    }
  }

  private fun closeQuietly(closeable: AutoCloseable, label: String) {
    try {
      closeable.close()
    } catch (e: Exception) {
      Log.w(TAG, "Failed to close QNN ONNX $label cleanly", e)
    }
  }

  private fun toUserFacingInitError(error: Throwable): String {
    val message = error.message.orEmpty()
    return if (message.contains("QNN execution provider is not supported in this build", ignoreCase = true)) {
      "QNN ONNX init failed: this APK is using a standard ONNX Runtime Android build without QNN support. " +
        "Install a custom ONNX Runtime Android AAR built with --use_qnn static_lib, then rebuild the app."
    } else if (
      message.contains("assigned to the default CPU EP", ignoreCase = true) &&
        message.contains("fallback to CPU EP has been explicitly disabled", ignoreCase = true)
    ) {
      "QNN ONNX init failed: some graph nodes are still unsupported by QNN/HTP, so ONNX Runtime tried to place them on CPU. " +
        "CPU fallback is intentionally disabled for NPU validation. Use the new verbose ORT logs to identify the unsupported nodes or switch to a more QNN-friendly export."
    } else {
      "QNN ONNX init failed: ${error.message ?: error.javaClass.simpleName}"
    }
  }
}