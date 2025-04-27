package org.futo.inputmethod.latin.uix.actions

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import org.futo.inputmethod.deepgram_voicedictaton.DeepgramSpeechToText
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.AUDIO_FOCUS
import org.futo.inputmethod.latin.uix.Action
import org.futo.inputmethod.latin.uix.ActionWindow
import org.futo.inputmethod.latin.uix.CAN_EXPAND_SPACE
import org.futo.inputmethod.latin.uix.DEEPGRAM_API_KEY
import org.futo.inputmethod.latin.uix.DISALLOW_SYMBOLS
import org.futo.inputmethod.latin.uix.ENABLE_SOUND
import org.futo.inputmethod.latin.uix.KeyboardManagerForAction
import org.futo.inputmethod.latin.uix.PREFER_BLUETOOTH
import org.futo.inputmethod.latin.uix.PersistentActionState
import org.futo.inputmethod.latin.uix.ResourceHelper
import org.futo.inputmethod.latin.uix.USE_VAD_AUTOSTOP
import org.futo.inputmethod.latin.uix.VERBOSE_PROGRESS
import org.futo.inputmethod.latin.uix.actions.DeepgramVoiceInputState.State.READY_FOR_CONNECT
import org.futo.inputmethod.latin.uix.getSetting
import org.futo.inputmethod.latin.uix.setSetting
import org.futo.inputmethod.latin.xlm.UserDictionaryObserver
import org.futo.inputmethod.updates.openURI
import org.futo.voiceinput.shared.ModelDoesNotExistException
import org.futo.voiceinput.shared.RecognizerView
import org.futo.voiceinput.shared.RecognizerViewListener
import org.futo.voiceinput.shared.RecognizerViewSettings
import org.futo.voiceinput.shared.RecordingSettings
import org.futo.voiceinput.shared.SoundPlayer
import org.futo.voiceinput.shared.types.Language
import org.futo.voiceinput.shared.types.ModelLoader
import org.futo.voiceinput.shared.types.getLanguageFromWhisperString
import org.futo.voiceinput.shared.ui.MicrophoneDeviceState
import org.futo.voiceinput.shared.whisper.DecodingConfiguration
import org.futo.voiceinput.shared.whisper.ModelManager
import org.futo.voiceinput.shared.whisper.MultiModelRunConfiguration
import java.util.Locale
import androidx.compose.runtime.remember as remember1

class VoiceInputDeepgramPersistentState(val manager: KeyboardManagerForAction) :
    PersistentActionState {
    var dictation = DeepgramSpeechToText(manager)

    override suspend fun cleanUp() {
    }
}

val DeepgramVoiceInputAction = Action(
    icon = R.drawable.mic_fill,
    name = R.string.deepgram_voice_input_action_title,
    simplePressImpl = { manager, _ ->
        manager.triggerVoiceInputDeepgram()
    },
    persistentState = { VoiceInputDeepgramPersistentState(it) },
    windowImpl = { manager, persistentState ->
        var uiState = DeepgramVoiceInputState()
        var state = persistentState as VoiceInputDeepgramPersistentState
        DeepgramActionWindow(manager, uiState, state)
    },
    shownInEditor = false
)

val SystemVoiceInputAction = Action(
    icon = R.drawable.mic_fill,
    name = R.string.system_voice_input_action_title,
    simplePressImpl = { it, _ ->
        it.triggerSystemVoiceInput()
    },
    persistentState = null,
    windowImpl = null,
    shownInEditor = false
)


@Composable
fun NoModelInstalled(locale: Locale) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                enabled = true,
                onClickLabel = null,
                onClick = {
                    context.openURI("https://keyboard.futo.org/voice-input-models", true)
                },
                role = null,
                indication = null,
                interactionSource = remember1 { MutableInteractionSource() })
    ) {
        Text(
            "No voice input model installed for ${locale.getDisplayName(locale)}, tap to check options?",
            modifier = Modifier
                .align(Alignment.Center)
                .padding(8.dp),
            textAlign = TextAlign.Center
        )
    }
}

