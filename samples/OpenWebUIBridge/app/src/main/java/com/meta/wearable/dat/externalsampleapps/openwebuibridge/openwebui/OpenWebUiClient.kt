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
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class OpenWebUiChatSession(
    val chatId: String,
    val sessionId: String,
)

data class OpenWebUiImageOptions(
    val maxDimension: Int,
    val jpegQuality: Int,
)

sealed interface OpenWebUiResult {
  data class Success(
      val content: String,
      val chatSession: OpenWebUiChatSession,
  ) : OpenWebUiResult

  data class Failure(val message: String) : OpenWebUiResult
}

sealed interface OpenWebUiModelsResult {
  data class Success(val models: List<String>) : OpenWebUiModelsResult

  data class Failure(val message: String) : OpenWebUiModelsResult
}

private data class HttpResponse(
    val code: Int,
    val body: String,
)

private data class ChatSnapshot(
    val title: String,
    val history: JSONObject,
    val messages: JSONObject,
    val currentId: String?,
)

private data class UploadedFile(
  val id: String,
  val path: String,
  val name: String,
  val size: Int,
)

private sealed interface ChatWriteResult {
  data object Success : ChatWriteResult

  data object Missing : ChatWriteResult

  data class Failure(val message: String) : ChatWriteResult
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

