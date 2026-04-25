/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.openwebuibridge.ui

import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meta.wearable.dat.camera.types.StreamSessionState
import com.meta.wearable.dat.externalsampleapps.openwebuibridge.BuildConfig
import com.meta.wearable.dat.externalsampleapps.openwebuibridge.R
import com.meta.wearable.dat.externalsampleapps.openwebuibridge.stream.SnapshotImageQuality
import com.meta.wearable.dat.externalsampleapps.openwebuibridge.stream.StreamUiState
import com.meta.wearable.dat.externalsampleapps.openwebuibridge.stream.StreamViewModel
import com.meta.wearable.dat.externalsampleapps.openwebuibridge.wearables.AppThemeMode
import com.meta.wearable.dat.externalsampleapps.openwebuibridge.wearables.WearablesViewModel

@Composable
fun StreamScreen(
    wearablesViewModel: WearablesViewModel,
    modifier: Modifier = Modifier,
    streamViewModel: StreamViewModel =
        viewModel(
            factory =
                StreamViewModel.Factory(
                    application = (LocalActivity.current as ComponentActivity).application,
                    wearablesViewModel = wearablesViewModel,
                ),
        ),
) {
  val streamUiState by streamViewModel.uiState.collectAsStateWithLifecycle()
  val wearablesUiState by wearablesViewModel.uiState.collectAsStateWithLifecycle()
  val isCameraStreaming = streamUiState.streamSessionState == StreamSessionState.STREAMING
  val isCameraStarting = streamUiState.streamSessionState == StreamSessionState.STARTING
  val isCameraEnabled = streamUiState.streamSessionState != StreamSessionState.STOPPED
  var modelMenuExpanded by remember { mutableStateOf(false) }
  var snapshotImageQualityMenuExpanded by remember { mutableStateOf(false) }
  var isSettingsExpanded by remember { mutableStateOf(false) }
  val context = LocalContext.current
  val clipboardManager = LocalClipboardManager.current

  LaunchedEffect(Unit) { streamViewModel.startStream() }

  Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
    Box(modifier = Modifier.fillMaxSize()) {
      Column(
          modifier = Modifier.fillMaxSize().systemBarsPadding(),
          verticalArrangement = Arrangement.spacedBy(0.dp),
      ) {
        ChatTopBar(
            isBridgeRunning = streamUiState.isBridgeRunning,
            isCameraStreaming = isCameraStreaming,
            isCameraStarting = isCameraStarting,
            model = streamUiState.openWebUiModel,
            onNewChat = { streamViewModel.startNewOpenWebUiChat() },
            onToggleSettings = { isSettingsExpanded = !isSettingsExpanded },
        )

        ChatTimeline(
            streamUiState = streamUiState,
            isCameraStreaming = isCameraStreaming,
            onCopy = { response -> clipboardManager.setText(AnnotatedString(response)) },
            onShare = { response -> shareText(context, response) },
            onSpeak = { streamViewModel.speakResponse() },
            onStopSpeaking = { streamViewModel.stopSpeakingResponse() },
            modifier = Modifier.weight(1f),
        )

        if (isSettingsExpanded) {
          SettingsPanel(
              modelMenuExpanded = modelMenuExpanded,
              onModelMenuExpandedChange = { modelMenuExpanded = it },
              snapshotImageQualityMenuExpanded = snapshotImageQualityMenuExpanded,
              onSnapshotImageQualityMenuExpandedChange = {
                snapshotImageQualityMenuExpanded = it
              },
              streamViewModel = streamViewModel,
              streamUiState = streamUiState,
              themeMode = wearablesUiState.appThemeMode,
              onThemeModeChange = wearablesViewModel::updateAppThemeMode,
              isDebugMenuVisible = wearablesUiState.isDebugMenuVisible,
              onDebugMenuVisibleChange = { visible ->
                if (visible) {
                  wearablesViewModel.showDebugMenu()
                } else {
                  wearablesViewModel.hideDebugMenu()
                }
              },
              modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
          )
        }

        ChatComposer(
            streamUiState = streamUiState,
            isCameraEnabled = isCameraEnabled,
            isCameraStreaming = isCameraStreaming,
            isBusy = streamUiState.isAskingOpenWebUi,
            isBridgeRunning = streamUiState.isBridgeRunning,
            isListeningForVoice = streamUiState.isListeningForVoice,
            onPromptChange = streamViewModel::updateOpenWebUiPrompt,
            onStopBridge = {
              streamViewModel.stopStream()
              wearablesViewModel.navigateToDeviceSelection()
            },
            onToggleCamera = { streamViewModel.toggleCameraStream() },
            onVoiceAsk = { streamViewModel.startVoiceAsk() },
            onSnapshotAsk = { streamViewModel.askOpenWebUiAboutSnapshot() },
        )
      }

      if (isCameraStarting) {
        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
      }
    }
  }

  streamUiState.capturedPhoto?.let { photo ->
    if (streamUiState.isShareDialogVisible) {
      SharePhotoDialog(
          photo = photo,
          onDismiss = { streamViewModel.hideShareDialog() },
          onShare = { bitmap ->
            streamViewModel.sharePhoto(bitmap)
            streamViewModel.hideShareDialog()
          },
      )
    }
  }
}

