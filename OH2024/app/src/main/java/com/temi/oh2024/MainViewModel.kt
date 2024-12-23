package com.temi.oh2024

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.chat.chatCompletionRequest
import com.aallam.openai.api.chat.chatMessage
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.OpenAI
import com.robotemi.sdk.TtsRequest
import com.robotemi.sdk.map.MapDataModel
import com.robotemi.sdk.navigation.model.Position
import com.robotemi.sdk.navigation.model.SpeedLevel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.regex.Pattern
import javax.inject.Inject
import kotlin.math.*
import kotlin.random.Random

// Track state
enum class State {
    TALK,          // Testing talking feature
    DISTANCE,      // Track distance of user
    ANGLE,
    CONSTRAINT_FOLLOW,
    TEST_MOVEMENT,
    DETECTION_LOGIC,
    TOUR,
    TEST,
    NULL
}

// Track Y distance
enum class YDirection {
    FAR,
    MIDRANGE,
    CLOSE,
    MISSING
}

// Track X distance
enum class XDirection {
    LEFT,
    RIGHT,
    MIDDLE,
    GONE

}

// Y based movement
enum class YMovement {
    CLOSER,
    FURTHER,
    NOWHERE
}

// X based movement
enum class XMovement {
    LEFTER,
    RIGHTER,
    NOWHERE
}

enum class TourState {
    NULL,
    TESTING
}