class VoiceInputPersistentState(val manager: KeyboardManagerForAction) : PersistentActionState {
    val modelManager = ModelManager(manager.getContext())
    val soundPlayer = SoundPlayer(manager.getContext())
    val userDictionaryObserver = UserDictionaryObserver(manager.getContext())

    override suspend fun cleanUp() {
        modelManager.cleanUp()
    }
}

private class VoiceInputActionWindow(
    val manager: KeyboardManagerForAction, val state: VoiceInputPersistentState,
    val model: ModelLoader, val locales: List<Locale>
) : ActionWindow, RecognizerViewListener {
    val context = manager.getContext()

    private var shouldPlaySounds: Boolean = false
    private fun loadSettings(): RecognizerViewSettings {
        val enableSound = context.getSetting(ENABLE_SOUND)
        val verboseFeedback = context.getSetting(VERBOSE_PROGRESS)
        val disallowSymbols = context.getSetting(DISALLOW_SYMBOLS)
        val useBluetoothAudio = context.getSetting(PREFER_BLUETOOTH)
        val requestAudioFocus = context.getSetting(AUDIO_FOCUS)
        val canExpandSpace = context.getSetting(CAN_EXPAND_SPACE)
        val useVAD = context.getSetting(USE_VAD_AUTOSTOP)

        val primaryModel = model
        val languageSpecificModels = mutableMapOf<Language, ModelLoader>()
        val allowedLanguages = locales.map { getLanguageFromWhisperString(it.language) }
            .filterNotNull().toSet()

        shouldPlaySounds = enableSound

        return RecognizerViewSettings(
            shouldShowInlinePartialResult = false,
            shouldShowVerboseFeedback = verboseFeedback,
            modelRunConfiguration = MultiModelRunConfiguration(
                primaryModel = primaryModel,
                languageSpecificModels = languageSpecificModels
            ),
            decodingConfiguration = DecodingConfiguration(
                glossary = state.userDictionaryObserver.getWords().map { it.word },
                languages = allowedLanguages,
                suppressSymbols = disallowSymbols
            ),
            recordingConfiguration = RecordingSettings(
                preferBluetoothMic = useBluetoothAudio,
                requestAudioFocus = requestAudioFocus,
                canExpandSpace = canExpandSpace,
                useVADAutoStop = useVAD
            )
        )
    }

    private var recognizerView: MutableState<RecognizerView?> = mutableStateOf(null)
    private var modelException: MutableState<ModelDoesNotExistException?> = mutableStateOf(null)

    private val initJob = manager.getLifecycleScope().launch {
        yield()
        val settings = loadSettings()

        yield()
        val recognizerView = try {
            RecognizerView(
                context = manager.getContext(),
                listener = this@VoiceInputActionWindow,
                settings = settings,
                lifecycleScope = manager.getLifecycleScope(),
                modelManager = state.modelManager
            )
        } catch (e: ModelDoesNotExistException) {
            modelException.value = e
            return@launch
        }

        this@VoiceInputActionWindow.recognizerView.value = recognizerView

        yield()
        recognizerView.reset()

        yield()
        recognizerView.start()
    }

    private var inputTransaction = manager.createInputTransaction(true)

    @Composable
    private fun ModelDownloader(modelException: ModelDoesNotExistException) {
        NoModelInstalled(locales.firstOrNull() ?: Locale.ROOT)
    }

    @Composable
    override fun windowName(): String {
        return stringResource(R.string.voice_input_action_title)
    }

    @Composable
    override fun WindowContents(keyboardShown: Boolean) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    enabled = true,
                    onClickLabel = null,
                    onClick = { recognizerView.value?.finish() },
                    role = null,
                    indication = null,
                    interactionSource = remember1 { MutableInteractionSource() })
                .semantics(mergeDescendants = true) {
                    traversalIndex = -1.0f
                }) {
            Box(modifier = Modifier.align(Alignment.Center)) {
                when {
                    modelException.value != null -> ModelDownloader(modelException.value!!)
                    recognizerView.value != null -> recognizerView.value!!.Content()
                }
            }
        }
    }

    override fun close() {
        initJob.cancel()
        recognizerView.value?.cancel()
    }

    private var wasFinished = false
    private var cancelPlayed = false
    override fun cancelled() {
        if (!wasFinished) {
            if (shouldPlaySounds && !cancelPlayed) {
                state.soundPlayer.playCancelSound()
                cancelPlayed = true
            }
            inputTransaction.cancel()
        }
    }

    override fun recordingStarted(device: MicrophoneDeviceState) {
        if (shouldPlaySounds) {
            state.soundPlayer.playStartSound()
        }

        // Only set the setting if bluetooth is available, else it would reset the setting
        // every time it's used without a bluetooth device connected.
        if (device.bluetoothAvailable) {
            manager.getLifecycleScope().launch {
                context.setSetting(PREFER_BLUETOOTH, device.bluetoothActive)
            }
        }
    }

    override fun finished(result: String) {
        wasFinished = true

        inputTransaction.commit(result)
        manager.announce(result)
        manager.closeActionWindow()
    }

    override fun partialResult(result: String) {
        inputTransaction.updatePartial(result)
    }

    override fun requestPermission(onGranted: () -> Unit, onRejected: () -> Unit): Boolean {
        return false
    }
}