@Composable
private fun ChatTopBar(
    isBridgeRunning: Boolean,
    isCameraStreaming: Boolean,
    isCameraStarting: Boolean,
    model: String,
    onNewChat: () -> Unit,
    onToggleSettings: () -> Unit,
) {
  Surface(
      modifier = Modifier.fillMaxWidth(),
      color = MaterialTheme.colorScheme.surface,
      tonalElevation = 2.dp,
  ) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
      Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(text = stringResource(R.string.chat_app_title), style = MaterialTheme.typography.titleMedium)
          Text(
              text = model.ifBlank { stringResource(R.string.openwebui_model_not_selected) },
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
          )
        }
        IconButton(onClick = onNewChat) {
          Icon(Icons.Default.AddComment, contentDescription = stringResource(R.string.openwebui_new_chat))
        }
        IconButton(onClick = onToggleSettings) {
          Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.show_settings))
        }
      }
      Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        StatusPill(
            text =
                if (isBridgeRunning) {
                  stringResource(R.string.bridge_running)
                } else {
                  stringResource(R.string.bridge_starting)
                },
        )
        StatusPill(
            text =
                when {
                  isCameraStarting -> stringResource(R.string.bridge_camera_starting)
                  isCameraStreaming -> stringResource(R.string.camera_stream_running)
                  else -> stringResource(R.string.camera_stream_off)
                },
        )
      }
    }
  }
}