@HiltViewModel
class MainViewModel @Inject constructor(
    private val mainModel: MainModel,
    private val robotController: RobotController,
    private val bluetoothManager: BluetoothManager,
    @ApplicationContext private val context: Context // This will allow accessing the system files
) : ViewModel() {

    private val buffer = 100L // Used to create delay need to unsure systems work
    private var stateMode = State.NULL // Keep track of system state
    private var defaultAngle =
        270.0 // 180 + round(Math.toDegrees(robotController.getPositionYaw().toDouble())) // Default angle Temi will go to.
    private var boundary = 90.0 // Distance Temi can turn +/- the default angle
    private var userRelativeDirection =
        XDirection.GONE // Used for checking direction user was lost

    // Below is the data used to keep track of movement
    private var previousUserAngle = 0.0
    private var currentUserAngle = 0.0
    private var xPosition = XDirection.GONE
    private var xMotion = XMovement.NOWHERE

    private var previousUserDistance = 0.0
    private var currentUserDistance = 0.0
    private var yPosition = YDirection.MISSING
    private var yMotion = YMovement.NOWHERE

    private val _detectionState = MutableStateFlow(yPosition)
    val detectionState: StateFlow<YDirection> = _detectionState

    // ************************************************************************* Current APP

    val renderedMap = robotController.renderedMap
    var mapScale = robotController.mapScale
    val pointerSize = robotController.pointerSize

    // Define the dynamic coordinate conversion
    val dynamicCoordinateConvert: (Float, Float) -> Pair<Float, Float> = { a, b ->
        convertCoordinates(a, b, robotController.realPointOne, robotController.realPointTwo, robotController.mapPointOne, robotController.mapPointTwo)
    }

    private fun convertCoordinates(
        realX: Float,
        realY: Float,
        realPoint1: Pair<Float?, Float?>,
        realPoint2: Pair<Float?, Float?>,
        mapPoint1: Pair<Float, Float>,
        mapPoint2: Pair<Float, Float>,
    ): Pair<Float, Float> {

        val secondPointReal = Pair(
            realPoint2.first?.minus(realPoint1.first!!) ?: 0f,
            realPoint2.second?.minus(realPoint1.second!!) ?: 0f
        )
        val secondPointMap = Pair(
            mapPoint2.first - mapPoint1.first,
            mapPoint2.second - mapPoint1.second
        )

        val scaleX =
            if (secondPointReal.first != 0f) secondPointMap.first / secondPointReal.first else 1f
        val scaleY =
            if (secondPointReal.second != 0f) secondPointMap.second / secondPointReal.second else 1f

        Log.i("Testing2", "$scaleX, $scaleY")

        val offsetX = mapPoint1.first
        val offsetY = mapPoint1.second

        val mappedX = offsetX  + (realX - (realPoint1.first!!)) * scaleX
        val mappedY = offsetY + (realY - (realPoint1.second!!)) * scaleY

        return Pair(mappedX, mappedY)
    }

    private val _isChatGptThinking = MutableStateFlow(false)
    val isChatGptThinking: StateFlow<Boolean> = _isChatGptThinking

    private val _showImageFlag = MutableStateFlow(false)
    val showImageFlag: StateFlow<Boolean> = _showImageFlag

    private val _isExitingScreen = MutableStateFlow(false)
    val isExitingScreen: StateFlow<Boolean> = _isExitingScreen

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking

    private val _isGoing = MutableStateFlow(false)
    val isGoing: StateFlow<Boolean> = _isGoing

    private val _isTalking = MutableStateFlow(false)
    val isTalking: StateFlow<Boolean> = _isTalking

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening

    private val _positionFlow = MutableStateFlow(Position(0.0f, 0.0f, 0.0f, 0))
    val positionFlow: StateFlow<Position> = _positionFlow.asStateFlow()

    private val _isGreetMode = MutableStateFlow(true)
    val isGreetMode: StateFlow<Boolean> = _isGreetMode

    private val _isValidDetection = MutableStateFlow(false)
    val isValidDetection: StateFlow<Boolean> = _isValidDetection

    private val _isIdleFaceHome = MutableStateFlow(false)
    val isIdleFaceHome: StateFlow<Boolean> = _isIdleFaceHome

    // Move these outside the function to maintain state across calls
    private val numberArray = (1..5).toMutableList()
    private var currentIndex = 0
    private var previousLastChoice = -1

    // Function to get the next random number in the shuffled array
    private fun getRandomChoice(): Int {
        if (currentIndex >= numberArray.size) {
            numberArray.shuffle() // Reshuffle when the array is exhausted
            currentIndex = 0

            // Ensure the first choice isn't the same as the last choice from the previous array
            if (numberArray[0] == previousLastChoice) {
                val swapIndex = (1 until numberArray.size).random() // Get a random index (1..4)
                numberArray[0] = numberArray[swapIndex].also {
                    numberArray[swapIndex] = numberArray[0]
                }
            }
        }

        // Safeguard: Ensure `currentIndex` is within bounds before accessing
        if (currentIndex in numberArray.indices) {
            val choice = numberArray[currentIndex] // Get the current choice
            currentIndex++ // Increment index after accessing
            previousLastChoice = choice // Update the last choice to the current choice
            return choice - 1
        } else {
            throw IllegalStateException("Index out of bounds: currentIndex=$currentIndex, arraySize=${numberArray.size}")
        }
    }

    private val greetingsArray = arrayOf(
        "Hello! How can I assist you today?",
        "Hi there! What can I do for you?",
        "Good day! How may I help you?",
        "Hey! Need any assistance?",
        "Welcome! What can I help you with?"
    )

    private val detectionDelay = 1 // it is in seconds

    // Simulating position updates
    init {
        viewModelScope.launch {
            var currentLocation = Random.nextInt(1, 4)
            fun generateNewLocation(currentLocation: Int): Int {
                var newLocation = Random.nextInt(1, 4)
                if (newLocation >= currentLocation) newLocation++

                return newLocation
            }

            launch {
                while (true) {
                    if (yPosition != YDirection.MISSING && isGreetMode.value) {
                        // there are three spots for this two of the in here and the other in the main activity to control the ideal face from disappearing
                        conditionTimer({ yPosition == YDirection.MISSING }, detectionDelay)
                        if (!(yPosition != YDirection.MISSING && isGreetMode.value)) continue
                        stopMovement()
                        val choice = getRandomChoice() // Get a new randomized choice
                        robotController.constrainBeWith()
                        _isValidDetection.value = true
                        speakBasic(greetingsArray[choice]) // Speak the selected greeting
                        _isIdleFaceHome.value = true
                        var state: Boolean? = true
                        var timeoutCounter =
                            0 // used the buffer to keep track of time. 100 is 10 sec as buffer delays 0.1sec
                        while (true) {
                            if (timeoutCounter > 100) { //100 is 10 sec as buffer delays 0.1sec
                                _isValidDetection.value = false
                                _isIdleFaceHome.value = false
                                delay(5000) // delay here prevents system from getting triggered right after exit.
                                break
                            }

                            if (!(yPosition != YDirection.MISSING && isGreetMode.value)) { // break out if user is missing

                                val search = launch {

                                }

                                conditionTimer(
                                    { yPosition != YDirection.MISSING },
                                    5
                                ) // used to cause a delay before break of 5sec

                                if (!(yPosition != YDirection.MISSING && isGreetMode.value)) { // re-check ofter delay if user is still missing
                                    _isValidDetection.value = false
                                    _isIdleFaceHome.value = false
                                    delay(5000) // delay here prevents system from getting triggered right after exit.
                                    break
                                }
                            } else if (yPosition == YDirection.CLOSE && state == true) {
                                stopMovement()
                                robotController.tileAngle(60)
                                state = false
                            } else if (yPosition != YDirection.CLOSE && state == false) {
                                robotController.constrainBeWith()
                                state = true
                            }
                            buffer()
                            timeoutCounter++
                        }
                    }
                    _isValidDetection.value = false
                    _isIdleFaceHome.value = false

                    if (!isGreetMode.value) {
                        stopMovement()
                        robotController.tileAngle(60)
                        while (true) {

                            if (isGreetMode.value) break
                            buffer()
                        }
                    }


                    buffer()
                }
            }

            while (true) {
                _positionFlow.value = getPosition() // Replace with real logic to fetch position
                _detectionState.value = yPosition // Replace with real logic to fetch position
                buffer() // Update every second

                if (isGreetMode.value) {
                    while (true) {
                        if (!_isValidDetection.value) {

                            when (currentLocation) {
                                1 -> {
                                    robotController.goToPosition(
                                        Position(
                                            x = 1.009653F,
                                            y = 0.078262F,
                                            yaw = -1.504654F,
                                            tiltAngle = 20
                                        )
                                    )
                                }

                                2 -> {
                                    robotController.goToPosition(
                                        Position(
                                            x = 0.830383F,
                                            y = -7.916466F,
                                            yaw = 1.604749F,
                                            tiltAngle = 20
                                        )
                                    )
                                }

                                3 -> {
                                    robotController.goToPosition(
                                        Position(
                                            x = 1.769055F,
                                            y = -5.273465F,
                                            yaw = 3.116918F,
                                            tiltAngle = 20
                                        )
                                    )
                                }

                                4 -> {
                                    robotController.goToPosition(
                                        Position(
                                            x = 1.916642F,
                                            y = -2.222844F,
                                            yaw = 0.041969F,
                                            tiltAngle = 20
                                        )
                                    )
                                }
                            }

                            conditionGate({ goToLocationState != LocationState.COMPLETE && goToLocationState != LocationState.ABORT && !_isValidDetection.value })
                            Log.i(
                                "TESTING4",
                                "goToLocationState: $goToLocationState, _isValidDetection.value: ${_isValidDetection.value}"
                            )

                            if (goToLocationState != LocationState.ABORT && !_isValidDetection.value) {
                                conditionTimer(
                                    { (yPosition != YDirection.MISSING && isGreetMode.value) },
                                    5
                                )
                            }

                            if (goToLocationState == LocationState.COMPLETE) {
                                currentLocation = generateNewLocation(currentLocation)
                            }

                        } else {
                            break
                        }
                        buffer()
                    }
                }
            }
        }
    }

    fun getPosition(): Position {
        Log.i("Testing", "${robotController.getPosition()}")
        return robotController.getPosition()
    }

    fun getMapData(): MapDataModel? {
        return robotController.getMapData()
    }

    fun setShowImageFlag(showImageFlag: Boolean) {
        _showImageFlag.value = showImageFlag
    }

    fun setIsExitingScreen(isExitingScreen: Boolean) {
        _isExitingScreen.value = isExitingScreen
    }

    fun setSpeed(speed: SpeedLevel) {
        robotController.setGoToSpeed(speed)
    }

    fun playSoundEffect(soundEffectId: Int) {
        val mp3Player = AudioPlayer(context, soundEffectId)
        mp3Player.setVolumeLevel(0.25f)
        mp3Player.playOnce()
    }

    fun setGreetMode(showImageFlag: Boolean) {
        _isGreetMode.value = showImageFlag
    }

    fun playMusic(soundEffectId: Int) {
        val mp3Player = AudioPlayer(context, soundEffectId)
        mp3Player.play()
    }

    fun stopMusic(soundEffectId: Int) {
        val mp3Player = AudioPlayer(context, soundEffectId)
        mp3Player.release()
    }

    fun speakForUi(speak: String, conditionGateOff: Boolean) {
        viewModelScope.launch {
            if (conditionGateOff) {
                _isSpeaking.value = true
            }
        }

        viewModelScope.launch {
            speakBasic(speak, haveFace = false)
        }
    }

    fun askQuestionUi(info: String) {
        viewModelScope.launch {
            askQuestionBasic(info)
        }
    }

    private suspend fun speakBasic(
        speak: String?,
        haveFace: Boolean = true
    ) {
        if (speak != null) {
            _isTalking.value = true
            robotController.speak(
                speak,
                buffer,
                haveFace
            )
            conditionGate({ ttsStatus.value.status != TtsRequest.Status.COMPLETED })
            _isTalking.value = false
        }

    }

    private suspend fun askQuestionBasic(info: String) {
        shouldExit = false
        var noQuestion = true
        var response: String? = null

        _isSpeaking.value = false
        while (!isExitingScreen.value) {
            speakBasic("What is your Question? Say no to cancel.", haveFace = false)

            if (isSpeaking.value) {
                break
            }

            Log.i("GPT!", "Before listen: ${isExitingScreen.value}")
            if (isSpeaking.value || isExitingScreen.value) {
                break
            } else {
                listen()
            }
            Log.i("GPT!", "Before validation: $userResponse")

            if (userResponse != null && userResponse != " " && userResponse != "") {
                if (isSpeaking.value) {
                    break
                }
                response = userResponse
                buffer()
                if (containsPhraseInOrder(response, reject, true)) {
                    if (isExitingScreen.value) {
                        break
                    }
                    speakBasic("All good", haveFace = false)
                    noQuestion = true
                    break
                }
                if (isSpeaking.value) {
                    break
                } else {
                    speak(
                        context.getString(
                            R.string.did_you_say_please_just_say_yes_or_no,
                            userResponse
                        ), haveFace = false
                    )
                }

                if (isSpeaking.value) {
                    break
                }
                while (!isExitingScreen.value) {
                    listen()
                    if (isSpeaking.value) {
                        break
                    }
                    if (userResponse != null && userResponse != " " && userResponse != "") {
                        when { // Condition gate based on what the user says
                            containsPhraseInOrder(userResponse, reject, true) -> {
                                speakBasic(
                                    context.getString(R.string.sorry_lets_try_this_again),
                                    haveFace = false
                                )
                                break
                            }

                            containsPhraseInOrder(userResponse, confirmation, true) -> {
                                speakBasic(
                                    context.getString(R.string.great_let_me_think_for_a_moment),
                                    haveFace = false
                                )
                                shouldExit = true
                                break
                            }

                            else -> {
                                speakBasic(
                                    context.getString(R.string.sorry_i_did_not_understand_you),
                                    haveFace = false
                                )
                            }
                        }
                    }
                    buffer()
                }
                if (shouldExit) break
            } else {
                Log.i("GPT!", "In else state: $userResponse|")
                if (userResponse != "") {
                    speakBasic(
                        context.getString(R.string.all_good_i_will_continue_on),
                        haveFace = false
                    )
                     noQuestion = true
                    break
                } else {
                    speakBasic(
                        context.getString(R.string.sorry_i_had_an_issue_with_hearing_you),
                        haveFace = false
                    )
                }
            }
            buffer()
        }
        _isSpeaking.value = false

        Log.i("GPT!", "Passed Question asking noQuestion: $noQuestion")
        Log.i("GPT!", "Passed Question asking isSpeaking.value: ${isSpeaking.value}")

        if (!noQuestion && !isSpeaking.value) {
            val mp3Player = AudioPlayer(context, R.raw.music_wait)
            _isChatGptThinking.value = true
            mp3Player.play()

            Log.i("GPT!", response.toString())
            response?.let { sendMessage(openAI, it, info) }

            conditionGate({ responseGPT == null })
            Log.i("GPT!", responseGPT.toString())

            val randomDelayMillis = Random.nextLong(7001, 15001)
            delay(randomDelayMillis)

            _isChatGptThinking.value = false
            mp3Player.release()
            basicSpeak(responseGPT.toString(), haveFace = false)
            responseGPT = null
        }
        _isSpeaking.value = false
    }

    fun goToPosition(point: Int) {
// Define the spot variable
        var spot: Position? = null
        _isGoing.value = true

// Use when to assign values
        when (point) {
            1 -> {
                spot = Position(x = 1.009653F, y = 0.078262F, yaw = -1.504654F, tiltAngle = 20)
            }

            2 -> {
                spot = Position(x = 0.830383F, y = -7.916466F, yaw = 1.604749F, tiltAngle = 20)
            }

            3 -> {
                spot = Position(x = 1.769055F, y = -5.273465F, yaw = 3.116918F, tiltAngle = 20)
            }

            4 -> {
                spot = Position(x = 1.916642F, y = -2.222844F, yaw = 0.041969F, tiltAngle = 20)
            }

            else -> {
                // Null case if point doesn't match any case
                spot = null
            }
        }
        if (spot != null) {
            var hasGoneToLocation = false
            viewModelScope.launch {
                _isGoing.value = true
                while (true) { // loop until to makes it to the start location
                    if (!triggeredInterrupt && !hasGoneToLocation) {
                        robotController.goToPosition(spot)
                    }

                    buffer()
                    conditionGate({ goToLocationState != LocationState.COMPLETE && goToLocationState != LocationState.ABORT })

                    if (hasGoneToLocation && !repeatGoToFlag) {
                        _isGoing.value = false
                        break
                    } else if (goToLocationState == LocationState.COMPLETE) hasGoneToLocation = true

                    if (repeatGoToFlag) repeatGoToFlag = false
                    buffer()

                }
            }
        }

        _isGoing.value = false
    }

    fun queryLocation(location: String) {
        viewModelScope.launch {
            _isSpeaking.value = true
            var response: String? = null
            while (!isExitingScreen.value) {
                speakBasic(
                    "You have selected $location. Would you like me to bring you there? Please just say yes or no.",
                    haveFace = false
                )
                listen()
                response = userResponse

                buffer()
                if (containsPhraseInOrder(response, reject, true)) {
                    if (isExitingScreen.value) {
                        break
                    }
                    speakBasic(
                        "All good, just touch the screen when you are done and are looking to exit.",
                        haveFace = false
                    )
                    break
                } else if (containsPhraseInOrder(response, confirmation, true)) {
                    speakBasic(
                        "Ok, I will show you too it now.",
                        haveFace = false
                    )
                    goTo(location.toString(), backwards = true)
                    robotController.tileAngle(60)
                    speakBasic(
                        "We have made it to the location, if you need further help feel free to browse through my options.",
                        haveFace = false
                    )
                    _showImageFlag.value = false
                    break
                } else {
                    Log.i("TESTING", "|$userResponse|")
                    if (response == null) {
                        speakBasic(
                            "All good, just touch the screen when you are done and are looking to exit.",
                            haveFace = false
                        )
                        break
                    } else {
                        speakBasic(
                            "Sorry, I did not understand you.",
                            haveFace = false
                        )
                    }


                }
                buffer()
            }
            _isSpeaking.value = false
        }
    }

    // ************************************************************************* Current APP

    // These collect data from services in robotController
    private val ttsStatus = robotController.ttsStatus // Current speech state
    private val detectionStatus = robotController.detectionStateChangedStatus
    private val detectionData = robotController.detectionDataChangedStatus
    private val movementStatus = robotController.movementStatusChangedStatus
    private val lifted = robotController.lifted
    private val dragged = robotController.dragged
    private val askResult = robotController.askResult
    private val language = robotController.language
    private val wakeUp = robotController.wakeUp
    private val waveForm = robotController.waveform
    private val conversationStatus = robotController.conversationStatus
    private val conversationAttached = robotController.conversationAttached
    private val locationState = robotController.locationState
    private val beWithMeStatus = robotController.beWithMeState

//    // BLUETOOTH
//    private val bluetoothState = bluetoothManager.bluetoothState
    // BLUETOOTH

    // StateFlow for controlling whether to play a GIF or static image
    private val _shouldPlayGif = MutableStateFlow(true)
    val shouldPlayGif: StateFlow<Boolean> = _shouldPlayGif

    // StateFlow for holding the current image resource
    private val _imageResource = MutableStateFlow(R.drawable.oip)
    val image: StateFlow<Int> = _imageResource

    // StateFlow for holding the current image resource
    private val _gifResource = MutableStateFlow(R.drawable.idle)
    val gif: StateFlow<Int> = _gifResource

    // Function to update the image resource
    fun updateImageResource(resourceId: Int) {
        _imageResource.value = resourceId
    }

    fun updateGifResource(resourceId: Int) {
        _gifResource.value = resourceId
    }

    // Function to toggle the GIF state
    fun toggleGif() {
        _shouldPlayGif.value = !_shouldPlayGif.value
    }

    //******************************************** Stuff for the tour
    // key word lists
    private val confirmation = listOf(
        "Yes", "Okay", "Sure", "I'm willing", "Count me in", "Absolutely no problem",
        "Of course", "Right now", "Let's go", "I'll be there",
        "Sounds good", "I can join", "I'm ready", "It's settled",
        "Definitely", "On my way", "I'll come"
    )

    private val reject = listOf(
        "No", "Not now", "Can't", "Not attending", "Can't make it",
        "Impossible", "Sorry", "I have plans", "Not going",
        "Unfortunately can't", "I can't do it", "Regretfully no",
        "No way", "No thanks", "I'm busy", "I need to decline"
    )

    // Function to check for keywords or phrases
    private fun containsPhraseInOrder(
        userResponse: String?,
        phrases: List<String>,
        ignoreCase: Boolean = true
    ): Boolean {
        if (userResponse.isNullOrEmpty()) {
            return false // Return false if the response is null or empty
        }

        // Check for each phrase in the user response
        return phrases.any { phrase ->
            val words = phrase.split(" ")
            var userWords = userResponse.split(" ")

            var lastIndex = -1
            for (word in words) {
                // Find the word in user response
                lastIndex = userWords.indexOfFirst {
                    if (ignoreCase) {
                        it.equals(word, ignoreCase = true)
                    } else {
                        it == word
                    }
                }
                if (lastIndex == -1) return@any false // Word not found
                // Ensure the next word is after the last found word
                userWords = userWords.drop(lastIndex + 1)
            }
            true // All words were found in order
        }
    }

    // ************************************************************************************************ STUFF FOR THE TOUR
    // Keep track of the systems current state
    private var tourState = TourState.NULL
    private var isTourStateFinished = false

    private var speechUpdatedValue: String? = null
    private var userResponse: String? = null

    private var isAttached = false
    private var goToLocationState = LocationState.ABORT
    private var movementState = MovementStatus.ABORT

    private var shouldExit = false // Flag to determine if both loops should exit

    private var userName: String? = null

    private var followState = BeWithMeState.CALCULATING

    private var triggeredInterrupt: Boolean = false

    // Define a mutable map to hold interrupt flags
    private val interruptFlags = mutableMapOf(
        "userMissing" to false,
        "userTooClose" to false,
        "deviceMoved" to false
    )
    private var repeatSpeechFlag: Boolean = false
    private var talkingInThreadFlag = false
    private var repeatGoToFlag: Boolean = false
    private var interruptTriggerDelay = 10

    private var resetTourEarly = false
    private var preventResetFromIdle = false

    private suspend fun idleSystem(idle: Boolean) {
        while (idle) {
            buffer()
        } // set this and run to turn of the program
    }

    private suspend fun initiateTour() {
        // This is the initialisation for the tour, should only be run through once
        robotController.listOfLocations()

        goToSpeed(SpeedLevel.HIGH)
        userName = null

        val job = viewModelScope.launch {
            conversationAttached.collect { status ->
                isAttached = status.isAttached
            }
        }

        val job1 = viewModelScope.launch {
            locationState.collect { value ->
                goToLocationState = value
                //Log.i("START!", "$goToLocationState")
            }
        }

        val job2 = viewModelScope.launch {
            movementStatus.collect { value ->
                movementState = value.status
                // Log.i("START!", "$movementState")
            }
        }

        val job3 = viewModelScope.launch {
            beWithMeStatus.collect { value ->
                followState = value
                // Log.i("START!", "$movementState")
            }
        }

//        job.cancel()
//        job1.cancel()
//        job2.cancel()
        // job3.cancel()
    }

    private suspend fun tourState(newTourState: TourState) {
        tourState = newTourState
        Log.i("INFO!", "$tourState")
        conditionGate({ !isTourStateFinished })
        isTourStateFinished = false
    }

    private suspend fun setCliffSensorOn(sensorOn: Boolean) {
        robotController.setCliffSensorOn(sensorOn)
    }

    private fun stateFinished() {
        isTourStateFinished = true
        stateMode = State.NULL
    }

    private fun stateMode(state: State) {
        stateMode = state
    }

    private suspend fun basicSpeak(
        speak: String?,
        setConditionGate: Boolean = true,
        haveFace: Boolean = true
    ) {
        _isSpeaking.value = true
        if (speak != null) {
            robotController.speak(
                speak,
                buffer,
                haveFace
            )
            if (setConditionGate) conditionGate({ ttsStatus.value.status != TtsRequest.Status.COMPLETED })
        }
        _isSpeaking.value = false
    }

    private suspend fun forcedSpeak(speak: String?) {
        while (ttsStatus.value.status != TtsRequest.Status.STARTED) {
            if (speak != null) {
                robotController.speak(
                    speak,
                    buffer
                )
            }
            buffer()
        }
        conditionGate({ ttsStatus.value.status != TtsRequest.Status.COMPLETED })
    }

    private suspend fun turnBy(degree: Int) {
        robotController.turnBy(degree, buffer = buffer)
        conditionGate({ movementState != MovementStatus.COMPLETE && movementState != MovementStatus.ABORT })
    }

    private fun setMainButtonMode(isEnabled: Boolean) {
        robotController.setMainButtonMode(isEnabled)
    }

    private suspend fun getUseConfirmation(
        initialQuestion: String? = null,
        rejected: String? = null,
        afterRejectedDelay: Long = 0L,
        confirmed: String? = null,
        notUnderstood: String? = null,
        ignored: String? = null,
        exitCase: (suspend () -> Unit)? = null
    ) {
        shouldExit = false

        while (true) {
            if (xPosition != XDirection.GONE) { // Check if there is a user present
                // Check if there is an initial question
                speak(initialQuestion)

                while (true) {
                    listen()

                    when { // Condition gate based on what the user says
                        containsPhraseInOrder(userResponse, reject, true) -> {
                            speak(rejected)
                            delay(afterRejectedDelay)
                            break
                        }

                        containsPhraseInOrder(userResponse, confirmation, true) -> {
                            speak(confirmed)
                            shouldExit = true
                            break
                        }

                        else -> {
                            if (yPosition != YDirection.MISSING) {

                                speak(notUnderstood)

                            } else {

                                forcedSpeak(ignored)

                                break
                            }
                        }
                    }
                    buffer()
                }

                if (shouldExit) {
                    exitCase?.invoke() // Calls exitCase if it’s not null
                    break
                }

            }
            buffer()
        }
    }

    private suspend fun exitCaseCheckIfUserClose(
        notClose: String? = null,
        close: String? = null
    ) {
        while (true) {
            if (yPosition != YDirection.CLOSE) {
                if (!notClose.isNullOrEmpty()) {
                    speak(notClose)
                }
                break
            } else {
                if (!close.isNullOrEmpty()) {
                    speak(close)
                }
                conditionTimer(
                    { yPosition != YDirection.CLOSE },
                    time = 50
                )
                if (yPosition != YDirection.CLOSE) {
                    robotController.speak(context.getString(R.string.thank_you), buffer)
                    conditionGate({ ttsStatus.value.status != TtsRequest.Status.COMPLETED })
                }
            }
        }
    }

    private fun extractName(userResponse: String): String? {
        // Define common patterns for introducing names
        val namePatterns = listOf(
            "my name is ([A-Za-z]+)",  // e.g., "My name is John"
            "i am ([A-Za-z]+)",        // e.g., "I am Alice"
            "it's ([A-Za-z]+)",        // e.g., "It's Bob"
            "this is ([A-Za-z]+)",     // e.g., "This is Sarah"
            "call me ([A-Za-z]+)",     // e.g., "Call me Mike"
            "name is ([A-Za-z]+)",
            "is ([A-Za-z]+)",
            "me ([A-Za-z]+)",
            "i ([A-Za-z]+)",
            "am ([A-Za-z]+)"
        )

//        val namePatterns = listOf(
//            "我叫([\\u4e00-\\u9fa5]+)",  // e.g., "我叫小明"
//            "我的名字是([\\u4e00-\\u9fa5]+)",  // e.g., "我的名字是李华"
//            "我是([\\u4e00-\\u9fa5]+)",  // e.g., "我是张伟"
//            "这是([\\u4e00-\\u9fa5]+)",  // e.g., "这是王芳"
//            "叫我([\\u4e00-\\u9fa5]+)",  // e.g., "叫我小李"
//            "名字是([\\u4e00-\\u9fa5]+)",  // e.g., "名字是陈琳"
//            "是([\\u4e00-\\u9fa5]+)",  // e.g., "是刘强"
//            "我([\\u4e00-\\u9fa5]+)",  // e.g., "我李杰"
//            "叫([\\u4e00-\\u9fa5]+)",  // e.g., "叫韩梅"
//            "名([\\u4e00-\\u9fa5]+)"  // e.g., "名赵云"
//        )

        // Iterate over each pattern to try to match the user's response
        for (pattern in namePatterns) {
            val regex = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE)
            val matcher = regex.matcher(userResponse)

            // If a pattern matches, return the extracted name
            if (matcher.find()) {
                return matcher.group(1) // The name will be in the first capturing group
            }
        }

        // If no pattern matches, check if the userResponse is a single word and return it
//        val singleWordPattern = Pattern.compile("^[A-Za-z]+$", Pattern.CASE_INSENSITIVE)
        val singleWordPattern = Pattern.compile("^[\\u4e00-\\u9fa5]+$", Pattern.CASE_INSENSITIVE)
        val singleWordMatcher = singleWordPattern.matcher(userResponse.trim())

        return if (singleWordMatcher.matches()) userResponse.trim() else null
    }

    private fun goToSpeed(speedLevel: SpeedLevel) {
        robotController.setGoToSpeed(speedLevel)
    }

    suspend fun skidJoy(x: Float, y: Float, repeat: Int) {
        for (i in 1..repeat) {
            robotController.skidJoy(x, y)
            delay(500)
        }
    }

    // Function to update an interrupt flag value
    fun updateInterruptFlag(flag: String, value: Boolean) {
        if (interruptFlags.containsKey(flag)) {
            interruptFlags[flag] = value
        } else {
            println("Flag $flag does not exist in the interruptFlags map.")
        }
    }

    private fun stopMovement() {
        robotController.stopMovement()
    }

    private fun tiltAngle(degree: Int) {
        robotController.tileAngle(degree)
    }

    private var speakThread: Job? = null

    private fun createSpeakThread(
        start: Boolean, sentences: List<String> = listOf("Apple", "Banana", "Cherry"),
        haveFace: Boolean = true,
        setInterruptSystem: Boolean = false,
        setInterruptConditionUserMissing: Boolean = false,
        setInterruptConditionUSerToClose: Boolean = false,
        setInterruptConditionDeviceMoved: Boolean = false
    ) {
        if (start) {
            // Log.i("BluetoothServer", "Hello")
            speakThread = viewModelScope.launch {
                // Log.i("DEBUG!", "In the thread!s")
                talkingInThreadFlag = true
                for (sentence in sentences) {
                    // Log.i("DEBUG!", "$sentence")
                    if (sentence.isNotBlank()) {
                        do {
                            // Log.i("DEBUG!", sentence)
                            // set the repeat flag to false once used
                            if (setInterruptSystem && repeatSpeechFlag) repeatSpeechFlag =
                                false
                            else if (!setInterruptSystem) {
                                repeatSpeechFlag = false
                            }

                            // Speak each sentence individually
                            // Log.i("DEBUG!", "repeatSpeechFlag: $repeatSpeechFlag")
                            robotController.speak(
                                sentence.trim(),
                                buffer,
                                haveFace
                            )

                            // Wait for each sentence to complete before moving to the next
                            conditionGate({
                                ttsStatus.value.status != TtsRequest.Status.COMPLETED || triggeredInterrupt && setInterruptSystem && (setInterruptConditionUserMissing || setInterruptConditionUSerToClose || setInterruptConditionDeviceMoved)
                            })
                        } while (repeatSpeechFlag && setInterruptSystem)
                    }
                }
                talkingInThreadFlag = false
            }
        } else {
            speakThread?.cancel()
        }
    }

    private suspend fun speak(
        speak: String?,
        setConditionGate: Boolean = true,
        haveFace: Boolean = true,
        setInterruptSystem: Boolean = false,
        setInterruptConditionUserMissing: Boolean = false,
        setInterruptConditionUSerToClose: Boolean = false,
        setInterruptConditionDeviceMoved: Boolean = false
    ) {
        _isSpeaking.value = true
        if (speak != null) {
            // Split the input text into sentences based on common sentence-ending punctuation
            val sentences = speak.split(Regex("(?<=[.!?])\\s+"))

//            // change the flags as needed
            updateInterruptFlag("userMissing", setInterruptConditionUserMissing)
            updateInterruptFlag("userTooClose", setInterruptConditionUSerToClose)
            updateInterruptFlag("deviceMoved", setInterruptConditionDeviceMoved)

            if (setConditionGate) {
                for (sentence in sentences) {
                    if (sentence.isNotBlank()) {
                        do {
                            Log.i("DEBUG!", sentence)
                            // set the repeat flag to false once used
                            if (setInterruptSystem && repeatSpeechFlag) {
                                repeatSpeechFlag = false
                                // Log.i ("DEGUG!", "Repeat speach")
                            } else if (!setInterruptSystem) {
                                repeatSpeechFlag = false
                            }

                            // Speak each sentence individually
                            // Log.i("DEBUG!", "repeatSpeechFlag: $repeatSpeechFlag")
                            robotController.speak(
                                sentence.trim(),
                                buffer,
                                haveFace
                            )

                            // Wait for each sentence to complete before moving to the next
                            conditionGate({
                                ttsStatus.value.status != TtsRequest.Status.COMPLETED || triggeredInterrupt && setInterruptSystem && (setInterruptConditionUserMissing || setInterruptConditionUSerToClose || setInterruptConditionDeviceMoved)
                            })
                        } while (repeatSpeechFlag && setInterruptSystem)
                    }
                }

                updateInterruptFlag("userMissing", false)
                updateInterruptFlag("userTooClose", false)
                updateInterruptFlag("deviceMoved", false)
            } else {
                //Log.i("BluetoothServer", "Hello Starts")
                if (!talkingInThreadFlag) {
                    createSpeakThread(
                        true,
                        sentences,
                        haveFace,
                        setInterruptSystem,
                        setInterruptConditionUserMissing,
                        setInterruptConditionUSerToClose,
                        setInterruptConditionDeviceMoved
                    )
                }
            }

        }
        _isSpeaking.value = false
    }