private class VoiceInputNoModelWindow(val locale: Locale) : ActionWindow {
    @Composable
    override fun windowName(): String {
        return stringResource(R.string.voice_input_action_title)
    }

    @Composable
    override fun WindowContents(keyboardShown: Boolean) {
        NoModelInstalled(locale)
    }

    override fun close() {

    }

}

class DeepgramVoiceInputState() {

    enum class State(val text: String) {
        WAIT_FOR_INTERNET("Wait for Internet..."),
        INVALID_API_KEY("Invalid API Key"),
        NO_API_KEY("No API Key"),
        READY_FOR_CONNECT("Internet works"),
        WEBSOCKET_CONNECTED("Connected"),
    }

    var errorMessage by mutableStateOf("")

    var lastVoiceCommand by mutableStateOf("")
    var isVoiceCommandVisible by mutableStateOf(false)

    var status by mutableStateOf(State.WAIT_FOR_INTERNET)

    var twoLetterCode by mutableStateOf("XX")
        private set

    var isSpoken by mutableStateOf(false)

    var energyLevel by mutableStateOf(0)

    fun changeTwoLetterCode(newCode: String) {
        twoLetterCode = newCode
    }
}


private class DeepgramActionWindow(
    val manager: KeyboardManagerForAction,
    val uiState: DeepgramVoiceInputState,
    val persistentState: VoiceInputDeepgramPersistentState
) :
    ActionWindow {
    @Composable
    override fun windowName(): String {
        return stringResource(R.string.deepgram_voice_input_action_title)
    }

    fun isInternetAvailable(context: Context): Boolean {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val activeNetwork = connectivityManager.getNetworkCapabilities(network) ?: return false

            return when {
                activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> true
                activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> true
                activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> true
                else -> false
            }
        } else {
            @Suppress("DEPRECATION")
            val networkInfo = connectivityManager.activeNetworkInfo ?: return false
            @Suppress("DEPRECATION")
            return networkInfo.isConnected
        }
    }

    @Composable
    override fun WindowContents(keyboardShown: Boolean) {


        // This block will run when the composable is removed from the composition
        DisposableEffect(Unit) {
            onDispose { persistentState.dictation.stopStreaming() }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            if (uiState.errorMessage.isNotEmpty()) {
                // Show error message if set
                ErrorScreen()
            } else if (uiState.status == DeepgramVoiceInputState.State.WAIT_FOR_INTERNET) {
                // Wait until we have internet
                WaitUntilInternetScreen()
            } else if (uiState.status == DeepgramVoiceInputState.State.NO_API_KEY) {
                ApiKeyInputScreen()
            } else if (uiState.status == DeepgramVoiceInputState.State.READY_FOR_CONNECT) {
                // start Websocket
                val apiKey = manager.getContext().getSetting(DEEPGRAM_API_KEY)
                if (apiKey.isEmpty()) {
                    uiState.status = DeepgramVoiceInputState.State.NO_API_KEY
                    return
                }
                persistentState.dictation.startWebsocket(apiKey, uiState)
            } else if (uiState.status == DeepgramVoiceInputState.State.WEBSOCKET_CONNECTED) {
                DictateScreen()
            } else {
                // something else
                Text(
                    text = uiState.status.text,
                    modifier = Modifier.padding(top = 16.dp) // Add some space between the circle and the text
                )
            }


        }
    }


    @Composable
    fun ErrorScreen() {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Text(uiState.errorMessage)
        }
    }

    @Composable
    fun DictateScreen() {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // First Row: Icon
            Icon(
                painter = painterResource(id = if (uiState.isSpoken) R.drawable.mic_fill else R.drawable.baseline_mic_none_24),
                contentDescription = "Microphone",
                modifier = Modifier.size(48.dp)
            )
            Text(
                text = uiState.twoLetterCode,
                fontSize = 16.sp,
                modifier = Modifier.clickable {
                    manager.activateAction(SwitchLanguageAction);
                    //Stop Streaming
                    persistentState.dictation.stopStreaming()
                    // Restart everything
                    uiState.status = READY_FOR_CONNECT
                }
            )
            Spacer(modifier = Modifier.height(16.dp)) // Space between rows

            // Second Row: Texts
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                LaunchedEffect(uiState.lastVoiceCommand) {
                    if (uiState.lastVoiceCommand.isNotEmpty()) {
                        uiState.isVoiceCommandVisible = true
                        delay(2000) // Wait for 2 seconds
                        uiState.isVoiceCommandVisible = false // Hide the command
                    }
                }
                if (uiState.isVoiceCommandVisible) {
                    Text(
                        text = "Last Command: ${uiState.lastVoiceCommand}",
                        color = Color.Green,
                        fontSize = 16.sp
                    )
                }


            }
        }

    }

    @Composable
    fun WaitUntilInternetScreen() {
        // Use a Box to center the Column
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center // Center the content
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center // Center items vertically within the Column
            ) {
                CircularProgressIndicator()
                Text(
                    text = uiState.status.text,
                    modifier = Modifier.padding(top = 16.dp) // Add some space between the circle and the text
                )
            }

            // LaunchedEffect to handle loading logic
            LaunchedEffect(Unit) {
                manager.getContext()
                var isLoading = true
                while (isLoading) {
                    if (isInternetAvailable(manager.getContext())) {
                        isLoading = false

                        uiState.status = READY_FOR_CONNECT
                    }
                    // Check every 500ms
                    delay(500)
                }
            }
        }
    }

    @Composable
    fun ApiKeyInputScreen() {

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // We cannot enter one directly as we have no keyboard
            Text("No API Key. Please enter one in the settings!")
        }
    }

    override fun close() {
        persistentState.dictation.stopStreaming()
    }

}

val VoiceInputAction = Action(
    icon = R.drawable.mic_fill,
    name = R.string.voice_input_action_title,
    simplePressImpl = null,
    keepScreenAwake = true,
    persistentState = { VoiceInputPersistentState(it) },
    windowImpl = { manager, persistentState ->
        val locales = manager.getActiveLocales()

        val model = ResourceHelper.tryFindingVoiceInputModelForLocale(
            manager.getContext(),
            locales.firstOrNull() ?: Locale.ROOT
        )

        if (model == null) {
            VoiceInputNoModelWindow(locales.firstOrNull() ?: Locale.ROOT)
        } else {
            VoiceInputActionWindow(
                manager = manager, state = persistentState as VoiceInputPersistentState,
                locales = locales, model = model
            )
        }
    }
)