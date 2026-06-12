package com.google.ai.edge.gallery.worker

import android.content.Context
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelAllowlist
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val MODEL_ALLOWLIST_FILENAME = "model_allowlist.json"

@Singleton
class GemmaModelResolver @Inject constructor(
  @ApplicationContext private val context: Context,
) {
  fun resolveDownloadedGemmaModel(): Model? {
    val allowlistFile = File(context.getExternalFilesDir(null), MODEL_ALLOWLIST_FILENAME)
    if (allowlistFile.exists()) {
      val allowlist = Gson().fromJson(allowlistFile.readText(), ModelAllowlist::class.java)
      if (allowlist != null) {
        val localGemma = allowlist.models
          .asSequence()
          .map { it.toModel() }
          .filter { model ->
            val displayName = model.displayName.ifEmpty { model.name }
            displayName.contains("gemma", ignoreCase = true) || model.name.contains("gemma", ignoreCase = true)
          }
          .firstOrNull { model -> File(model.getPath(context)).exists() }
        if (localGemma != null) {
          return localGemma
        }
      }
    }
    return Model(
      name = "OpenRouter Llama 4 Scout",
      displayName = "OpenRouter Llama 4 Scout"
    )
  }
}