@Composable
private fun StatusPill(text: String) {
  Surface(
      shape = RoundedCornerShape(8.dp),
      color = MaterialTheme.colorScheme.surfaceVariant,
  ) {
    Text(
        text = text,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
private fun ChatTimeline(
    streamUiState: StreamUiState,
    isCameraStreaming: Boolean,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
    onSpeak: () -> Unit,
    onStopSpeaking: () -> Unit,
    modifier: Modifier = Modifier,
) {
  Column(
      modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    GlassesContextCard(streamUiState = streamUiState, isCameraStreaming = isCameraStreaming)

    val hasConversation =
        streamUiState.openWebUiResponse != null ||
            streamUiState.openWebUiError != null ||
            streamUiState.isAskingOpenWebUi ||
            streamUiState.voiceTranscript != null

    if (!hasConversation) {
      EmptyChatState()
    } else {
      val userText = streamUiState.voiceTranscript ?: streamUiState.openWebUiPrompt
      UserMessageBubble(text = userText)

      when {
        streamUiState.isAskingOpenWebUi -> AssistantMessageBubble(text = stringResource(R.string.openwebui_asking))
        streamUiState.openWebUiError != null -> ErrorMessageBubble(text = streamUiState.openWebUiError)
        streamUiState.openWebUiResponse != null -> {
          AssistantMessageBubble(text = streamUiState.openWebUiResponse)
          ResponseActions(
              isSpeakingResponse = streamUiState.isSpeakingResponse,
              onCopy = { onCopy(streamUiState.openWebUiResponse) },
              onShare = { onShare(streamUiState.openWebUiResponse) },
              onSpeak = onSpeak,
              onStopSpeaking = onStopSpeaking,
          )
        }
      }
    }
  }
}

@Composable
private fun GlassesContextCard(streamUiState: StreamUiState, isCameraStreaming: Boolean) {
  Surface(
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(8.dp),
      color = MaterialTheme.colorScheme.surfaceVariant,
  ) {
    Row(
        modifier = Modifier.padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      val frame = streamUiState.videoFrame
      if (frame != null) {
        key(streamUiState.videoFrameCount) {
          Image(
              bitmap = frame.asImageBitmap(),
              contentDescription = stringResource(R.string.live_stream),
              modifier = Modifier.size(width = 96.dp, height = 64.dp),
              contentScale = ContentScale.Crop,
          )
        }
      } else {
        Box(
            modifier = Modifier.size(width = 96.dp, height = 64.dp).background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
          Icon(Icons.Default.VideocamOff, contentDescription = null)
        }
      }
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = stringResource(R.string.glasses_context_title), style = MaterialTheme.typography.titleSmall)
        Text(
            text =
                if (isCameraStreaming) {
                  stringResource(R.string.glasses_context_live)
                } else {
                  stringResource(R.string.glasses_context_standby)
                },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
private fun EmptyChatState() {
  Column(
      modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Icon(
        imageVector = Icons.Default.CameraAlt,
        contentDescription = null,
        modifier = Modifier.size(40.dp),
        tint = MaterialTheme.colorScheme.primary,
    )
    Text(text = stringResource(R.string.chat_empty_title), style = MaterialTheme.typography.titleMedium)
    Text(
        text = stringResource(R.string.openwebui_response_empty),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
private fun UserMessageBubble(text: String) {
  Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
    Surface(
        modifier = Modifier.widthIn(max = 300.dp),
        shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp, bottomStart = 8.dp, bottomEnd = 2.dp),
        color = MaterialTheme.colorScheme.primary,
    ) {
      Text(
          text = text,
          modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onPrimary,
      )
    }
  }
}

@Composable
private fun AssistantMessageBubble(text: String) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(
        text = stringResource(R.string.openwebui_assistant_label),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SelectionContainer {
      Text(text = text, style = MaterialTheme.typography.bodyLarge)
    }
  }
}

@Composable
private fun ErrorMessageBubble(text: String) {
  Surface(
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(8.dp),
      color = MaterialTheme.colorScheme.errorContainer,
  ) {
    Text(
        text = text,
        modifier = Modifier.padding(12.dp),
        color = MaterialTheme.colorScheme.onErrorContainer,
        style = MaterialTheme.typography.bodyMedium,
    )
  }
}

@Composable
private fun ResponseActions(
    isSpeakingResponse: Boolean,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onSpeak: () -> Unit,
    onStopSpeaking: () -> Unit,
) {
  Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    TextButton(onClick = onCopy, modifier = Modifier.weight(1f)) {
      Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
      Text(stringResource(R.string.copy_response))
    }
    TextButton(onClick = onShare, modifier = Modifier.weight(1f)) {
      Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
      Text(stringResource(R.string.share_response))
    }
    TextButton(
        onClick = if (isSpeakingResponse) onStopSpeaking else onSpeak,
        modifier = Modifier.weight(1f),
    ) {
      Icon(Icons.Default.VolumeUp, contentDescription = null, modifier = Modifier.size(16.dp))
      Text(
          if (isSpeakingResponse) {
            stringResource(R.string.stop_speaking_response)
          } else {
            stringResource(R.string.speak_response)
          }
      )
    }
  }
}

@Composable
private fun SettingsPanel(
    modelMenuExpanded: Boolean,
    onModelMenuExpandedChange: (Boolean) -> Unit,
    snapshotImageQualityMenuExpanded: Boolean,
    onSnapshotImageQualityMenuExpandedChange: (Boolean) -> Unit,
    streamViewModel: StreamViewModel,
    streamUiState: StreamUiState,
    themeMode: AppThemeMode,
    onThemeModeChange: (AppThemeMode) -> Unit,
    isDebugMenuVisible: Boolean,
    onDebugMenuVisibleChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
  Column(
      modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    OutlinedTextField(
        value = streamUiState.openWebUiBaseUrl,
        onValueChange = streamViewModel::updateOpenWebUiBaseUrl,
        label = { Text(stringResource(R.string.openwebui_base_url)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = streamUiState.openWebUiApiKey,
        onValueChange = streamViewModel::updateOpenWebUiApiKey,
        label = { Text(stringResource(R.string.openwebui_api_key)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = streamUiState.openWebUiModel,
        onValueChange = streamViewModel::updateOpenWebUiModel,
        label = { Text(stringResource(R.string.openwebui_model)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      TextButton(
          onClick = { streamViewModel.refreshOpenWebUiModels() },
          enabled = !streamUiState.isLoadingOpenWebUiModels,
          modifier = Modifier.weight(1f),
      ) {
        Text(
            if (streamUiState.isLoadingOpenWebUiModels) {
              stringResource(R.string.openwebui_loading_models)
            } else {
              stringResource(R.string.openwebui_load_models)
            }
        )
      }
      Box(modifier = Modifier.weight(1f)) {
        TextButton(
            onClick = { onModelMenuExpandedChange(true) },
            enabled = streamUiState.openWebUiModels.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
          Text(stringResource(R.string.openwebui_select_model))
        }
        DropdownMenu(
            expanded = modelMenuExpanded,
            onDismissRequest = { onModelMenuExpandedChange(false) },
        ) {
          streamUiState.openWebUiModels.forEach { model ->
            DropdownMenuItem(
                text = { Text(model) },
                onClick = {
                  streamViewModel.updateOpenWebUiModel(model)
                  onModelMenuExpandedChange(false)
                },
            )
          }
        }
      }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = stringResource(R.string.app_theme_mode),
        style = MaterialTheme.typography.bodyLarge,
      )
      Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
      ThemeModeButton(
        label = stringResource(R.string.app_theme_system),
        selected = themeMode == AppThemeMode.SYSTEM,
        onClick = { onThemeModeChange(AppThemeMode.SYSTEM) },
      )
      ThemeModeButton(
        label = stringResource(R.string.app_theme_light),
        selected = themeMode == AppThemeMode.LIGHT,
        onClick = { onThemeModeChange(AppThemeMode.LIGHT) },
      )
      ThemeModeButton(
        label = stringResource(R.string.app_theme_dark),
        selected = themeMode == AppThemeMode.DARK,
        onClick = { onThemeModeChange(AppThemeMode.DARK) },
      )
      }
    }
    if (BuildConfig.DEBUG) {
      Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
            text = stringResource(R.string.mock_device_kit_title),
            style = MaterialTheme.typography.bodyLarge,
        )
        Switch(
            checked = isDebugMenuVisible,
            onCheckedChange = onDebugMenuVisibleChange,
        )
      }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
          text = stringResource(R.string.snapshot_image_quality),
          style = MaterialTheme.typography.bodyLarge,
      )
      Box {
        TextButton(onClick = { onSnapshotImageQualityMenuExpandedChange(true) }) {
          Text(snapshotImageQualityLabel(streamUiState.snapshotImageQuality))
        }
        DropdownMenu(
            expanded = snapshotImageQualityMenuExpanded,
            onDismissRequest = { onSnapshotImageQualityMenuExpandedChange(false) },
        ) {
          SnapshotImageQuality.values().forEach { quality ->
            DropdownMenuItem(
                text = { Text(snapshotImageQualityLabel(quality)) },
                onClick = {
                  streamViewModel.updateSnapshotImageQuality(quality)
                  onSnapshotImageQualityMenuExpandedChange(false)
                },
            )
          }
        }
      }
    }
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      TextButton(
          onClick = { streamViewModel.startNewOpenWebUiChat() },
          modifier = Modifier.weight(1f),
      ) {
        Text(stringResource(R.string.openwebui_new_chat))
      }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
          text = stringResource(R.string.auto_speak_responses),
          style = MaterialTheme.typography.bodyLarge,
      )
      Switch(
          checked = streamUiState.isAutoSpeakResponseEnabled,
          onCheckedChange = streamViewModel::updateAutoSpeakResponseEnabled,
      )
    }
    OutlinedTextField(
        value = streamUiState.openWebUiSystemPrompt,
        onValueChange = streamViewModel::updateOpenWebUiSystemPrompt,
        label = { Text(stringResource(R.string.openwebui_system_prompt)) },
        minLines = 2,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = streamUiState.openWebUiPrompt,
        onValueChange = streamViewModel::updateOpenWebUiPrompt,
        label = { Text(stringResource(R.string.openwebui_prompt)) },
        minLines = 2,
        modifier = Modifier.fillMaxWidth(),
    )
  }
}

@Composable
private fun snapshotImageQualityLabel(quality: SnapshotImageQuality): String =
    when (quality) {
      SnapshotImageQuality.STANDARD -> stringResource(R.string.snapshot_image_quality_standard)
      SnapshotImageQuality.HIGH -> stringResource(R.string.snapshot_image_quality_high)
      SnapshotImageQuality.ORIGINAL -> stringResource(R.string.snapshot_image_quality_original)
    }

@Composable
private fun ThemeModeButton(label: String, selected: Boolean, onClick: () -> Unit) {
  Surface(
      shape = RoundedCornerShape(8.dp),
      color =
          if (selected) {
            MaterialTheme.colorScheme.primaryContainer
          } else {
            MaterialTheme.colorScheme.surfaceVariant
          },
      onClick = onClick,
  ) {
    Text(
        text = label,
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        style = MaterialTheme.typography.labelMedium,
        color =
            if (selected) {
              MaterialTheme.colorScheme.onPrimaryContainer
            } else {
              MaterialTheme.colorScheme.onSurfaceVariant
            },
    )
  }
}

@Composable
private fun ChatComposer(
  streamUiState: StreamUiState,
    isCameraEnabled: Boolean,
    isCameraStreaming: Boolean,
    isBusy: Boolean,
    isBridgeRunning: Boolean,
    isListeningForVoice: Boolean,
  onPromptChange: (String) -> Unit,
    onStopBridge: () -> Unit,
    onToggleCamera: () -> Unit,
    onVoiceAsk: () -> Unit,
    onSnapshotAsk: () -> Unit,
) {
  Surface(modifier = Modifier.fillMaxWidth(), tonalElevation = 3.dp) {
  Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    OutlinedTextField(
        value = streamUiState.openWebUiPrompt,
        onValueChange = onPromptChange,
        label = { Text(stringResource(R.string.openwebui_prompt)) },
        minLines = 1,
        maxLines = 3,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      SwitchButton(
          label =
              if (isListeningForVoice) {
                stringResource(R.string.openwebui_listening)
              } else {
                stringResource(R.string.openwebui_voice_ask)
              },
          onClick = onVoiceAsk,
          icon = Icons.Default.Mic,
          enabled = isBridgeRunning && !isListeningForVoice && !isBusy,
          modifier = Modifier.weight(1f),
      )
      SwitchButton(
          label =
              if (isBusy) {
                stringResource(R.string.openwebui_asking)
              } else {
                stringResource(R.string.openwebui_snapshot_ask)
              },
          onClick = onSnapshotAsk,
              icon = Icons.Default.CameraAlt,
          enabled = isCameraStreaming && !isBusy,
          modifier = Modifier.weight(1f),
      )
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      SwitchButton(
          label =
              if (isCameraEnabled) {
                stringResource(R.string.stop_camera_stream_button_title)
              } else {
                stringResource(R.string.start_camera_stream_button_title)
              },
          onClick = onToggleCamera,
          icon = if (isCameraEnabled) Icons.Default.VideocamOff else Icons.Default.Videocam,
          enabled = isBridgeRunning && !isBusy,
          modifier = Modifier.weight(1f),
      )
      SwitchButton(
          label = stringResource(R.string.stop_bridge_button_title),
          onClick = onStopBridge,
          icon = Icons.Default.StopCircle,
          isDestructive = true,
          modifier = Modifier.weight(1f),
      )
    }
  }
  }
}

private fun shareText(context: Context, text: String) {
  val intent =
      Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
      }
  context.startActivity(Intent.createChooser(intent, null))
}