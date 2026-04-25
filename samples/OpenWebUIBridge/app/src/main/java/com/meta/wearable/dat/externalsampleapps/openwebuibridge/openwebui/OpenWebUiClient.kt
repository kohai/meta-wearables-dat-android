/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.openwebuibridge.openwebui

import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

sealed interface OpenWebUiResult {
  data class Success(val content: String) : OpenWebUiResult

  data class Failure(val message: String) : OpenWebUiResult
}

sealed interface OpenWebUiModelsResult {
  data class Success(val models: List<String>) : OpenWebUiModelsResult

  data class Failure(val message: String) : OpenWebUiModelsResult
}

class OpenWebUiClient {
  suspend fun listModels(baseUrl: String, apiKey: String): OpenWebUiModelsResult =
      withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) {
          return@withContext OpenWebUiModelsResult.Failure("Open WebUI URL is required")
        }
        if (apiKey.isBlank()) {
          return@withContext OpenWebUiModelsResult.Failure("Open WebUI API key is required")
        }

        val connection =
            try {
              (URL(modelsUrl(baseUrl)).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Accept", "application/json")
              }
            } catch (error: Exception) {
              return@withContext OpenWebUiModelsResult.Failure(
                  "Invalid Open WebUI URL: ${error.message}"
              )
            }

        try {
          val responseCode = connection.responseCode
          val responseText =
              if (responseCode in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
              } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
              }

          if (responseCode !in 200..299) {
            return@withContext OpenWebUiModelsResult.Failure(
                "Open WebUI returned HTTP $responseCode: ${responseText.take(500)}"
            )
          }

          val models = parseModels(responseText)
          if (models.isEmpty()) {
            OpenWebUiModelsResult.Failure("Open WebUI did not return any models")
          } else {
            OpenWebUiModelsResult.Success(models)
          }
        } catch (error: Exception) {
          OpenWebUiModelsResult.Failure("Open WebUI models request failed: ${error.message}")
        } finally {
          connection.disconnect()
        }
      }

  suspend fun askAboutImage(
      baseUrl: String,
      apiKey: String,
      model: String,
      systemPrompt: String,
      prompt: String,
      image: Bitmap,
    ): OpenWebUiResult = ask(
      baseUrl = baseUrl,
      apiKey = apiKey,
      model = model,
      systemPrompt = systemPrompt,
      prompt = prompt,
      image = image,
    )

    suspend fun askText(
      baseUrl: String,
      apiKey: String,
      model: String,
      systemPrompt: String,
      prompt: String,
    ): OpenWebUiResult = ask(
      baseUrl = baseUrl,
      apiKey = apiKey,
      model = model,
      systemPrompt = systemPrompt,
      prompt = prompt,
      image = null,
    )

    private suspend fun ask(
      baseUrl: String,
      apiKey: String,
      model: String,
      systemPrompt: String,
      prompt: String,
      image: Bitmap?,
  ): OpenWebUiResult =
      withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) {
          return@withContext OpenWebUiResult.Failure("Open WebUI URL is required")
        }
        if (apiKey.isBlank()) {
          return@withContext OpenWebUiResult.Failure("Open WebUI API key is required")
        }
        if (model.isBlank()) {
          return@withContext OpenWebUiResult.Failure("Model is required")
        }
        if (prompt.isBlank()) {
          return@withContext OpenWebUiResult.Failure("Prompt is required")
        }

        val connection =
            try {
              (URL(chatCompletionsUrl(baseUrl)).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15_000
                readTimeout = 60_000
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
              }
            } catch (error: Exception) {
              return@withContext OpenWebUiResult.Failure("Invalid Open WebUI URL: ${error.message}")
            }

        try {
          val payload = buildPayload(model, systemPrompt, prompt, image)
          connection.outputStream.use { stream -> stream.write(payload.toByteArray(Charsets.UTF_8)) }

          val responseCode = connection.responseCode
          val responseText =
              if (responseCode in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
              } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
              }

          if (responseCode !in 200..299) {
            return@withContext OpenWebUiResult.Failure(
                "Open WebUI returned HTTP $responseCode: ${responseText.take(500)}"
            )
          }

          val content = parseAssistantContent(responseText)
          if (content.isBlank()) {
            OpenWebUiResult.Failure("Open WebUI response did not include assistant content")
          } else {
            OpenWebUiResult.Success(content)
          }
        } catch (error: Exception) {
          OpenWebUiResult.Failure("Open WebUI request failed: ${error.message}")
        } finally {
          connection.disconnect()
        }
      }

  private fun chatCompletionsUrl(baseUrl: String): String {
    val normalized = baseUrl.trim().trimEnd('/')
    return when {
      normalized.endsWith("/chat/completions") -> normalized
      normalized.endsWith("/api/v1") -> "$normalized/chat/completions"
      normalized.endsWith("/api/chat/completions") -> normalized
      normalized.endsWith("/api") -> "$normalized/chat/completions"
      else -> "$normalized/api/chat/completions"
    }
  }

  private fun modelsUrl(baseUrl: String): String {
    val normalized = baseUrl.trim().trimEnd('/')
    return when {
      normalized.endsWith("/models") -> normalized
      normalized.endsWith("/api/v1") -> "$normalized/models"
      normalized.endsWith("/api") -> "$normalized/models"
      else -> "$normalized/api/models"
    }
  }

  private fun buildPayload(model: String, systemPrompt: String, prompt: String, image: Bitmap?): String {
    val messages = JSONArray()
    if (systemPrompt.isNotBlank()) {
      messages.put(JSONObject().put("role", "system").put("content", systemPrompt))
    }

    val userContent = JSONArray().put(JSONObject().put("type", "text").put("text", prompt))
    if (image != null) {
      val imageBase64 = encodeJpeg(image)
      val imageUrl = "data:image/jpeg;base64,$imageBase64"
      userContent.put(
          JSONObject()
              .put("type", "image_url")
              .put("image_url", JSONObject().put("url", imageUrl))
      )
    }
    messages.put(
        JSONObject()
            .put("role", "user")
            .put("content", userContent)
    )

    return JSONObject()
        .put("model", model)
        .put("stream", false)
        .put("messages", messages)
        .toString()
  }

  private fun encodeJpeg(image: Bitmap): String {
    val scaledImage = scaleToMaxDimension(image, 896)
    val output = ByteArrayOutputStream()
    scaledImage.compress(Bitmap.CompressFormat.JPEG, 82, output)
    if (scaledImage !== image) {
      scaledImage.recycle()
    }
    return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
  }

  private fun scaleToMaxDimension(image: Bitmap, maxDimension: Int): Bitmap {
    val largestSide = maxOf(image.width, image.height)
    if (largestSide <= maxDimension) {
      return image
    }

    val scale = maxDimension.toFloat() / largestSide.toFloat()
    val targetWidth = (image.width * scale).toInt().coerceAtLeast(1)
    val targetHeight = (image.height * scale).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(image, targetWidth, targetHeight, true)
  }

  private fun parseAssistantContent(responseText: String): String {
    val choices = JSONObject(responseText).optJSONArray("choices") ?: return ""
    val content = choices.optJSONObject(0)?.optJSONObject("message")?.opt("content") ?: return ""
    return when (content) {
      is String -> content
      is JSONArray -> content.toTextBlocks()
      else -> content.toString()
    }.trim()
  }

  private fun parseModels(responseText: String): List<String> {
    val trimmed = responseText.trim()
    val data =
        if (trimmed.startsWith("[")) {
          JSONArray(trimmed)
        } else {
          val root = JSONObject(trimmed)
          root.optJSONArray("data") ?: root.optJSONArray("models") ?: JSONArray()
        }
    val models = mutableListOf<String>()
    for (index in 0 until data.length()) {
      when (val item = data.opt(index)) {
        is String -> models += item
        is JSONObject -> {
          val id = item.optString("id").ifBlank { item.optString("name") }
          if (id.isNotBlank()) {
            models += id
          }
        }
      }
    }
    return models.distinct().sorted()
  }

  private fun JSONArray.toTextBlocks(): String {
    val blocks = mutableListOf<String>()
    for (index in 0 until length()) {
      val item = optJSONObject(index) ?: continue
      val text = item.optString("text").ifBlank { item.optString("content") }
      if (text.isNotBlank()) {
        blocks += text
      }
    }
    return blocks.joinToString(separator = "\n")
  }
}