        try {
          val response = request(method = "GET", url = modelsUrl(baseUrl), apiKey = apiKey)
          if (response.code !in 200..299) {
            return@withContext OpenWebUiModelsResult.Failure(
                "Open WebUI returned HTTP ${response.code}: ${response.body.take(500)}"
            )
          }

          val models = parseModels(response.body)
          if (models.isEmpty()) {
            OpenWebUiModelsResult.Failure("Open WebUI did not return any models")
          } else {
            OpenWebUiModelsResult.Success(models)
          }
        } catch (error: Exception) {
          OpenWebUiModelsResult.Failure("Open WebUI models request failed: ${error.message}")
        }
      }

  suspend fun askAboutImage(
      baseUrl: String,
      apiKey: String,
      model: String,
      systemPrompt: String,
      prompt: String,
      image: Bitmap,
      imageOptions: OpenWebUiImageOptions,
      chatSession: OpenWebUiChatSession?,
      chatTitle: String,
  ): OpenWebUiResult =
      ask(
          baseUrl = baseUrl,
          apiKey = apiKey,
          model = model,
          systemPrompt = systemPrompt,
          prompt = prompt,
          image = image,
          imageOptions = imageOptions,
          chatSession = chatSession,
          chatTitle = chatTitle,
      )

  suspend fun askText(
      baseUrl: String,
      apiKey: String,
      model: String,
      systemPrompt: String,
      prompt: String,
      chatSession: OpenWebUiChatSession?,
      chatTitle: String,
  ): OpenWebUiResult =
      ask(
          baseUrl = baseUrl,
          apiKey = apiKey,
          model = model,
          systemPrompt = systemPrompt,
          prompt = prompt,
          image = null,
          imageOptions = null,
          chatSession = chatSession,
          chatTitle = chatTitle,
      )

  private suspend fun ask(
      baseUrl: String,
      apiKey: String,
      model: String,
      systemPrompt: String,
      prompt: String,
      image: Bitmap?,
      imageOptions: OpenWebUiImageOptions?,
      chatSession: OpenWebUiChatSession?,
      chatTitle: String,
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

        try {
          val activeSession =
              when (val result = ensureChatSession(baseUrl, apiKey, model, chatTitle, chatSession)) {
                is OpenWebUiResult.Failure -> return@withContext result
                is OpenWebUiResult.Success -> result.chatSession
              }

          val imageBytes = image?.toJpegBytes(imageOptions ?: OpenWebUiImageOptions(1600, 92))
          val uploadedFile =
              imageBytes?.let { bytes ->
                when (val result = uploadImage(baseUrl, apiKey, bytes)) {
                  is OpenWebUiResult.Failure -> return@withContext result
                  is OpenWebUiResult.Success ->
                      UploadedFile(
                          id = result.content,
                          path = result.chatSession.chatId,
                          name = result.chatSession.sessionId,
                          size = bytes.size,
                      )
                }
              }

          val userMessageId = UUID.randomUUID().toString()
          val assistantMessageId = UUID.randomUUID().toString()
          val preparedSession =
              when (
                  val result =
                      prepareUserMessage(
                          baseUrl = baseUrl,
                          apiKey = apiKey,
                          model = model,
                          title = chatTitle,
                          session = activeSession,
                          userMessageId = userMessageId,
                          prompt = prompt,
                            uploadedFile = uploadedFile,
                      )
              ) {
                ChatWriteResult.Success -> activeSession
                ChatWriteResult.Missing -> {
                  val recreated =
                      when (val result = createChatSession(baseUrl, apiKey, model, chatTitle)) {
                        is OpenWebUiResult.Failure -> return@withContext result
                        is OpenWebUiResult.Success -> result.chatSession
                      }
                  when (
                      val retry =
                          prepareUserMessage(
                              baseUrl = baseUrl,
                              apiKey = apiKey,
                              model = model,
                              title = chatTitle,
                              session = recreated,
                              userMessageId = userMessageId,
                              prompt = prompt,
                                uploadedFile = uploadedFile,
                          )
                  ) {
                    ChatWriteResult.Success -> recreated
                    ChatWriteResult.Missing ->
                        return@withContext OpenWebUiResult.Failure(
                            "Open WebUI chat session disappeared while preparing the request"
                        )
                    is ChatWriteResult.Failure -> return@withContext OpenWebUiResult.Failure(retry.message)
                  }
                }
                is ChatWriteResult.Failure -> return@withContext OpenWebUiResult.Failure(result.message)
              }

          val chatSnapshot = fetchChat(baseUrl, apiKey, preparedSession.chatId)
          val payload =
              buildChatCompletionPayload(
                  model = model,
                  systemPrompt = systemPrompt,
                  prompt = prompt,
                  imageBytes = imageBytes,
                  chatSnapshot = chatSnapshot,
                  chatSession = preparedSession,
                  assistantMessageId = assistantMessageId,
              )
          val response =
              request(
                  method = "POST",
                  url = chatCompletionsUrl(baseUrl),
                  apiKey = apiKey,
                  body = payload,
              )

          if (response.code !in 200..299) {
            return@withContext OpenWebUiResult.Failure(
                "Open WebUI returned HTTP ${response.code}: ${response.body.take(500)}"
            )
          }

            val content =
              pollAssistantMessage(
                baseUrl = baseUrl,
                apiKey = apiKey,
                chatId = preparedSession.chatId,
                assistantMessageId = assistantMessageId,
                timeoutMs = if (image == null) 60_000L else 90_000L,
              ).ifBlank { parseAssistantContent(response.body) }
          if (content.isBlank()) {
            return@withContext OpenWebUiResult.Failure(
                "Open WebUI response did not include assistant content"
            )
          }

          runCatching {
            notifyChatCompleted(
                baseUrl = baseUrl,
                apiKey = apiKey,
                model = model,
                session = preparedSession,
                userMessageId = userMessageId,
                userContent = prompt,
                assistantMessageId = assistantMessageId,
                assistantContent = content,
            )
          }
          runCatching {
            saveAssistantMessage(
                baseUrl = baseUrl,
                apiKey = apiKey,
                model = model,
                title = chatTitle,
                session = preparedSession,
                userMessageId = userMessageId,
                userContent = prompt,
                assistantMessageId = assistantMessageId,
                assistantContent = content,
                uploadedFile = uploadedFile,
            )
          }

          OpenWebUiResult.Success(content = content, chatSession = preparedSession)
        } catch (error: Exception) {
          OpenWebUiResult.Failure("Open WebUI request failed: ${error.message}")
        }
      }

  private fun ensureChatSession(
      baseUrl: String,
      apiKey: String,
      model: String,
      title: String,
      chatSession: OpenWebUiChatSession?,
  ): OpenWebUiResult {
    if (chatSession?.chatId?.isNotBlank() == true && chatSession.sessionId.isNotBlank()) {
      return OpenWebUiResult.Success(content = "", chatSession = chatSession)
    }
    return createChatSession(baseUrl, apiKey, model, title)
  }

  private fun createChatSession(
      baseUrl: String,
      apiKey: String,
      model: String,
      title: String,
  ): OpenWebUiResult {
    val chat =
        JSONObject()
            .put("title", title)
            .put("models", JSONArray().put(model))
            .put("timestamp", System.currentTimeMillis())
            .put(
                "history",
                JSONObject().put("messages", JSONObject()).put("currentId", JSONObject.NULL),
            )
    val response =
        request(
            method = "POST",
            url = apiV1Url(baseUrl, "/chats/new"),
            apiKey = apiKey,
            body = JSONObject().put("chat", chat).toString(),
        )
    if (response.code !in 200..299) {
      return OpenWebUiResult.Failure(
          "Open WebUI chat creation returned HTTP ${response.code}: ${response.body.take(500)}"
      )
    }

    val root = JSONObject(response.body)
    val chatId =
        root.optString("id")
            .ifBlank { root.optString("chat_id") }
            .ifBlank { root.optString("chatId") }
    if (chatId.isBlank()) {
      return OpenWebUiResult.Failure("Open WebUI chat creation did not return a chat id")
    }
    return OpenWebUiResult.Success(
        content = "",
        chatSession = OpenWebUiChatSession(chatId = chatId, sessionId = UUID.randomUUID().toString()),
    )
  }

  private fun prepareUserMessage(
      baseUrl: String,
      apiKey: String,
      model: String,
      title: String,
      session: OpenWebUiChatSession,
      userMessageId: String,
      prompt: String,
        uploadedFile: UploadedFile?,
  ): ChatWriteResult {
    val snapshot =
        try {
          fetchChat(baseUrl, apiKey, session.chatId)
        } catch (error: MissingChatException) {
          return ChatWriteResult.Missing
        } catch (error: Exception) {
          return ChatWriteResult.Failure("Failed to fetch Open WebUI chat: ${error.message}")
        }

    val messages = snapshot.messages
    val previousMessageId = snapshot.currentId
    previousMessageId?.let { parentId -> appendChildId(messages.optJSONObject(parentId), userMessageId) }
    messages.put(
        userMessageId,
        JSONObject()
            .put("id", userMessageId)
            .put("parentId", previousMessageId ?: JSONObject.NULL)
            .put("childrenIds", JSONArray())
            .put("role", "user")
            .put("content", prompt.withImageMarkdown(baseUrl, uploadedFile))
            .put("timestamp", unixTimestampSeconds())
            .put("models", JSONArray().put(model))
            .apply {
              uploadedFile?.let { put("files", JSONArray().put(it.toOpenWebUiFile())) }
            },
    )

    val history = snapshot.history.put("messages", messages).put("currentId", userMessageId)
    val chat =
        JSONObject()
            .put("title", snapshot.title.ifBlank { title })
            .put("history", history)
            .put(
                "messages",
                JSONArray().put(
                    JSONObject()
                        .put("id", userMessageId)
                        .put("role", "user")
                        .put("content", prompt.withImageMarkdown(baseUrl, uploadedFile))
                        .apply {
                          uploadedFile?.let { put("files", JSONArray().put(it.toOpenWebUiFile())) }
                        }
                ),
            )
    return writeChat(baseUrl, apiKey, session.chatId, chat)
  }

  private fun saveAssistantMessage(
      baseUrl: String,
      apiKey: String,
      model: String,
      title: String,
      session: OpenWebUiChatSession,
      userMessageId: String,
      userContent: String,
      assistantMessageId: String,
      assistantContent: String,
        uploadedFile: UploadedFile?,
  ): ChatWriteResult {
    val snapshot =
        try {
          fetchChat(baseUrl, apiKey, session.chatId)
        } catch (error: Exception) {
          return ChatWriteResult.Failure("Failed to refresh Open WebUI chat history: ${error.message}")
        }

    val messages = snapshot.messages
    appendChildId(messages.optJSONObject(userMessageId), assistantMessageId)
    messages.put(
        assistantMessageId,
        JSONObject()
            .put("id", assistantMessageId)
            .put("parentId", userMessageId)
            .put("childrenIds", JSONArray())
            .put("role", "assistant")
            .put("content", assistantContent)
            .put("model", model)
            .put("timestamp", unixTimestampSeconds())
            .put("done", true),
    )

    val history = snapshot.history.put("messages", messages).put("currentId", assistantMessageId)
    val chat =
        JSONObject()
            .put("title", snapshot.title.ifBlank { title })
            .put("history", history)
            .put(
                "messages",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("id", userMessageId)
                            .put("role", "user")
                            .put("content", userContent.withImageMarkdown(baseUrl, uploadedFile))
                            .apply {
                              uploadedFile?.let { put("files", JSONArray().put(it.toOpenWebUiFile())) }
                            }
                    )
                    .put(
                        JSONObject()
                            .put("id", assistantMessageId)
                            .put("role", "assistant")
                            .put("content", assistantContent)
                    ),
            )
    return writeChat(baseUrl, apiKey, session.chatId, chat)
  }

  private fun notifyChatCompleted(
      baseUrl: String,
      apiKey: String,
      model: String,
      session: OpenWebUiChatSession,
      userMessageId: String,
      userContent: String,
      assistantMessageId: String,
      assistantContent: String,
  ) {
    val body =
        JSONObject()
            .put("model", model)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("id", userMessageId).put("role", "user").put("content", userContent))
                    .put(
                        JSONObject()
                            .put("id", assistantMessageId)
                            .put("role", "assistant")
                            .put("content", assistantContent)
                    ),
            )
            .put("chat_id", session.chatId)
            .put("session_id", session.sessionId)
            .put("id", assistantMessageId)
    request(method = "POST", url = apiChatUrl(baseUrl, "/chat/completed"), apiKey = apiKey, body = body.toString())
  }

  private fun fetchChat(baseUrl: String, apiKey: String, chatId: String): ChatSnapshot {
    val response = request(method = "GET", url = apiV1Url(baseUrl, "/chats/$chatId"), apiKey = apiKey)
    if (response.code == 401 || response.code == 404) {
      throw MissingChatException()
    }
    if (response.code !in 200..299) {
      throw IllegalStateException("HTTP ${response.code}: ${response.body.take(500)}")
    }

    val root = JSONObject(response.body)
    val chat = root.optJSONObject("chat") ?: root
    val history = chat.optJSONObject("history") ?: JSONObject()
    val messages = history.optJSONObject("messages") ?: JSONObject()
    val currentId = if (history.isNull("currentId")) null else history.optString("currentId").ifBlank { null }
    return ChatSnapshot(
        title = chat.optString("title"),
        history = history,
        messages = messages,
        currentId = currentId,
    )
  }

  private fun writeChat(baseUrl: String, apiKey: String, chatId: String, chat: JSONObject): ChatWriteResult {
    val response =
        request(
            method = "POST",
            url = apiV1Url(baseUrl, "/chats/$chatId"),
            apiKey = apiKey,
            body = JSONObject().put("chat", chat).toString(),
        )
    return when {
      response.code in 200..299 -> ChatWriteResult.Success
      response.code == 401 || response.code == 404 -> ChatWriteResult.Missing
      else -> ChatWriteResult.Failure("Open WebUI chat update returned HTTP ${response.code}: ${response.body.take(500)}")
    }
  }

  private fun buildChatCompletionPayload(
      model: String,
      systemPrompt: String,
      prompt: String,
      imageBytes: ByteArray?,
      chatSnapshot: ChatSnapshot,
      chatSession: OpenWebUiChatSession,
      assistantMessageId: String,
  ): String {
    val messages = JSONArray()
    if (systemPrompt.isNotBlank()) {
      messages.put(JSONObject().put("role", "system").put("content", systemPrompt))
    }

    chatSnapshot.historyMessagesInCurrentBranch().forEach { message ->
      val role = message.optString("role")
      val content = message.optString("content")
      if ((role == "user" || role == "assistant") && content.isNotBlank()) {
        messages.put(JSONObject().put("role", role).put("content", content))
      }
    }

    if (messages.length() == 0 || chatSnapshot.currentId == null) {
      messages.put(buildUserPayload(prompt, imageBytes))
    } else if (imageBytes != null) {
      messages.remove(messages.length() - 1)
      messages.put(buildUserPayload(prompt, imageBytes))
    }

    return JSONObject()
        .put("model", model)
        .put("stream", true)
        .put("messages", messages)
        .put("chat_id", chatSession.chatId)
        .put("id", assistantMessageId)
        .put("session_id", chatSession.sessionId)
        .toString()
  }

  private fun pollAssistantMessage(
      baseUrl: String,
      apiKey: String,
      chatId: String,
      assistantMessageId: String,
      timeoutMs: Long,
  ): String {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    while (System.nanoTime() < deadline) {
      Thread.sleep(1_000L)
      val snapshot =
          try {
            fetchChat(baseUrl, apiKey, chatId)
          } catch (_: Exception) {
            continue
          }
      val message = snapshot.messages.optJSONObject(assistantMessageId) ?: continue
      val content = message.optString("content").trim()
      if (content.isNotBlank()) {
        return content
      }
    }
    return ""
  }

  private fun buildUserPayload(prompt: String, imageBytes: ByteArray?): JSONObject {
    if (imageBytes == null) {
      return JSONObject().put("role", "user").put("content", prompt)
    }

    val imageBase64 = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
    val imageUrl = "data:image/jpeg;base64,$imageBase64"
    return JSONObject()
        .put("role", "user")
        .put(
            "content",
            JSONArray()
                .put(JSONObject().put("type", "text").put("text", prompt))
                .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", imageUrl))),
        )
  }

  private fun uploadImage(baseUrl: String, apiKey: String, imageBytes: ByteArray): OpenWebUiResult {
    val fileName = "bridge_${System.currentTimeMillis()}.jpg"
    val boundary = "----OpenWebUIBridge${UUID.randomUUID()}"
    val bodyStart =
        "--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"file\"; filename=\"$fileName\"\r\n" +
            "Content-Type: image/jpeg\r\n\r\n"
    val bodyEnd = "\r\n--$boundary--\r\n"
    val body = ByteArrayOutputStream()
    body.write(bodyStart.toByteArray(Charsets.UTF_8))
    body.write(imageBytes)
    body.write(bodyEnd.toByteArray(Charsets.UTF_8))

    val response =
        request(
            method = "POST",
            url = apiV1Url(baseUrl, "/files/"),
            apiKey = apiKey,
            body = body.toByteArray(),
            contentType = "multipart/form-data; boundary=$boundary",
        )
    if (response.code !in 200..299) {
      return OpenWebUiResult.Failure(
          "Open WebUI image upload returned HTTP ${response.code}: ${response.body.take(500)}"
      )
    }

    val root = JSONObject(response.body)
    val fileId = root.optString("id")
    if (fileId.isBlank()) {
      return OpenWebUiResult.Failure("Open WebUI image upload did not return a file id")
    }
    return OpenWebUiResult.Success(
        content = fileId,
        chatSession =
            OpenWebUiChatSession(
                chatId = root.optString("path").ifBlank { fileId },
                sessionId = fileName,
            ),
    )
  }

  private fun String.withImageMarkdown(baseUrl: String, uploadedFile: UploadedFile?): String {
    if (uploadedFile == null) {
      return this
    }
    return "$this\n\n![image](${apiV1Url(baseUrl, "/files/${uploadedFile.id}/content")})"
  }

  private fun UploadedFile.toOpenWebUiFile(): JSONObject =
      JSONObject()
          .put("id", id)
          .put("type", "image")
          .put("name", name)
          .put("status", "uploaded")
          .put("size", size)
          .put(
              "file",
              JSONObject()
                  .put("id", id)
                  .put("path", path)
                  .put(
                      "meta",
                      JSONObject()
                          .put("content_type", "image/jpeg")
                          .put("name", name)
                          .put("size", size),
                  ),
          )

  private fun ChatSnapshot.historyMessagesInCurrentBranch(): List<JSONObject> {
    val current = currentId ?: return emptyList()
    val branch = mutableListOf<JSONObject>()
    var nextId: String? = current
    while (nextId != null) {
      val message = messages.optJSONObject(nextId) ?: break
      branch += message
      nextId = if (message.isNull("parentId")) null else message.optString("parentId").ifBlank { null }
    }
    return branch.asReversed()
  }

  private fun appendChildId(message: JSONObject?, childId: String) {
    if (message == null) {
      return
    }
    val children = message.optJSONArray("childrenIds") ?: JSONArray().also { message.put("childrenIds", it) }
    for (index in 0 until children.length()) {
      if (children.optString(index) == childId) {
        return
      }
    }
    children.put(childId)
  }

    private fun request(
      method: String,
      url: String,
      apiKey: String,
      body: String? = null,
    ): HttpResponse = request(method, url, apiKey, body?.toByteArray(Charsets.UTF_8), "application/json")

    private fun request(
      method: String,
      url: String,
      apiKey: String,
      body: ByteArray?,
      contentType: String,
    ): HttpResponse {
    val connection =
        (URL(url).openConnection() as HttpURLConnection).apply {
          requestMethod = method
          connectTimeout = 15_000
          readTimeout = 90_000
          setRequestProperty("Authorization", "Bearer $apiKey")
          setRequestProperty("Accept", "application/json")
          if (body != null) {
            doOutput = true
            setRequestProperty("Content-Type", contentType)
          }
        }
    try {
      if (body != null) {
        connection.outputStream.use { stream -> stream.write(body) }
      }
      val responseCode = connection.responseCode
      val responseText =
          if (responseCode in 200..299) {
            connection.inputStream.bufferedReader().use { it.readText() }
          } else {
            connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
          }
      return HttpResponse(code = responseCode, body = responseText)
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
      normalized.endsWith("/api") -> "${normalized.removeSuffix("/api")}/api/v1/chat/completions"
      else -> "$normalized/api/v1/chat/completions"
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

  private fun apiV1Url(baseUrl: String, path: String): String = "${serverRoot(baseUrl)}/api/v1$path"

  private fun apiChatUrl(baseUrl: String, path: String): String = "${serverRoot(baseUrl)}/api$path"

  private fun serverRoot(baseUrl: String): String {
    val normalized = baseUrl.trim().trimEnd('/')
    return when {
      normalized.endsWith("/api/v1/chat/completions") -> normalized.removeSuffix("/api/v1/chat/completions")
      normalized.endsWith("/api/chat/completions") -> normalized.removeSuffix("/api/chat/completions")
      normalized.endsWith("/api/v1") -> normalized.removeSuffix("/api/v1")
      normalized.endsWith("/api") -> normalized.removeSuffix("/api")
      else -> normalized
    }
  }

  private fun Bitmap.toJpegBytes(imageOptions: OpenWebUiImageOptions): ByteArray {
    val scaledImage = scaleToMaxDimension(this, imageOptions.maxDimension)
    val output = ByteArrayOutputStream()
    scaledImage.compress(Bitmap.CompressFormat.JPEG, imageOptions.jpegQuality.coerceIn(0, 100), output)
    if (scaledImage !== this) {
      scaledImage.recycle()
    }
    return output.toByteArray()
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
    val trimmed = responseText.trim()
    if (trimmed.isBlank()) {
      return ""
    }
    val root = JSONObject(trimmed)
    val choices = root.optJSONArray("choices")
    if (choices != null) {
      val content = choices.optJSONObject(0)?.optJSONObject("message")?.opt("content") ?: return ""
      return contentToText(content)
    }
    return root.optString("content").ifBlank { root.optString("text") }.trim()
  }

  private fun contentToText(content: Any): String =
      when (content) {
        is String -> content
        is JSONArray -> content.toTextBlocks()
        else -> content.toString()
      }.trim()

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

  private fun unixTimestampSeconds(): Long = System.currentTimeMillis() / 1000L
}

private class MissingChatException : Exception()