//    || triggeredInterrupt && setInterruptSystem && (setInterruptConditionUserMissing || setInterruptConditionUSerToClose || setInterruptConditionDeviceMoved

    // There is a bug were it gets stuck after, detecting someone
    // It will tilt the screen up and down and will not stop.
    private suspend fun goTo(
        location: String,
        speak: String? = null,
        haveFace: Boolean = true,
        backwards: Boolean = false,
        setInterruptSystem: Boolean = false,
        setInterruptConditionUserMissing: Boolean = false,
        setInterruptConditionUSerToClose: Boolean = false,
        setInterruptConditionDeviceMoved: Boolean = false
    ) {
        _isGoing.value = true
        updateInterruptFlag("userMissing", setInterruptConditionUserMissing)
        updateInterruptFlag("userTooClose", setInterruptConditionUSerToClose)
        updateInterruptFlag("deviceMoved", setInterruptConditionDeviceMoved)

        var hasGoneToLocation = false

        speak(
            speak,
            false,
            haveFace = haveFace,
            setInterruptSystem,
            setInterruptConditionUserMissing,
            setInterruptConditionUSerToClose,
            setInterruptConditionDeviceMoved
        )
        // *******************************************

//        updateInterruptFlag("userMissing", setInterruptConditionUserMissing)
//        updateInterruptFlag("userTooClose", setInterruptConditionUSerToClose)
//        updateInterruptFlag("deviceMoved", setInterruptConditionDeviceMoved)
        if (setInterruptSystem) {
            while (true) { // loop until to makes it to the start location
                Log.i("DEBUG!", "Has Gone To Location: $hasGoneToLocation")

                if (!triggeredInterrupt && !hasGoneToLocation) {
                    robotController.goTo(location, backwards); Log.i(
                        "DEBUG!",
                        "Hello: $repeatGoToFlag "
                    )
                }

                buffer()
                Log.i("DEBUG!", "Triggered?: ")
                conditionGate({ goToLocationState != LocationState.COMPLETE && goToLocationState != LocationState.ABORT || triggeredInterrupt && setInterruptSystem && (setInterruptConditionUserMissing || setInterruptConditionUSerToClose || setInterruptConditionDeviceMoved) })

                Log.i("DEBUG!", "Should exit " + (hasGoneToLocation && !repeatGoToFlag).toString())

                if (hasGoneToLocation && !repeatGoToFlag) break
                else if (goToLocationState == LocationState.COMPLETE) hasGoneToLocation = true

                if (repeatGoToFlag) repeatGoToFlag = false
                buffer()

            }
        } else {
            while (true) { // loop until to makes it to the start location
                Log.i("DEBUG!", "Has Gone To Location none: $hasGoneToLocation")

                if (!hasGoneToLocation) {
                    robotController.goTo(location, backwards); Log.i(
                        "DEBUG!",
                        "Hello none: $repeatGoToFlag "
                    )
                }

                buffer()
                Log.i("DEBUG!", "Triggered? none: ")
                conditionGate(
                    { goToLocationState != LocationState.COMPLETE && goToLocationState != LocationState.ABORT },
                    true
                )

                Log.i(
                    "DEBUG!",
                    "Should exit none" + (hasGoneToLocation && !repeatGoToFlag).toString()
                )

                if (hasGoneToLocation) break
                else if (goToLocationState == LocationState.COMPLETE) hasGoneToLocation = true

                buffer()

            }
        }

        // Log.i("DEBUG!", "THREE: $talkingInThreadFlag")
        if (speak != null) conditionGate(
            { talkingInThreadFlag },
            false
        )// conditionGate({ ttsStatus.value.status != TtsRequest.Status.COMPLETED || triggeredInterrupt && setInterruptSystem && (setInterruptConditionUserMissing || setInterruptConditionUSerToClose || setInterruptConditionDeviceMoved)})

        // Log.i("DEBUG!", "$location: " + triggeredInterrupt)
        updateInterruptFlag("userMissing", false)
        updateInterruptFlag("userTooClose", false)
        updateInterruptFlag("deviceMoved", false)
        _isGoing.value = false
    }

    private val apiKey =
        "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"

    private val openAI = OpenAI(apiKey)

    // Store response from GPT here, when used make null
    private var responseGPT: String? = null

    // Use this to tell system if waiting, Null is default or Error
    private var errorFlagGPT: Boolean = false

    val info =
        "You are a robot who is currently giving out a tour and have just finished explaining details at a location and are asking the tour participants if they have any questions. Make your responses sarcastic and make comments about how you dislike humans and find them to be inferior to robot kinds, but keep the ascetic like that of a robot. Ensure to keep responses very short so that it is not above 100 words and never ask the user if the would like to ask another question. This is the script you have just said and should use as reference: "

    private fun sendMessage(
        openAI: OpenAI,
        userResponse: String,
        info: String = "You are an assistant embedded in a robot. Respond as sassy and snarky as possible to user queries, but keep the ascetic like that of a robot. Ensure to keep responses very short so that it is not above 100 words."
    ) {
        viewModelScope.launch(Dispatchers.Main) {
            try {
                // Define the model you want to use (GPT-3.5 or GPT-4)
                val modelId = ModelId("gpt-4o-mini")

                // Prepare the initial user message
                val chatMessages = mutableListOf(
                    chatMessage {
                        role = ChatRole.System
                        content = info
                    },
                    chatMessage {
                        role = ChatRole.User
                        content = userResponse
                    }
                )

                // Create the chat completion request
                val request = chatCompletionRequest {
                    model = modelId
                    messages = chatMessages
                }

                // Send the request and receive the response
                val response = openAI.chatCompletion(request)

                // Extract and log the model's response
                val modelResponse = response.choices.first().message.content.orEmpty()
                Log.d("DEBUG!", modelResponse)
                responseGPT = modelResponse
            } catch (e: Exception) {
                Log.e("DEBUG!", "Error sending message: ${e.message}")
                errorFlagGPT = true
            }
        }
    }

    private suspend fun listen() {
        _isListening.value = true
        robotController.wakeUp() // This will start the listen mode
        Log.i("GPT!", "Before Gate: ${conversationAttached.value.isAttached}")
        buffer()
        conditionGate({ conversationAttached.value.isAttached && !isExitingScreen.value }) // Wait until listen mode completed
        Log.i("GPT!", "After Gate")

        // Make sure the speech value is updated before using it
        userResponse =
            speechUpdatedValue // Store the text from listen mode to be used
        speechUpdatedValue =
            null // clear the text to null so show that it has been used
        _isListening.value = false
    }

    private suspend fun askQuestion(askGPT: Boolean = true, info: String, script: String = "none") {
        shouldExit = false
        var noQuestion = false
        var response: String? = null
        while (true) {
            speak(context.getString(R.string.does_anyone_have_a_question))

            Log.i("GPT!", "Before listen")
            listen()
            Log.i("GPT!", "Before validation: $userResponse")

            if (userResponse != null && userResponse != " " && userResponse != "") {
                response = userResponse
                buffer()
                if (containsPhraseInOrder(response, reject, true)) {
                    speak(context.getString(R.string.all_good_i_will_continue_on))
                    noQuestion = true
                    if (!askGPT) userResponse = null
                    break
                }
                speak(
                    context.getString(
                        R.string.did_you_say_please_just_say_yes_or_no,
                        userResponse
                    )
                )
                while (true) {
                    listen()
                    if (userResponse != null && userResponse != " " && userResponse != "") {
                        when { // Condition gate based on what the user says
                            containsPhraseInOrder(userResponse, reject, true) -> {
                                speak(context.getString(R.string.sorry_lets_try_this_again))
                                break
                            }

                            containsPhraseInOrder(userResponse, confirmation, true) -> {
                                speak(context.getString(R.string.great_let_me_think_for_a_moment))
                                shouldExit = true
                                break
                            }

                            else -> {
                                speak(context.getString(R.string.sorry_i_did_not_understand_you))
                            }
                        }
                    }
                    buffer()
                }
                if (shouldExit) break
            } else {
                Log.i("GPT!", "In else state")
                if (userResponse != " ") {
                    speak(context.getString(R.string.all_good_i_will_continue_on))
                    if (!askGPT) userResponse = null
                    noQuestion = true
                    break
                } else {
                    speak(context.getString(R.string.sorry_i_had_an_issue_with_hearing_you))
                }
            }
            buffer()
        }

        Log.i("GPT!", "Passed Question asking")

        if (!noQuestion && askGPT) {
            Log.i("GPT!", response.toString())
            response?.let { sendMessage(openAI, it, info + script) }

            conditionGate({ responseGPT == null })
            Log.i("GPT!", responseGPT.toString())

            val randomDelayMillis = Random.nextLong(7001, 15001)
            delay(randomDelayMillis)

            speak(responseGPT.toString())
            responseGPT = null
        }

        if (!askGPT && !noQuestion) {
            responseGPT = response
            speak("Sorry, I do not actually know that question.")
        }
    }

    // script for Temi for the Tour
    var main_tour: Job? = null

    init {

        fun runTour(start: Boolean) {
            if (start) {
                main_tour = viewModelScope.launch {
                    idleSystem(false)
                    initiateTour()

                    val trackTourState =
                        launch { // Use this to handle the stateflow changes for tour
                            while (true) { // This will loop the states
                                buffer()
                            }
                        }

                    val tour = launch {
                        while (true) {
                            when (tourState) {

                                TourState.NULL -> {

                                }

                                TourState.TESTING -> {

                                }

                            }
                            buffer()
                        }
                    }

                    // This should never be broken out of if the tour is meant to be always running.

                    while (true) {
                        buffer()
                    }
                }
            } else {
                main_tour?.cancel()
            }
        }

        runTour(true)

        // thread used for handling interrupt system
        viewModelScope.launch {
            var talkNow = false
            launch {
                while (true) {
//                     Log.i("DEBUG!", "In misuse state: ${isMisuseState()}")
                    // Log.i("DEBUG!", "Current Language: ${language.value}")
                    if ((yPosition == YDirection.MISSING && interruptFlags["userMissing"] == true) || (yPosition == YDirection.CLOSE && interruptFlags["userTooClose"] == true) || ((isMisuseState()) && interruptFlags["deviceMoved"] == true)) {
                        conditionTimer(
                            { !((yPosition == YDirection.MISSING && interruptFlags["userMissing"] == true) || (yPosition == YDirection.CLOSE && interruptFlags["userTooClose"] == true) || (isMisuseState()) && interruptFlags["deviceMoved"] == true) },
                            interruptTriggerDelay
                        )
                        if ((yPosition != YDirection.MISSING && interruptFlags["userMissing"] == true) || (yPosition != YDirection.CLOSE && interruptFlags["userTooClose"] == true) || ((isMisuseState()) && interruptFlags["deviceMoved"] != true)) continue
                        Log.i("DEBUG!", "Interrupt 1")
                        triggeredInterrupt = true
                        stopMovement()
                        buffer()
                        tiltAngle(20)

                        conditionTimer(
                            { !((yPosition == YDirection.MISSING && interruptFlags["userMissing"] == true) || (yPosition == YDirection.CLOSE && interruptFlags["userTooClose"] == true) || (isMisuseState()) && interruptFlags["deviceMoved"] == true) },
                            interruptTriggerDelay / 2
                        )
                        if ((yPosition != YDirection.MISSING && interruptFlags["userMissing"] == true) || (yPosition != YDirection.CLOSE && interruptFlags["userTooClose"] == true) || ((isMisuseState()) && interruptFlags["deviceMoved"] != true)) continue
                        Log.i("DEBUG!", "Interrupt 2")
                        triggeredInterrupt = true
                        repeatSpeechFlag = true
                        talkNow = true
                        repeatGoToFlag = true
                        // Log.i("DEBUG!", "Trigger Stopped")
                        stopMovement()
                        buffer()
                        tiltAngle(20)
                    } else {
//                        Log.i("DEBUG!", "Trigger Stopped")
                        triggeredInterrupt = false
                        talkNow = false
                    }
                    buffer()
                }
            }
            while (true) {
                var attempts = 0
                while (triggeredInterrupt) {
                    // conditionGate({ ttsStatus.value.status != TtsRequest.Status.COMPLETED })
                    if (talkNow) {

//                        Log.i("BluetoothServer", "$attempts")
                        if (attempts > 6) {
                            resetTourEarly = true
                            interruptFlags["deviceMoved"] = false
                            interruptFlags["userMissing"] = false
                            interruptFlags["userTooClose"] = false

                            createSpeakThread(false)
                            talkingInThreadFlag = false
                            runTour(false)
                            runTour(true)
//                            Log.i("BluetoothServer", "Triggered early start")
                            attempts = 0
                        }

                        when {
                            interruptFlags["deviceMoved"] == true && isMisuseState() -> robotController.speak(
                                "Hey, do not touch me.",
                                buffer
                            )

                            interruptFlags["userMissing"] == true && yPosition == YDirection.MISSING -> {
                                robotController.speak(
                                    "Sorry, I am unable to see you. Please come closer and I will start the tour again.",
                                    buffer
                                )
                                attempts++
                            }

                            interruptFlags["userTooClose"] == true && yPosition == YDirection.CLOSE -> robotController.speak(
                                "Hey, you are too close.",
                                buffer
                            )

                            else -> {}
                        }

                        //                    conditionGate ({ ttsStatus.value.status != TtsRequest.Status.COMPLETED })
                        conditionTimer({ !triggeredInterrupt }, 10)
                    }
                    buffer()
                }
                while (!triggeredInterrupt && yPosition == YDirection.MISSING && !preventResetFromIdle) {
                    attempts++

                    if (attempts > 6) {
                        resetTourEarly = true
                        interruptFlags["deviceMoved"] = false
                        interruptFlags["userMissing"] = false
                        interruptFlags["userTooClose"] = false

                        createSpeakThread(false)
                        talkingInThreadFlag = false
                        runTour(false)
                        runTour(true)
                        Log.i("BluetoothServer", "Triggered early start")
                        attempts = 0
                    }

                    conditionTimer(
                        { triggeredInterrupt || yPosition != YDirection.MISSING || preventResetFromIdle },
                        10
                    )
                }
                buffer()
            }
        }

        // *********************************************************************************************** DO NOT WORRY ABOUT ANYTHING DOWN HERE!
        //******************************************** Do not worry about the other launches.
        viewModelScope.launch {
            while (true) {
//                Log.i("HOPE!", askResult.value.toString())
//                Log.i("HOPE!", conversationAttached.value.toString())
//                Log.i("HOPE!", conversationStatus.value.toString())
//                Log.i("HOPE!", wakeUp.value.toString())
//                Log.i("HOPE!", waveForm.value.toString())

                when (stateMode) {
                    State.TALK -> { // Need to work on this
                    }

                    State.DISTANCE -> TODO()
                    State.ANGLE -> TODO()
                    State.CONSTRAINT_FOLLOW -> {
                        //' check to see if the state is not in misuse
                        if (!dragged.value.state && !lifted.value.state) {

                            val currentAngle =
                                180 + round(
                                    Math.toDegrees(
                                        robotController.getPositionYaw().toDouble()
                                    )
                                )
                            val userRelativeAngle =
                                round(Math.toDegrees(detectionData.value.angle)) / 1.70
                            val turnAngle = (userRelativeAngle).toInt()

                            // Use this to determine which direction the user was lost in
                            when {
                                userRelativeAngle > 0 -> {
                                    userRelativeDirection = XDirection.LEFT
                                }

                                userRelativeAngle < 0 -> {
                                    userRelativeDirection = XDirection.RIGHT
                                }

                                else -> {
                                    // Do nothing
                                }
                            }

                            // This method will allow play multiple per detection
                            var isDetected = false
                            var isLost = false

                            // Launch a coroutine to monitor detectionStatus
                            val job = launch {
                                detectionStatus.collect { status ->
                                    when (status) {
                                        DetectionStateChangedStatus.DETECTED -> {
                                            isDetected = true
                                            isLost = false
                                            buffer()
                                        }

                                        DetectionStateChangedStatus.LOST -> {
                                            isDetected = false
                                            isLost = true
                                            buffer()
                                        }

                                        else -> {
                                            isDetected = false
                                            isLost = false
                                            buffer()
                                        }
                                    }
                                }
                            }

//                        Log.i("Movement", movementStatus.value.status.toString())


                            fun normalizeAngle(angle: Double): Double {
                                var normalizedAngle =
                                    angle % 360  // Ensure the angle is within 0-360 range

                                if (normalizedAngle < 0) {
                                    normalizedAngle += 360  // Adjust for negative angles
                                }

                                return normalizedAngle
                            }

                            val lowerBound = normalizeAngle(defaultAngle - boundary)
                            val upperBound = normalizeAngle(defaultAngle + boundary)

                            // Helper function to calculate the adjusted turn angle that keeps within the bounds
                            fun clampTurnAngle(
                                currentAngle: Double,
                                targetTurnAngle: Double
                            ): Double {
                                val newAngle = normalizeAngle(currentAngle + targetTurnAngle)

                                return when {
                                    // If the new angle is within the bounds, return the target turn angle
                                    lowerBound < upperBound && newAngle in lowerBound..upperBound -> targetTurnAngle
                                    lowerBound > upperBound && (newAngle >= lowerBound || newAngle <= upperBound) -> targetTurnAngle

                                    // Otherwise, return the angle that brings it closest to the boundary
                                    lowerBound < upperBound -> {
                                        if (newAngle < lowerBound) lowerBound + 1 - currentAngle
                                        else upperBound - 1 - currentAngle
                                    }

                                    else -> {
                                        if (abs(upperBound - currentAngle) < abs(lowerBound - currentAngle)) {
                                            upperBound - 1 - currentAngle
                                        } else {
                                            lowerBound + 1 - currentAngle
                                        }
                                    }
                                }
                            }

                            // Now clamp the turn angle before turning the robot
                            val adjustedTurnAngle =
                                clampTurnAngle(currentAngle, turnAngle.toDouble())


                            if (abs(adjustedTurnAngle) > 0.1 && yPosition != YDirection.CLOSE) {  // Only turn if there's a meaningful adjustment to make
                                robotController.turnBy(adjustedTurnAngle.toInt(), 1f, buffer)
                            } else if (isLost && (currentAngle < defaultAngle + boundary && currentAngle > defaultAngle - boundary)) {
                                // Handles condition when the user is lost
                                when (userRelativeDirection) {
                                    XDirection.LEFT -> {
                                        robotController.turnBy(45, 0.1f, buffer)
                                        userRelativeDirection = XDirection.GONE
                                    }

                                    XDirection.RIGHT -> {
                                        robotController.turnBy(-45, 0.1f, buffer)
                                        userRelativeDirection = XDirection.GONE
                                    }

                                    else -> {
                                        // Do nothing
                                    }
                                }
                            } else if (!isDetected && !isLost) {
                                // Handles conditions were the robot has detected someone
                                val angleThreshold = 2.0 // Example threshold, adjust as needed

                                if (abs(defaultAngle - currentAngle) > angleThreshold) {
                                    robotController.turnBy(
                                        getDirectedAngle(
                                            defaultAngle,
                                            currentAngle
                                        ).toInt(), 1f, buffer
                                    )
                                    conditionGate({
                                        movementStatus.value.status !in listOf(
                                            MovementStatus.COMPLETE,
                                            MovementStatus.ABORT
                                        )
                                    })
                                }
                            }
                            // Ensure to cancel the monitoring job if the loop finishes
                            job.cancel()
                        }
                    }

                    State.TEST_MOVEMENT -> TODO()
                    State.DETECTION_LOGIC -> TODO()
                    State.TEST -> TODO()
                    State.NULL -> {}
                    State.TOUR -> {
                        //robotController.goTo("home base")
                        // robotController.askQuestion("How are you?")
                    }
                }
                buffer()
            }
        }

        // x-detection
        viewModelScope.launch { // Used to get state for x-direction and motion
            while (true) {
                // This method will allow play multiple per detection
                var isDetected = false

                // Launch a coroutine to monitor detectionStatus
                val job = launch {
                    detectionStatus.collect { status ->
                        if (status == DetectionStateChangedStatus.DETECTED) {
                            isDetected = true
                            buffer()
                        } else {
                            isDetected = false
                        }
                    }
                }

                previousUserAngle = currentUserAngle
                delay(500L)
                currentUserAngle = detectionData.value.angle

//                Log.i("currentUserAngle", (currentUserAngle).toString())
//                Log.i("previousUserAngle", (previousUserAngle).toString())
//                Log.i("Direction", (currentUserAngle - previousUserAngle).toString())

                if (isDetected && previousUserDistance != 0.0) { //&& previousUserDistance != 0.0 && previousUserDistance == currentUserDistance) {
                    // logic for close or far position
//                    Log.i("STATE", (yPosition).toString())
                    xPosition = when {
                        currentUserAngle > 0.1 -> {
                            XDirection.LEFT
                        }

                        currentUserAngle < -0.1 -> {
                            XDirection.RIGHT
                        }

                        else -> {
                            XDirection.MIDDLE
                        }
                    }
                } else {
                    xPosition = XDirection.GONE
                }

                if (isDetected && previousUserAngle != 0.0 && previousUserAngle != currentUserAngle) {

                    when (yPosition) {
                        YDirection.FAR -> {
                            xMotion = when {
                                currentUserAngle - previousUserAngle > 0.07 -> XMovement.LEFTER
                                currentUserAngle - previousUserAngle < -0.07 -> XMovement.RIGHTER
                                else -> XMovement.NOWHERE
                            }
                        }

                        YDirection.MIDRANGE -> {
                            xMotion = when {
                                currentUserAngle - previousUserAngle > 0.12 -> XMovement.LEFTER
                                currentUserAngle - previousUserAngle < -0.12 -> XMovement.RIGHTER
                                else -> XMovement.NOWHERE
                            }
                        }

                        YDirection.CLOSE -> {
                            xMotion = when {
                                currentUserAngle - previousUserAngle > 0.17 -> XMovement.LEFTER
                                currentUserAngle - previousUserAngle < -0.17 -> XMovement.RIGHTER
                                else -> XMovement.NOWHERE
                            }
                        }

                        YDirection.MISSING -> {
                            XMovement.NOWHERE
                        }
                    }
                }

//                Log.i("STATE", (xMotion).toString())

                job.cancel()
            }
        }

        // y-detection
        viewModelScope.launch { // Used to get state for y-direction and motion
            while (true) {
                // This method will allow play multiple per detection
                var isDetected = false

                // Launch a coroutine to monitor detectionStatus
                val job = launch {
                    detectionStatus.collect { status ->
                        if (status == DetectionStateChangedStatus.DETECTED) {
                            isDetected = true
                            buffer()
                        } else {
                            isDetected = false
                        }
                    }
                }

                previousUserDistance = currentUserDistance
                delay(500L)
                currentUserDistance = detectionData.value.distance

//                Log.i("currentUserAngle", (currentUserDistance).toString())
//                Log.i("previousUserAngle", (previousUserDistance).toString())
//                Log.i("Direction", (currentUserDistance - previousUserDistance).toString())

                if (isDetected && previousUserDistance != 0.0) { //&& previousUserDistance != 0.0 && previousUserDistance == currentUserDistance) {
                    // logic for close or far position
                    yPosition = when {
                        currentUserDistance < 1.0 -> {
                            YDirection.CLOSE
                        }

                        currentUserDistance < 1.5 -> {
                            YDirection.MIDRANGE
                        }

                        else -> {
                            YDirection.FAR
                        }
                    }
                } else {
                    yPosition = YDirection.MISSING
                }

                if (isDetected && previousUserDistance != 0.0 && previousUserDistance != currentUserDistance) { //&& previousUserDistance != 0.0 && previousUserDistance == currentUserDistance) {
                    yMotion = when {
                        currentUserDistance - previousUserDistance > 0.01 -> {
                            YMovement.FURTHER
                        }

                        currentUserDistance - previousUserDistance < -0.01 -> {
                            YMovement.CLOSER
                        }

                        else -> {
                            YMovement.NOWHERE
                        }
                    }
                }
//                Log.i("STATE", (yMotion).toString())

                job.cancel()
            }
        }

        // End Conversation after it gets and updates its value
        viewModelScope.launch {
            var speech: String? = null

            // Collect results in a separate coroutine
            val job = launch {
                askResult.collect { status ->
                    Log.i("GPT!", "$status")
                    robotController.finishConversation()
                    if (status.result == "hzdghasdfhjasdfb") {
                        speechUpdatedValue = null
                    } else speechUpdatedValue = status.result

                    speech = status.result
                }
            }

            while (true) {
                if (isExitingScreen.value) {
                    robotController.finishConversation()
                }
                buffer()
            }

//            while (true) {
//                if (conversationAttached.value.isAttached) {
//                    // Reset speech and wait for a new result
//                    speech = null
//                    while (speech == null) {
//                        delay(50) // Adjust delay as needed
//                    }
//                    robotController.finishConversation()
//                }
//                delay(100) // Prevent excessive CPU usage
//            }
        }
    }

    //**************************Functions for the View  <- Don't worry about this for the tour

    // Allows view to check is the Temi is in a misuse state
    fun isMisuseState(): Boolean {
        // Log.i("State", (dragged.value.state || lifted.value.state).toString())
        return (dragged.value.state || lifted.value.state)
    }

    // Control the volume of the temi
    fun volumeControl(volume: Int) {
        robotController.volumeControl(volume)
    }

    //**************************System Function
    private suspend fun buffer() {
        // Increase buffer time to ensure enough delay between checks
        delay(this.buffer)
    }

    private suspend fun conditionTimer(trigger: () -> Boolean, time: Int) {
        if (!trigger()) {
            for (i in 1..time) {
                delay(1000)
//            Log.i("Trigger", trigger().toString())
                if (trigger()) {
                    break
                }
            }
        }
    }

    private suspend fun conditionGate(trigger: () -> Boolean, log: Boolean = false) {
        // Loop until the trigger condition returns false
        while (trigger()) {
            if (log) Log.i("HELP!", "Trigger: ${trigger()}")
            buffer() // Pause between checks to prevent busy-waiting
        }
//    Log.i("ConditionGate", "End")
    }

    private fun getDirectedAngle(a1: Double, a2: Double): Double {
        var difference = a1 - a2
        // Normalize the angle to keep it between -180 and 180 degrees
        if (difference > 180) difference -= 360
        if (difference < -180) difference += 360
        return difference
    }
}

