/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// StreamViewModel - DAT Camera Streaming API Demo
//
// This ViewModel demonstrates the DAT Camera Streaming APIs for:
// - Creating and managing stream sessions with wearable devices
// - Receiving video frames from device cameras
// - Capturing photos during streaming sessions
// - Handling different video qualities and formats
// - Processing raw video data (I420 -> ARGB conversion)

package com.meta.wearable.dat.externalsampleapps.openwebuibridge.stream

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addStream
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamError
import com.meta.wearable.dat.camera.types.StreamSessionState
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.DeviceSelector
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.session.Session
import com.meta.wearable.dat.externalsampleapps.openwebuibridge.openwebui.OpenWebUiChatSession
import com.meta.wearable.dat.externalsampleapps.openwebuibridge.openwebui.OpenWebUiClient
import com.meta.wearable.dat.externalsampleapps.openwebuibridge.openwebui.OpenWebUiImageOptions
import com.meta.wearable.dat.externalsampleapps.openwebuibridge.openwebui.OpenWebUiModelsResult
import com.meta.wearable.dat.externalsampleapps.openwebuibridge.openwebui.OpenWebUiResult
import com.meta.wearable.dat.externalsampleapps.openwebuibridge.wearables.WearablesViewModel
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@SuppressLint("AutoCloseableUse")
class StreamViewModel(
    application: Application,
    private val wearablesViewModel: WearablesViewModel,
) : AndroidViewModel(application) {

  companion object {
    private const val TAG = "OpenWebUIBridge:StreamViewModel"
    private const val SETTINGS_NAME = "open_webui_bridge_settings"
    private const val KEY_OPEN_WEB_UI_BASE_URL = "open_web_ui_base_url"
    private const val KEY_OPEN_WEB_UI_API_KEY = "open_web_ui_api_key"
    private const val KEY_OPEN_WEB_UI_MODEL = "open_web_ui_model"
    private const val KEY_OPEN_WEB_UI_CHAT_ID = "open_web_ui_chat_id"
    private const val KEY_OPEN_WEB_UI_SESSION_ID = "open_web_ui_session_id"
    private const val KEY_AUTO_SPEAK_RESPONSE = "auto_speak_response"
    private const val KEY_SNAPSHOT_IMAGE_QUALITY = "snapshot_image_quality"
    private const val KEY_OPEN_WEB_UI_SYSTEM_PROMPT = "open_web_ui_system_prompt"
    private const val KEY_OPEN_WEB_UI_PROMPT = "open_web_ui_prompt"
    private val SESSION_TERMINAL_STATES = setOf(StreamSessionState.CLOSED)

    private fun readSnapshotImageQuality(value: String?): SnapshotImageQuality =
        SnapshotImageQuality.values().firstOrNull { it.name == value } ?: SnapshotImageQuality.HIGH
  }

  private val deviceSelector: DeviceSelector = wearablesViewModel.deviceSelector
  private var session: Session? = null
  private val openWebUiClient = OpenWebUiClient()
    private val settings: SharedPreferences =
      application.getSharedPreferences(SETTINGS_NAME, Context.MODE_PRIVATE)
    private val initialState =
      StreamUiState(
        openWebUiBaseUrl =
          settings.getString(KEY_OPEN_WEB_UI_BASE_URL, StreamUiState().openWebUiBaseUrl)
            ?: StreamUiState().openWebUiBaseUrl,
        openWebUiApiKey = settings.getString(KEY_OPEN_WEB_UI_API_KEY, "").orEmpty(),
        openWebUiModel = settings.getString(KEY_OPEN_WEB_UI_MODEL, "").orEmpty(),
        openWebUiChatId = settings.getString(KEY_OPEN_WEB_UI_CHAT_ID, "").orEmpty(),
        openWebUiSessionId = settings.getString(KEY_OPEN_WEB_UI_SESSION_ID, "").orEmpty(),
        isAutoSpeakResponseEnabled = settings.getBoolean(KEY_AUTO_SPEAK_RESPONSE, false),
        snapshotImageQuality =
          readSnapshotImageQuality(settings.getString(KEY_SNAPSHOT_IMAGE_QUALITY, null)),
        openWebUiSystemPrompt =
          settings.getString(KEY_OPEN_WEB_UI_SYSTEM_PROMPT, StreamUiState().openWebUiSystemPrompt)
            ?: StreamUiState().openWebUiSystemPrompt,
        openWebUiPrompt =
          settings.getString(KEY_OPEN_WEB_UI_PROMPT, StreamUiState().openWebUiPrompt)
            ?: StreamUiState().openWebUiPrompt,
      )

    private val _uiState = MutableStateFlow(initialState)
  val uiState: StateFlow<StreamUiState> = _uiState.asStateFlow()

  private var videoJob: Job? = null
  private var stateJob: Job? = null
  private var errorJob: Job? = null
  private var sessionStateJob: Job? = null
  private var stream: Stream? = null
  private var speechRecognizer: SpeechRecognizer? = null
  private var textToSpeech: TextToSpeech? = null
  private var isCameraStreamRequested = false
  private var isStoppingCameraOnly = false
  private var isStoppingBridge = false

  // Presentation queue for buffering frames after color conversion
  private var presentationQueue: PresentationQueue? = null

  fun updateOpenWebUiBaseUrl(value: String) {
    _uiState.update {
      it.copy(
          openWebUiBaseUrl = value,
          openWebUiChatId = "",
          openWebUiSessionId = "",
          openWebUiError = null,
      )
    }
    settings.edit()
        .putString(KEY_OPEN_WEB_UI_BASE_URL, value)
        .remove(KEY_OPEN_WEB_UI_CHAT_ID)
        .remove(KEY_OPEN_WEB_UI_SESSION_ID)
        .apply()
  }

  fun updateOpenWebUiApiKey(value: String) {
    _uiState.update {
      it.copy(
          openWebUiApiKey = value,
          openWebUiChatId = "",
          openWebUiSessionId = "",
          openWebUiError = null,
      )
    }
    settings.edit()
        .putString(KEY_OPEN_WEB_UI_API_KEY, value)
        .remove(KEY_OPEN_WEB_UI_CHAT_ID)
        .remove(KEY_OPEN_WEB_UI_SESSION_ID)
        .apply()
  }

  fun updateOpenWebUiModel(value: String) {
    _uiState.update {
      it.copy(
          openWebUiModel = value,
          openWebUiChatId = "",
          openWebUiSessionId = "",
          openWebUiError = null,
      )
    }
    settings.edit()
        .putString(KEY_OPEN_WEB_UI_MODEL, value)
        .remove(KEY_OPEN_WEB_UI_CHAT_ID)
        .remove(KEY_OPEN_WEB_UI_SESSION_ID)
        .apply()
  }

  fun startNewOpenWebUiChat() {
    _uiState.update {
      it.copy(openWebUiChatId = "", openWebUiSessionId = "", openWebUiError = null)
    }
    settings.edit().remove(KEY_OPEN_WEB_UI_CHAT_ID).remove(KEY_OPEN_WEB_UI_SESSION_ID).apply()
  }

  fun updateOpenWebUiPrompt(value: String) {
    _uiState.update { it.copy(openWebUiPrompt = value, openWebUiError = null) }
    settings.edit().putString(KEY_OPEN_WEB_UI_PROMPT, value).apply()
  }

  fun updateOpenWebUiSystemPrompt(value: String) {
    _uiState.update { it.copy(openWebUiSystemPrompt = value, openWebUiError = null) }
    settings.edit().putString(KEY_OPEN_WEB_UI_SYSTEM_PROMPT, value).apply()
  }

  fun updateAutoSpeakResponseEnabled(value: Boolean) {
    _uiState.update { it.copy(isAutoSpeakResponseEnabled = value, openWebUiError = null) }
    settings.edit().putBoolean(KEY_AUTO_SPEAK_RESPONSE, value).apply()
  }

  fun updateSnapshotImageQuality(value: SnapshotImageQuality) {
    _uiState.update { it.copy(snapshotImageQuality = value, openWebUiError = null) }
    settings.edit().putString(KEY_SNAPSHOT_IMAGE_QUALITY, value.name).apply()
  }

  fun speakResponse() {
    val response = _uiState.value.openWebUiResponse
    if (response.isNullOrBlank()) {
      _uiState.update { it.copy(openWebUiError = "No response to speak") }
      return
    }

    speakText(response)
  }

  private fun speakText(response: String) {
    routeAudioToBluetooth()
    _uiState.update { it.copy(isSpeakingResponse = true, openWebUiError = null) }
    val existingTextToSpeech = textToSpeech
    if (existingTextToSpeech != null) {
      existingTextToSpeech.speak(response, TextToSpeech.QUEUE_FLUSH, null, "openwebui-response")
      return
    }

    textToSpeech =
        TextToSpeech(getApplication<Application>()) { status ->
          val tts = textToSpeech
          if (status == TextToSpeech.SUCCESS && tts != null) {
            tts.language = Locale.getDefault()
            tts.setOnUtteranceProgressListener(
                object : UtteranceProgressListener() {
                  override fun onStart(utteranceId: String?) = Unit

                  override fun onDone(utteranceId: String?) {
                    _uiState.update { it.copy(isSpeakingResponse = false) }
                  }

                  @Deprecated("Deprecated in Java")
                  override fun onError(utteranceId: String?) {
                    _uiState.update { it.copy(isSpeakingResponse = false) }
                  }
                }
            )
            tts.speak(response, TextToSpeech.QUEUE_FLUSH, null, "openwebui-response")
          } else {
            _uiState.update {
              it.copy(isSpeakingResponse = false, openWebUiError = "Text to speech is not available")
            }
          }
        }
  }

  fun stopSpeakingResponse() {
    textToSpeech?.stop()
    _uiState.update { it.copy(isSpeakingResponse = false) }
  }

  private fun maybeSpeakResponse(response: String) {
    if (_uiState.value.isAutoSpeakResponseEnabled) {
      speakText(response)
    }
  }

  private fun currentOpenWebUiChatSession(): OpenWebUiChatSession? {
    val state = _uiState.value
    if (state.openWebUiChatId.isBlank() || state.openWebUiSessionId.isBlank()) {
      return null
    }
    return OpenWebUiChatSession(
        chatId = state.openWebUiChatId,
        sessionId = state.openWebUiSessionId,
    )
  }

  private fun persistOpenWebUiChatSession(chatSession: OpenWebUiChatSession) {
    _uiState.update {
      it.copy(
          openWebUiChatId = chatSession.chatId,
          openWebUiSessionId = chatSession.sessionId,
      )
    }
    settings.edit()
        .putString(KEY_OPEN_WEB_UI_CHAT_ID, chatSession.chatId)
        .putString(KEY_OPEN_WEB_UI_SESSION_ID, chatSession.sessionId)
        .apply()
  }

  fun refreshOpenWebUiModels() {
    if (_uiState.value.isLoadingOpenWebUiModels) {
      return
    }

    val state = _uiState.value
    _uiState.update { it.copy(isLoadingOpenWebUiModels = true, openWebUiError = null) }
    viewModelScope.launch {
      when (
          val result =
              openWebUiClient.listModels(
                  baseUrl = state.openWebUiBaseUrl,
                  apiKey = state.openWebUiApiKey,
              )
      ) {
        is OpenWebUiModelsResult.Success ->
            _uiState.update {
              val selectedModel =
                  if (it.openWebUiModel in result.models) {
                    it.openWebUiModel
                  } else {
                    result.models.firstOrNull().orEmpty()
                  }
              settings.edit().putString(KEY_OPEN_WEB_UI_MODEL, selectedModel).apply()
              it.copy(
                  isLoadingOpenWebUiModels = false,
                  openWebUiModels = result.models,
                  openWebUiModel = selectedModel,
              )
            }
        is OpenWebUiModelsResult.Failure ->
            _uiState.update {
              it.copy(isLoadingOpenWebUiModels = false, openWebUiError = result.message)
            }
      }
    }
  }

  fun askOpenWebUiAboutCurrentFrame() {
    if (_uiState.value.isAskingOpenWebUi) {
      return
    }

    val state = _uiState.value
    val frame =
        state.videoFrame?.copy(state.videoFrame.config ?: Bitmap.Config.ARGB_8888, false)
            ?: run {
              _uiState.update { it.copy(openWebUiError = "No video frame available yet") }
              return
            }

    stopSpeakingResponse()
    _uiState.update {
      it.copy(isAskingOpenWebUi = true, openWebUiError = null, openWebUiResponse = null)
    }

    viewModelScope.launch {
      val result = tryAskOpenWebUiAboutImage(state.openWebUiPrompt, frame)
      when (result) {
        is OpenWebUiResult.Success -> {
          persistOpenWebUiChatSession(result.chatSession)
          _uiState.update { it.copy(isAskingOpenWebUi = false, openWebUiResponse = result.content) }
          maybeSpeakResponse(result.content)
        }
        is OpenWebUiResult.Failure ->
            _uiState.update { it.copy(isAskingOpenWebUi = false, openWebUiError = result.message) }
      }
    }
  }

  fun askOpenWebUiAboutSnapshot(promptOverride: String? = null) {
    if (_uiState.value.isAskingOpenWebUi || _uiState.value.isCapturing) {
      return
    }

    val state = _uiState.value
    if (state.streamSessionState != StreamSessionState.STREAMING) {
      _uiState.update { it.copy(openWebUiError = "Stream must be active before asking") }
      return
    }

    val prompt = promptOverride?.ifBlank { null } ?: state.openWebUiPrompt
    stopSpeakingResponse()
    _uiState.update {
      it.copy(
          isAskingOpenWebUi = true,
          isCapturing = true,
          openWebUiError = null,
          openWebUiResponse = null,
      )
    }

    viewModelScope.launch {
      val result = stream?.capturePhoto()
      if (result == null) {
        _uiState.update {
          it.copy(
              isAskingOpenWebUi = false,
              isCapturing = false,
              openWebUiError = "No active stream available for snapshot",
          )
        }
        return@launch
      }

      result
          .onSuccess { photoData ->
            val image = decodePhotoData(photoData)
            val openWebUiResult = tryAskOpenWebUiAboutImage(prompt, image)
            when (openWebUiResult) {
              is OpenWebUiResult.Success -> {
                persistOpenWebUiChatSession(openWebUiResult.chatSession)
                _uiState.update {
                  it.copy(
                      isAskingOpenWebUi = false,
                      isCapturing = false,
                      openWebUiPrompt = prompt,
                      openWebUiResponse = openWebUiResult.content,
                      isSpeakingResponse = false,
                  )
                }
                maybeSpeakResponse(openWebUiResult.content)
              }
              is OpenWebUiResult.Failure ->
                  _uiState.update {
                    it.copy(
                        isAskingOpenWebUi = false,
                        isCapturing = false,
                        openWebUiPrompt = prompt,
                        openWebUiError = openWebUiResult.message,
                    )
                  }
            }
          }
          .onFailure { error, _ ->
            Log.e(TAG, "Snapshot capture failed: ${error.description}")
            _uiState.update {
              it.copy(
                  isAskingOpenWebUi = false,
                  isCapturing = false,
                  openWebUiError = "Snapshot capture failed: ${error.description}",
              )
            }
          }
    }
  }

  private fun askOpenWebUiAboutText(prompt: String) {
    if (_uiState.value.isAskingOpenWebUi) {
      return
    }

    stopSpeakingResponse()
    _uiState.update {
      it.copy(
          isAskingOpenWebUi = true,
          openWebUiError = null,
          openWebUiResponse = null,
          voiceTranscript = prompt,
      )
    }

    viewModelScope.launch {
      val result = tryAskOpenWebUiAboutText(prompt)
      when (result) {
        is OpenWebUiResult.Success -> {
          persistOpenWebUiChatSession(result.chatSession)
          _uiState.update { it.copy(isAskingOpenWebUi = false, openWebUiResponse = result.content) }
          maybeSpeakResponse(result.content)
        }
        is OpenWebUiResult.Failure ->
            _uiState.update { it.copy(isAskingOpenWebUi = false, openWebUiError = result.message) }
      }
    }
  }

  fun startVoiceAsk() {
    if (_uiState.value.isListeningForVoice || _uiState.value.isAskingOpenWebUi) {
      return
    }

    val context = getApplication<Application>()
    if (!SpeechRecognizer.isRecognitionAvailable(context)) {
      _uiState.update { it.copy(openWebUiError = "Speech recognition is not available") }
      return
    }

    routeAudioToBluetooth()
    val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
    speechRecognizer?.destroy()
    speechRecognizer = recognizer
    recognizer.setRecognitionListener(createRecognitionListener())

    val intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
          putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
          putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
          putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask Open WebUI")
        }

    _uiState.update {
      it.copy(isListeningForVoice = true, voiceTranscript = null, openWebUiError = null)
    }
    recognizer.startListening(intent)
  }

  fun stopVoiceAsk() {
    speechRecognizer?.stopListening()
    speechRecognizer?.destroy()
    speechRecognizer = null
    _uiState.update { it.copy(isListeningForVoice = false) }
  }

  fun startStream() {
    if (session != null) {
      return
    }

    isStoppingBridge = false
    if (session == null) {
      Wearables.createSession(deviceSelector)
          .onSuccess { createdSession ->
            session = createdSession
            session?.start()
          }
          .onFailure { error, _ -> Log.e(TAG, "Failed to create session: ${error.description}") }
      if (session == null) return
    }
    startBridgeSessionMonitor()
  }

  fun toggleCameraStream() {
    if (stream == null) {
      startCameraStream()
    } else {
      stopCameraStream(keepBridge = true)
    }
  }

  fun startCameraStream() {
    if (stream != null || isCameraStreamRequested) {
      return
    }

    isCameraStreamRequested = true
    if (session?.state?.value == DeviceSessionState.STARTED) {
      startCameraStreamInternal()
    }
  }

  private fun startBridgeSessionMonitor() {
    Log.d(TAG, "startBridgeSessionMonitor() - collecting session state")
    sessionStateJob?.cancel()
    sessionStateJob =
        viewModelScope.launch {
          session?.state?.collect { currentState ->
            _uiState.update { it.copy(isBridgeRunning = currentState == DeviceSessionState.STARTED) }
            if (currentState == DeviceSessionState.STARTED) {
              if (isCameraStreamRequested && stream == null) {
                startCameraStreamInternal()
              }
            } else if (currentState == DeviceSessionState.STOPPED) {
              stopCameraStream()
              session = null
              if (isStoppingCameraOnly && !isStoppingBridge) {
                isStoppingCameraOnly = false
                startStream()
              } else if (!isStoppingBridge) {
                wearablesViewModel.navigateToDeviceSelection()
              }
            }
          }
        }
  }

  private fun startCameraStreamInternal() {
    videoJob?.cancel()
    stateJob?.cancel()
    errorJob?.cancel()
    presentationQueue?.stop()
    presentationQueue = null

    val queue =
        PresentationQueue(
            bufferDelayMs = 100L,
            maxQueueSize = 15,
            onFrameReady = { frame ->
              _uiState.update {
                it.copy(videoFrame = frame.bitmap, videoFrameCount = it.videoFrameCount + 1)
              }
            },
        )
    presentationQueue = queue
    queue.start()

    session
        ?.addStream(StreamConfiguration(videoQuality = VideoQuality.MEDIUM, 24))
        ?.onSuccess { addedStream ->
          stream = addedStream
          videoJob =
              viewModelScope.launch {
                Log.d(TAG, "Collecting video frames from stream")
                stream?.videoStream?.collect { handleVideoFrame(it) }
                Log.d(TAG, "Video stream collection ended")
              }
          stateJob =
              viewModelScope.launch {
                stream?.state?.collect { currentState ->
                  val prevState = _uiState.value.streamSessionState
                  Log.d(TAG, "Stream state changed: $prevState -> $currentState")
                  _uiState.update { it.copy(streamSessionState = currentState) }

                  val wasActive = prevState !in SESSION_TERMINAL_STATES
                  val isTerminated = currentState in SESSION_TERMINAL_STATES
                  if (wasActive && isTerminated) {
                    Log.d(TAG, "Camera stream terminal state reached")
                    stopCameraStream()
                  }
                }
              }
          errorJob =
              viewModelScope.launch {
                stream?.errorStream?.collect { error ->
                  Log.d(TAG, "Stream error received: $error (description: ${error.description})")
                  if (error == StreamError.HINGE_CLOSED) {
                    Log.d(TAG, "HINGE_CLOSED detected, stopping bridge")
                    stopStream()
                    wearablesViewModel.navigateToDeviceSelection()
                  }
                }
              }
          stream?.start()
        }
        ?.onFailure { error, _ ->
          isCameraStreamRequested = false
          _uiState.update { it.copy(openWebUiError = "Failed to start camera: ${error.description}") }
          Log.e(TAG, "Failed to add stream to session: ${error.description}")
        }
  }

  fun stopCameraStream(keepBridge: Boolean = false) {
    if (keepBridge) {
      isStoppingCameraOnly = true
    }
    isCameraStreamRequested = false
    videoJob?.cancel()
    videoJob = null
    stateJob?.cancel()
    stateJob = null
    errorJob?.cancel()
    errorJob = null
    presentationQueue?.stop()
    presentationQueue = null
    stream?.stop()
    stream = null
    _uiState.update { it.copy(streamSessionState = StreamSessionState.STOPPED, videoFrame = null) }
  }

  fun stopStream() {
    isStoppingBridge = true
    stopVoiceAsk()
    stopSpeakingResponse()
    stopCameraStream()
    sessionStateJob?.cancel()
    sessionStateJob = null
    _uiState.update {
        initialState.copy(
          openWebUiBaseUrl = it.openWebUiBaseUrl,
          openWebUiApiKey = it.openWebUiApiKey,
          openWebUiModel = it.openWebUiModel,
          isAutoSpeakResponseEnabled = it.isAutoSpeakResponseEnabled,
          openWebUiSystemPrompt = it.openWebUiSystemPrompt,
          openWebUiPrompt = it.openWebUiPrompt,
          snapshotImageQuality = it.snapshotImageQuality,
          openWebUiChatId = it.openWebUiChatId,
          openWebUiSessionId = it.openWebUiSessionId,
          openWebUiModels = it.openWebUiModels,
      )
    }
    session?.stop()
    session = null
  }

  fun capturePhoto() {
    if (uiState.value.isCapturing) {
      Log.d(TAG, "Photo capture already in progress, ignoring request")
      return
    }

    if (uiState.value.streamSessionState == StreamSessionState.STREAMING) {
      Log.d(TAG, "Starting photo capture")
      _uiState.update { it.copy(isCapturing = true) }

      viewModelScope.launch {
        stream
            ?.capturePhoto()
            ?.onSuccess { photoData ->
              Log.d(TAG, "Photo capture successful")
              handlePhotoData(photoData)
              _uiState.update { it.copy(isCapturing = false) }
            }
            ?.onFailure { error, _ ->
              Log.e(TAG, "Photo capture failed: ${error.description}")
              _uiState.update { it.copy(isCapturing = false) }
            }
      }
    } else {
      Log.w(
          TAG,
          "Cannot capture photo: stream not active (state=${uiState.value.streamSessionState})",
      )
    }
  }

  fun showShareDialog() {
    _uiState.update { it.copy(isShareDialogVisible = true) }
  }

  fun hideShareDialog() {
    _uiState.update { it.copy(isShareDialogVisible = false) }
  }

  fun sharePhoto(bitmap: Bitmap) {
    val context = getApplication<Application>()
    val imagesFolder = File(context.cacheDir, "images")
    try {
      imagesFolder.mkdirs()
      val file = File(imagesFolder, "shared_image.png")
      FileOutputStream(file).use { stream ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 90, stream)
      }

      val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
      val intent = Intent(Intent.ACTION_SEND)
      intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
      intent.putExtra(Intent.EXTRA_STREAM, uri)
      intent.type = "image/png"
      intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

      val chooser = Intent.createChooser(intent, "Share Image")
      chooser.flags = Intent.FLAG_ACTIVITY_NEW_TASK
      context.startActivity(chooser)
    } catch (e: IOException) {
      Log.e("StreamViewModel", "Failed to share photo", e)
    }
  }

  private fun handleVideoFrame(videoFrame: VideoFrame) {
    // VideoFrame contains raw I420 video data in a ByteBuffer
    // Use optimized YuvToBitmapConverter for direct I420 to ARGB conversion
    val bitmap =
        YuvToBitmapConverter.convert(
            videoFrame.buffer,
            videoFrame.width,
            videoFrame.height,
        )
    if (bitmap != null) {
      presentationQueue?.enqueue(
          bitmap,
          videoFrame.presentationTimeUs,
      )
    } else {
      Log.e(TAG, "Failed to convert YUV to bitmap")
    }
  }

  private fun handlePhotoData(photo: PhotoData) {
    val capturedPhoto = decodePhotoData(photo)
    _uiState.update { it.copy(capturedPhoto = capturedPhoto, isShareDialogVisible = true) }
  }

  private suspend fun tryAskOpenWebUiAboutImage(prompt: String, image: Bitmap): OpenWebUiResult {
    return openWebUiClient.askAboutImage(
        baseUrl = _uiState.value.openWebUiBaseUrl,
        apiKey = _uiState.value.openWebUiApiKey,
        model = _uiState.value.openWebUiModel,
        prompt = prompt,
        systemPrompt = _uiState.value.openWebUiSystemPrompt,
        image = image,
        imageOptions =
            OpenWebUiImageOptions(
                maxDimension = _uiState.value.snapshotImageQuality.maxDimension,
                jpegQuality = _uiState.value.snapshotImageQuality.jpegQuality,
            ),
        chatSession = currentOpenWebUiChatSession(),
        chatTitle = "Open WebUI Bridge",
    )
  }

  private suspend fun tryAskOpenWebUiAboutText(prompt: String): OpenWebUiResult {
    return openWebUiClient.askText(
        baseUrl = _uiState.value.openWebUiBaseUrl,
        apiKey = _uiState.value.openWebUiApiKey,
        model = _uiState.value.openWebUiModel,
        prompt = prompt,
        systemPrompt = _uiState.value.openWebUiSystemPrompt,
        chatSession = currentOpenWebUiChatSession(),
        chatTitle = "Open WebUI Bridge",
    )
  }

  private fun decodePhotoData(photo: PhotoData): Bitmap {
    return when (photo) {
      is PhotoData.Bitmap -> photo.bitmap
      is PhotoData.HEIC -> {
        val byteArray = ByteArray(photo.data.remaining())
        photo.data.get(byteArray)

        val exifInfo = getExifInfo(byteArray)
        val transform = getTransform(exifInfo)
        decodeHeic(byteArray, transform)
      }
    }
  }

  private fun createRecognitionListener(): RecognitionListener {
    return object : RecognitionListener {
      override fun onReadyForSpeech(params: Bundle?) = Unit

      override fun onBeginningOfSpeech() = Unit

      override fun onRmsChanged(rmsdB: Float) = Unit

      override fun onBufferReceived(buffer: ByteArray?) = Unit

      override fun onEndOfSpeech() {
        _uiState.update { it.copy(isListeningForVoice = false) }
      }

      override fun onError(error: Int) {
        speechRecognizer?.destroy()
        speechRecognizer = null
        _uiState.update {
          it.copy(
              isListeningForVoice = false,
              openWebUiError = "Voice recognition failed: ${recognitionErrorMessage(error)}",
          )
        }
      }

      override fun onResults(results: Bundle?) {
        speechRecognizer?.destroy()
        speechRecognizer = null
        val transcript = bestRecognitionResult(results)
        _uiState.update { it.copy(isListeningForVoice = false, voiceTranscript = transcript) }
        if (transcript.isBlank()) {
          _uiState.update { it.copy(openWebUiError = "No voice prompt heard") }
        } else if (_uiState.value.streamSessionState == StreamSessionState.STREAMING) {
          askOpenWebUiAboutSnapshot(transcript)
        } else {
          askOpenWebUiAboutText(transcript)
        }
      }

      override fun onPartialResults(partialResults: Bundle?) {
        val transcript = bestRecognitionResult(partialResults)
        if (transcript.isNotBlank()) {
          _uiState.update { it.copy(voiceTranscript = transcript) }
        }
      }

      override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }
  }

  private fun bestRecognitionResult(results: Bundle?): String {
    return results
        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        ?.firstOrNull()
        .orEmpty()
  }

  private fun recognitionErrorMessage(error: Int): String {
    return when (error) {
      SpeechRecognizer.ERROR_AUDIO -> "audio recording error"
      SpeechRecognizer.ERROR_CLIENT -> "client error"
      SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "microphone permission missing"
      SpeechRecognizer.ERROR_NETWORK -> "network error"
      SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network timeout"
      SpeechRecognizer.ERROR_NO_MATCH -> "no speech match"
      SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "recognizer busy"
      SpeechRecognizer.ERROR_SERVER -> "recognition server error"
      SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "no speech detected"
      else -> "error $error"
    }
  }

  private fun routeAudioToBluetooth() {
    val audioManager =
        getApplication<Application>().getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val bluetoothDevice =
        audioManager.availableCommunicationDevices.firstOrNull {
          it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }
    if (bluetoothDevice != null) {
      audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
      audioManager.setCommunicationDevice(bluetoothDevice)
    }
  }

  // HEIC Decoding with EXIF transformation
  private fun decodeHeic(heicBytes: ByteArray, transform: Matrix): Bitmap {
    val bitmap = BitmapFactory.decodeByteArray(heicBytes, 0, heicBytes.size)
    return applyTransform(bitmap, transform)
  }

  private fun getExifInfo(heicBytes: ByteArray): ExifInterface? {
    return try {
      ByteArrayInputStream(heicBytes).use { inputStream -> ExifInterface(inputStream) }
    } catch (e: IOException) {
      Log.w(TAG, "Failed to read EXIF from HEIC", e)
      null
    }
  }

  private fun getTransform(exifInfo: ExifInterface?): Matrix {
    val matrix = Matrix()

    if (exifInfo == null) {
      return matrix // Identity matrix (no transformation)
    }

    when (
        exifInfo.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    ) {
      ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> {
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_180 -> {
        matrix.postRotate(180f)
      }
      ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
        matrix.postScale(1f, -1f)
      }
      ExifInterface.ORIENTATION_TRANSPOSE -> {
        matrix.postRotate(90f)
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_90 -> {
        matrix.postRotate(90f)
      }
      ExifInterface.ORIENTATION_TRANSVERSE -> {
        matrix.postRotate(270f)
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_270 -> {
        matrix.postRotate(270f)
      }
      ExifInterface.ORIENTATION_NORMAL,
      ExifInterface.ORIENTATION_UNDEFINED -> {
        // No transformation needed
      }
    }

    return matrix
  }

  private fun applyTransform(bitmap: Bitmap, matrix: Matrix): Bitmap {
    if (matrix.isIdentity) {
      return bitmap
    }

    return try {
      val transformed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
      if (transformed != bitmap) {
        bitmap.recycle()
      }
      transformed
    } catch (e: OutOfMemoryError) {
      Log.e(TAG, "Failed to apply transformation due to memory", e)
      bitmap
    }
  }

  override fun onCleared() {
    super.onCleared()
    speechRecognizer?.destroy()
    speechRecognizer = null
    textToSpeech?.shutdown()
    textToSpeech = null
    stopStream()
    session?.stop()
    session = null
  }

  class Factory(
      private val application: Application,
      private val wearablesViewModel: WearablesViewModel,
  ) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      if (modelClass.isAssignableFrom(StreamViewModel::class.java)) {
        @Suppress("UNCHECKED_CAST", "KotlinGenericsCast")
        return StreamViewModel(
            application = application,
            wearablesViewModel = wearablesViewModel,
        )
            as T
      }
      throw IllegalArgumentException("Unknown ViewModel class")
    }
  }
}