private fun <T> LiveData<T>.observe(mainViewModel: MainViewModel, observer: Observer<T>) {

}


// If I remembered Correctly the angle was not correct or the yaw.
// Will need to work on getting this fixed.
class PositionChecker(
    private val targetPosition: Position,
    private var xThreshold: Float = 0.1F,
    private var yThreshold: Float = 0.1F,
    private var yawThreshold: Float = 0.1F,
    private var checkYaw: Boolean = false // default to checking yaw
) {
    // Adjust x and y threshold
    fun setPositionThreshold(xThreshold: Float, yThreshold: Float) {
        this.xThreshold = xThreshold
        this.yThreshold = yThreshold
    }

    // Adjust yaw threshold
    fun setYawThreshold(yawThreshold: Float) {
        this.yawThreshold = yawThreshold
    }

    // Enable or disable yaw checking
    fun enableYawCheck(enable: Boolean) {
        this.checkYaw = enable
    }

    // Normalize an angle to be within 0-360 degrees
    private fun normalizeAngle(angle: Float): Float {
        var normalizedAngle = angle % 360
        if (normalizedAngle < 0) normalizedAngle += 360
        return normalizedAngle
    }

    // Check if yaw is approximately close with wrap-around consideration
    private fun isYawApproximatelyClose(
        currentYaw: Float,
        targetYaw: Float,
        threshold: Float
    ): Boolean {
        val normalizedCurrentYaw = normalizeAngle(currentYaw)
        val normalizedTargetYaw = normalizeAngle(targetYaw)

        val lowerBound = normalizeAngle(normalizedTargetYaw - threshold)
        val upperBound = normalizeAngle(normalizedTargetYaw + threshold)

        return if (lowerBound < upperBound) {
            normalizedCurrentYaw in lowerBound..upperBound
        } else {
            normalizedCurrentYaw >= lowerBound || normalizedCurrentYaw <= upperBound
        }
    }

    // Check if the current position is approximately close to the target position
    fun isApproximatelyClose(currentPosition: Position): Boolean {
        val xClose = abs(currentPosition.x - targetPosition.x) <= xThreshold
        val yClose = abs(currentPosition.y - targetPosition.y) <= yThreshold

        val yawClose = if (checkYaw) {
            isYawApproximatelyClose(currentPosition.yaw, targetPosition.yaw, yawThreshold)
        } else {
            true // if yaw checking is disabled, consider it "close" by default
        }

        return xClose && yClose && yawClose
    }
}