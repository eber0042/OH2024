package com.temi.oh2024

import android.annotation.SuppressLint
import android.os.Build.VERSION.SDK_INT
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import com.robotemi.sdk.navigation.model.SpeedLevel
import com.temi.oh2024.ui.theme.OH2024Theme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// This is used to record all screens that are currently present in the app
enum class Screen() {
    Home,
    GeneralQuestions,
    DirectionsAndLocations,
    Tours,
    RB
}

// Need to look more into the cases for the ask question, people are able to break when the should.

@Composable
fun Gif(imageId: Int) {
    // Determine the image resource based on shouldPlayGif
    val gifEnabledLoader = ImageLoader.Builder(LocalContext.current)
        .components {
            if (SDK_INT >= 28) {
                add(ImageDecoderDecoder.Factory())
            } else {
                add(GifDecoder.Factory())
            }
        }
        .build()

    AsyncImage(
        model = imageId, // Resource or URL of the image/GIF
        contentDescription = "Animated GIF",
        imageLoader = gifEnabledLoader,
        modifier = Modifier
            .fillMaxSize() // Fill the whole screen
            .pointerInput(Unit) {
            },
        contentScale = ContentScale.Crop // Crop to fit the entire screen
    )
}

@Composable
fun GifWithFadeIn(imageId: Int) {
    // Create an animation state to control visibility
    var isVisible by remember { mutableStateOf(false) }

    // Trigger the fade-in animation with a delay
    LaunchedEffect(Unit) {
        delay(2000) // Add a 500ms delay before starting the fade-in
        isVisible = true
    }

    // Build the custom ImageLoader for GIFs
    val gifEnabledLoader = ImageLoader.Builder(LocalContext.current)
        .components {
            if (SDK_INT >= 28) {
                add(ImageDecoderDecoder.Factory())
            } else {
                add(GifDecoder.Factory())
            }
        }
        .build()

    // Animated visibility with fade-in effect
    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(animationSpec = tween(durationMillis = 1000)), // 1000ms fade-in
        modifier = Modifier.fillMaxSize() // Ensure it fills the screen
    ) {
        AsyncImage(
            model = imageId, // Resource or URL of the image/GIF
            contentDescription = "Animated GIF",
            imageLoader = gifEnabledLoader,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {},
            contentScale = ContentScale.Crop // Crop to fit the entire screen
        )
    }
}

@Composable
fun RefreshExit(viewModel: MainViewModel, delay: Long = 1000) {
    LaunchedEffect(Unit) {
        delay(delay) // Wait for timeout
        viewModel.setIsExitingScreen(false)
        viewModel.setShowImageFlag(false)
    }
}

@Composable
fun BlackBackgroundBox() {
    Box(
        modifier = Modifier
            .fillMaxSize() // This ensures the Box takes up the entire screen
            .background(Color.Black) // Sets the background color to black
    ) {
        // Add any content here if needed
    }
}

// Add sound effects here
// Naming convention is the type of audio follow my description
val buttonSoundEffect_main = R.raw.soundeffect_buttonsound
val buttonSoundEffect_secondary = R.raw.soundeffect_buttonsound1

val music_wait = R.raw.music_wait

val gif_thinking = R.drawable.galaxy_brain_meme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private var timerJob: Job? = null // Coroutine Job for the timer
    private var lastInteractionTime: Long =
        System.currentTimeMillis() // Track last interaction time
    private val timeoutPeriod: Long = 30000 // Timeout period in milliseconds
    // Initializing the viewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            OH2024Theme {
                // Initialize the navigation controller
                val navController = rememberNavController()
                val viewModel: MainViewModel = hiltViewModel()

                val currentScreen = remember { mutableStateOf(Screen.Home.name) }

                // Used for resetting the time out event
                val isThinking by viewModel.isChatGptThinking.collectAsState()
                val isTalking by viewModel.isTalking.collectAsState()
                val isGoing by viewModel.isGoing.collectAsState()
                val isListening by viewModel.isListening.collectAsState()

                // Effect to monitor the flags and reset the timer
                LaunchedEffect(Unit) {
                    while (true) {
                        if (isThinking || isTalking || isGoing || isListening) {
                            lastInteractionTime = System.currentTimeMillis()
                        }
                        delay(1000)
                    }
                }

                // Observe current back stack entry to detect navigation changes
                LaunchedEffect(navController) {
                    navController.addOnDestinationChangedListener { _, destination, _ ->
                        currentScreen.value = destination.route ?: Screen.Home.name

                        // Start or reset timer based on the current screen
                        if (currentScreen.value == Screen.Home.name) {
                            stopTimer()
                        } else {
                            startTimer(navController)
                        }
                    }
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    // Navigation Host
                    NavHost(
                        navController = navController,
                        startDestination = Screen.Home.name,
                        modifier = Modifier.padding(innerPadding)
                    ) {
                        // Define the screens (composable destinations)
                        composable(route = Screen.Home.name) {
                            LaunchedEffect(Unit) {
                                delay(10000)
                                viewModel.setGreetMode(true)
                            }
                            viewModel.setSpeed(SpeedLevel.SLOW)
                            HomeScreen(
                                navController,
                                viewModel
                            )  // Passing viewModel here
                        }
                        composable(route = Screen.GeneralQuestions.name) {
                            viewModel.setGreetMode(false)
                            GeneralQuestionsScreen(
                                navController,
                                viewModel
                            )  // Passing viewModel here
                        }
                        composable(route = Screen.DirectionsAndLocations.name) {
                            viewModel.setGreetMode(false)
                            viewModel.setSpeed(SpeedLevel.HIGH)
                            DirectionsAndLocationsScreen(
                                navController,
                                viewModel
                            )  // Passing viewModel here
                        }
                        composable(route = Screen.Tours.name) {
                            viewModel.setGreetMode(false)
                            ToursScreen(navController, viewModel)  // Passing viewModel here
                        }
                        composable(route = Screen.RB.name) {
                            viewModel.setGreetMode(false)
                            RBScreen(navController, viewModel)  // Passing viewModel here
                        }
                    }
                }
            }
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev != null) {
            resetTimer() // Reset the timer on any touch event
        }
        return super.dispatchTouchEvent(ev) // Ensure normal event handling
    }

    private fun startTimer(navController: NavController) {
        // Cancel any existing timer
        timerJob?.cancel()
        // Start a new timer
        timerJob = CoroutineScope(Dispatchers.Main).launch {
            while (true) {
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastInteractionTime >= timeoutPeriod) {
                    withContext(Dispatchers.Main) {
                        navController.navigate(Screen.Home.name) {
                            popUpTo(Screen.Home.name) { inclusive = true }
                        }
                    }
                    break
                }
                Log.i("TIMEOUT!", "${currentTime - lastInteractionTime}")
                delay(1000) // Check conditions every second
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
    }

    private fun resetTimer() {
        // Update last interaction time and restart the timer
        lastInteractionTime = System.currentTimeMillis()
    }
}

@Composable
fun HomeScreen(navController: NavController, viewModel: MainViewModel) {
    RefreshExit(viewModel)
    // -- Control UI elements --

    // Button
    val buttonHeight = 100.dp
    val buttonFillWidth = 0.8f
    val buttonSpacing = 16.dp
    //Text
    val fontSize = 48.sp

    BlackBackgroundBox()

    // Use a Column to stack the buttons vertically
    Column(
        modifier = Modifier
            .fillMaxSize() // Occupy the entire screen space
            .padding(16.dp), // Add some padding to the edges
        verticalArrangement = Arrangement.Center, // Center items vertically
        horizontalAlignment = Alignment.CenterHorizontally // Center items horizontally
    ) {
        // Button 1
        Button(
            onClick = {
                navController.navigate(Screen.GeneralQuestions.name)
                viewModel.playSoundEffect(buttonSoundEffect_main)
            },
            modifier = Modifier
                .fillMaxWidth(buttonFillWidth) // Width is 80% of the screen
                .height(buttonHeight), // Increase the height of the buttons
        ) {
            Text("General Questions", fontSize = fontSize)
        }

        Spacer(modifier = Modifier.height(buttonSpacing)) // Space between buttons

        // Button 2
        Button(
            onClick = {
                navController.navigate(Screen.DirectionsAndLocations.name)
                viewModel.playSoundEffect(buttonSoundEffect_main)
            },
            modifier = Modifier
                .fillMaxWidth(buttonFillWidth)
                .height(buttonHeight),
        ) {
            Text("Directions and Locations", fontSize = fontSize)
        }

        Spacer(modifier = Modifier.height(buttonSpacing)) // Space between buttons

        // Button 3
        Button(
            onClick = {
                navController.navigate(Screen.Tours.name)
                viewModel.playSoundEffect(buttonSoundEffect_main)
            },
            modifier = Modifier
                .fillMaxWidth(buttonFillWidth)
                .height(buttonHeight),
        ) {
            Text("Tours", fontSize = fontSize)
        }

        Spacer(modifier = Modifier.height(buttonSpacing)) // Space between buttons

        // Button 4
        Button(
            onClick = {
                navController.navigate(Screen.RB.name)
                viewModel.playSoundEffect(buttonSoundEffect_main)
            },
            modifier = Modifier
                .fillMaxWidth(buttonFillWidth)
                .height(buttonHeight),

        ) {
            Text("Explanation", fontSize = fontSize)
        }
    }

    val detectionState by viewModel.detectionState.collectAsState()

    Log.i("Testing3", "$detectionState")

    LaunchedEffect(Unit) {

    }

    val idleFaceHome by viewModel.isIdleFaceHome.collectAsState()
    val greetMode by viewModel.isGreetMode.collectAsState()

    Log.i("Testing5", "$idleFaceHome")

    //*******************************************************************Created Image Generator

    if (!idleFaceHome && greetMode) {
        GifWithFadeIn(R.drawable.idle)
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun GeneralQuestionsScreen(navController: NavController, viewModel: MainViewModel) {
    RefreshExit(viewModel, 100)

    // Flags
    val showImage by viewModel.showImageFlag.collectAsState()
    val showWaitSequence by viewModel.isChatGptThinking.collectAsState()

    // Add time out for the image
    val timeoutDuration = 5000L // Timeout duration (5 seconds)
    // Start a timeout event (timeoutDuration) to hide the image
    LaunchedEffect(showImage) {
        if (showImage) {
            delay(timeoutDuration) // Wait for timeout
            viewModel.setShowImageFlag(false) // Hide the image after timeout
        }
    }

    // Sample list of question types (replace with your actual data)
    // Note that if you do not want to use an image with a question, just leave it as null
    val questionTypes = listOf(
        Triple(
            "What is Temi?",
            "Temi is a personal robot designed for various assistive and entertainment tasks. This other sentence is here for testing a particular issues that way or may not occur.",
            R.drawable.sample_image
        ),
        Triple(
            "How do I use Temi?",
            "You can use Temi by giving voice commands, using the touchscreen, or the mobile app.",
            R.drawable.sample_image
        ),
        Triple(
            "What features does Temi have?",
            "Temi features include autonomous navigation, voice recognition, and video calling.",
            R.drawable.sample_image
        ),
        Triple(
            "How can Temi assist me?",
            "Temi can assist you with tasks like scheduling, navigation, and connecting to smart devices.",
            R.drawable.sample_image
        ),
        Triple(
            "What are Temi's limitations?",
            "Temi cannot perform heavy lifting or tasks that require physical dexterity.",
            R.drawable.sample_image
        ),
        Triple("SAMPLE", "Sample dialogue for future question 1.", null),
        Triple("SAMPLE", "Sample dialogue for future question 2.", null),
        Triple("SAMPLE", "Sample dialogue for future question 3.", null),
        Triple("SAMPLE", "Sample dialogue for future question 4.", null),
        Triple("SAMPLE", "Sample dialogue for future question 5.", null),
        Triple("SAMPLE", "Sample dialogue for future question 6.", null),
        Triple("SAMPLE", "Sample dialogue for future question 7.", null),
        Triple("SAMPLE", "Sample dialogue for future question 8.", null),
        Triple("SAMPLE", "Sample dialogue for future question 9.", null),
        Triple("SAMPLE", "Sample dialogue for future question 10.", null),
        Triple("SAMPLE", "Sample dialogue for future question 11.", null),
        Triple("SAMPLE", "Sample dialogue for future question 12.", null),
        Triple("SAMPLE", "Sample dialogue for future question 13.", null),
        Triple("SAMPLE", "Sample dialogue for future question 14.", null),
        Triple("SAMPLE", "Sample dialogue for future question 15.", null),
        Triple("SAMPLE", "Sample dialogue for future question 16.", null)
    )

    Scaffold(
        topBar = {
            // Top App Bar with "Back to Home" button
            androidx.compose.material3.TopAppBar(
                title = { Text("General Questions") },
                actions = {
                    // Button in the top-right corner
                    Button(
                        onClick = {
                            navController.navigate(Screen.Home.name)
                            viewModel.playSoundEffect(buttonSoundEffect_main)
                            viewModel.setIsExitingScreen(true)
                        },
                        modifier = Modifier
                            .padding(end = 16.dp) // Padding for spacing
                            .height(60.dp)
                            .width(120.dp)
                    ) {
                        Text("HOME", fontSize = 24.sp)
                    }
                }
            )
        }
    ) {
        BlackBackgroundBox()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally // Center items horizontally
        ) {
            // "Ask Question" Button
            Button(
                onClick = {
                    viewModel.playSoundEffect(buttonSoundEffect_main)
                    viewModel.askQuestionUi("Just be yourself, keep responses short. If the user says Tammy or Timmy they mean Temi, there is an issue with the text to speech system.")
                },
                modifier = Modifier
                    .fillMaxWidth(0.5f)
                    .padding(bottom = 16.dp, top = 70.dp) // Space below the button
                    .height(100.dp)
            ) {
                Text("Ask Question", fontSize = 48.sp)
            }

            // Scrollable List of Questions
            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier.fillMaxSize()
            ) {
                items(questionTypes) { question ->
                    // Each question as a text item
                    Text(
                        text = question.first,
                        fontSize = 50.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                            .wrapContentWidth(Alignment.CenterHorizontally) // Center horizontally
                            .clickable {
                                viewModel.playSoundEffect(buttonSoundEffect_secondary)
                                viewModel.speakForUi(question.second, true)
                                if (question.third != null) viewModel.setShowImageFlag(true)
                            },
                        color = Color.White // Text color
                    )
                    // Optional divider between items
                    Divider(color = androidx.compose.ui.graphics.Color.White, thickness = 1.dp)
                }
            }
        }
    }

    if (showImage) {
        // Display the image when showImage is true
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(onTap = {
                        // Hide image when tapped
                        viewModel.setShowImageFlag(false)
                    })
                }
        ) {
            Image(
                painter = painterResource(id = R.drawable.sample_image), // Replace with your image resource
                contentDescription = "Displayed Image",
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f) // Adjust as needed
            )
        }
    }

    if (showWaitSequence) {
        Gif(gif_thinking)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter", "UseOfNonLambdaOffsetOverload")
@Composable
fun DirectionsAndLocationsScreen(navController: NavController, viewModel: MainViewModel) {
    RefreshExit(viewModel, delay = 100)

    val showImage by viewModel.showImageFlag.collectAsState()

    var point by remember { mutableIntStateOf(1) }

    val position = when (point) {
        1 -> {
            val (x, y) = Pair(0.0f, 0.0f) // Coordinates for point 1
            Offset(x.toFloat(), y.toFloat())
        }

        2 -> {
            val (x, y) = Pair(0.0f, 0.0f) // Coordinates for point 1 // Coordinates for point 2
            Offset(x.toFloat(), y.toFloat())
        }

        3 -> {
            val (x, y) = Pair(0.0f, 0.0f) // Coordinates for point 1 // Coordinates for point 3
            Offset(x.toFloat(), y.toFloat())
        }

        4 -> {
            val (x, y) = Pair(0.0f, 0.0f) // Coordinates for point 1 // Coordinates for point 4
            Offset(x.toFloat(), y.toFloat())
        }

        else -> Offset(0f, 0f) // Default position
    }

    // -- Control UI elements --
    // Button
    val buttonWidthAndHeight = 400.dp
    val roundedCorners = 12.dp
    //Text
    val fontSize = 48.sp

    // MutableState to hold the current position
    val currentNewMethodPosition = remember {
        mutableStateOf(
            viewModel.dynamicCoordinateConvert(
                viewModel.getPosition().x,
                viewModel.getPosition().y
            )
        )
    }

    // Observe changes to the position from the viewModel
    LaunchedEffect(viewModel) {
        viewModel.positionFlow.collect { newPosition ->
            val mappedPositionTwo = viewModel.dynamicCoordinateConvert(
                newPosition.x,
                newPosition.y
            )

            currentNewMethodPosition.value = mappedPositionTwo

            delay(100)
        }
    }

    Scaffold(
        topBar = {
            // Top App Bar with "Back to Home" button
            androidx.compose.material3.TopAppBar(
                title = { Text("Directions/Locations") },
                actions = {
                    // Button in the top-right corner
                    Button(
                        onClick = {
                            navController.navigate(Screen.Home.name)
                            viewModel.playSoundEffect(buttonSoundEffect_main)
                            viewModel.setIsExitingScreen(true)
                        },
                        modifier = Modifier
                            .padding(end = 16.dp) // Padding for spacing
                            .height(60.dp)
                            .width(120.dp)
                    ) {
                        Text("HOME", fontSize = 24.sp)
                    }
                }
            )
        },

        content = {
            BlackBackgroundBox()

            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                // Create the two rows of buttons
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    // First row of buttons (test 1, test 2)
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Button(
                            onClick = {
                                viewModel.playSoundEffect(buttonSoundEffect_main)
                                // viewModel.goToPosition(1)
                                viewModel.setShowImageFlag(true)
                                point = 1
                                viewModel.queryLocation("test point 1")
                            },
                            modifier = Modifier
                                .width(buttonWidthAndHeight)
                                .height(buttonWidthAndHeight),
                            shape = RoundedCornerShape(roundedCorners) // Adjust corner radius
                        ) {
                            Text("Test 1", fontSize = fontSize)
                        }
                        Spacer(modifier = Modifier.width(16.dp)) // Add space between buttons
                        Button(
                            onClick = {
                                viewModel.playSoundEffect(buttonSoundEffect_main)
                                // viewModel.goToPosition(2)
                                viewModel.setShowImageFlag(true)
                                point = 2
                                viewModel.queryLocation("test point 2")
                            },
                            modifier = Modifier
                                .width(buttonWidthAndHeight)
                                .height(buttonWidthAndHeight),
                            shape = RoundedCornerShape(roundedCorners) // Adjust corner radius
                        ) {
                            Text("Test 2", fontSize = fontSize)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp)) // Add space between rows

                    // Second row of buttons (test 3, test 4)
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Button(
                            onClick = {
                                viewModel.playSoundEffect(buttonSoundEffect_main)// viewModel.goToPosition(3)
                                viewModel.setShowImageFlag(true)
                                point = 3
                                viewModel.queryLocation("test point 3")
                            },
                            modifier = Modifier
                                .width(buttonWidthAndHeight)
                                .height(buttonWidthAndHeight),
                            shape = RoundedCornerShape(roundedCorners) // Adjust corner radius
                        ) {
                            Text("Test 3", fontSize = fontSize)
                        }
                        Spacer(modifier = Modifier.width(16.dp)) // Add space between buttons
                        Button(
                            onClick = {
                                viewModel.playSoundEffect(buttonSoundEffect_main)// viewModel.goToPosition(4)
                                viewModel.setShowImageFlag(true)
                                point = 4
                                viewModel.queryLocation("test point 4")
                            },
                            modifier = Modifier
                                .width(buttonWidthAndHeight)
                                .height(buttonWidthAndHeight),
                            shape = RoundedCornerShape(roundedCorners) // Adjust corner radius
                        ) {
                            Text("Test 4", fontSize = fontSize)
                        }
                    }
                }
            }
        }
    )

    if (showImage) {//showImage
        // State to hold the scale and offset
        val scale = remember { mutableStateOf(1f) }
        val offsetX = remember { mutableStateOf(0f) }
        val offsetY = remember { mutableStateOf(0f) }

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale.value = (scale.value * zoom).coerceIn(0.5f, 5f) // Restrict zoom range
                        offsetX.value += pan.x
                        offsetY.value += pan.y
                    }
                }
        ) {
            @Composable
            fun CreatePath(
                dotSize: Int,
                spaceBetweenDots: Int,
                pathPoints: List<Pair<Int, Int>>, // List of points defining the path
                currentPosition: Pair<Int, Int>,
                scale: androidx.compose.runtime.State<Float>, // Pass scale as a parameter
                offsetX: androidx.compose.runtime.State<Float>, // Pass offsetX as a parameter
                offsetY: androidx.compose.runtime.State<Float> // Pass offsetY as a parameter
            ) {
                // List to hold point data: number, distance, coordinates (pair), and steps
                val pointData: MutableList<Triple<Int, Int, Pair<Int, Int>>> = mutableListOf()
                var numberOfPoints = 0
                var currentPoint: Pair<Int, Int>

                // Loop through all points except the last one
                for (i in 0 until pathPoints.size - 1) {
                    currentPoint = pathPoints[i]

                    fun stepRatio(
                        startPoint: Pair<Int, Int>,
                        finishPoint: Pair<Int, Int>,
                        spaceBetweenDots: Int
                    ): Pair<Int, Int> {
                        val xDifference = finishPoint.first - startPoint.first
                        val yDifference = finishPoint.second - startPoint.second

                        if (xDifference == 0 && yDifference == 0) {
                            return Pair(0, 0) // No movement needed
                        } else if (xDifference == 0) {
                            return Pair(0, spaceBetweenDots * if (yDifference > 0) 1 else -1)
                        } else if (yDifference == 0) {
                            return Pair(spaceBetweenDots * if (xDifference > 0) 1 else -1, 0)
                        }

                        val angleInRadians = atan2(yDifference.toFloat(), xDifference.toFloat())
                        val x = spaceBetweenDots * cos(angleInRadians)
                        val y = spaceBetweenDots * sin(angleInRadians)

                        return Pair(x.roundToInt(), y.roundToInt())
                    }

                    // Calculate the total length of the line
                    val totalDistance = sqrt(
                        ((pathPoints[i + 1].first - pathPoints[i].first).toDouble().pow(2)) +
                                ((pathPoints[i + 1].second - pathPoints[i].second).toDouble()
                                    .pow(2))
                    )

                    // Get step increments
                    val steps = stepRatio(pathPoints[i], pathPoints[i + 1], spaceBetweenDots)
                    var cumulativeDistance = 0.0

                    // Loop through and add points until total distance is covered
                    while (cumulativeDistance < totalDistance) {
                        val xDifference =
                            (currentPoint.first - currentPosition.first).toDouble()
                        val yDifference =
                            (currentPoint.second - currentPosition.second).toDouble()
                        val distance = sqrt(xDifference.pow(2) + yDifference.pow(2))
                        pointData.add(
                            Triple(
                                numberOfPoints++,
                                distance.roundToInt(),
                                Pair(currentPoint.first, currentPoint.second)
                            )
                        )

                        // Update current point
                        currentPoint = Pair(
                            currentPoint.first + steps.first,
                            currentPoint.second + steps.second
                        )

                        // Update cumulative distance
                        cumulativeDistance = sqrt(
                            ((currentPoint.first - pathPoints[i].first).toDouble().pow(2)) +
                                    ((currentPoint.second - pathPoints[i].second).toDouble()
                                        .pow(2))
                        )
                    }
                }

                // Find the index of the point with the smallest distance
                val minDistanceIndex =
                    pointData.indices.minByOrNull { pointData[it].second } ?: 0
                currentPoint = pathPoints[0]

                // Render the dots starting from the point with the smallest distance
                for (i in minDistanceIndex until numberOfPoints) {
                    val (x, y) = pointData[i].third // Destructure the Pair
                    Box(
                        modifier = Modifier
                            .size(dotSize.dp)
                            .graphicsLayer(
                                scaleX = scale.value,
                                scaleY = scale.value,
                                translationX = offsetX.value,
                                translationY = offsetY.value
                            )
                            .offset(x = x.dp, y = y.dp)
                            .background(Color.Red, shape = CircleShape)

                    )
                }
            }

//            // Render the main image with zoom and pan applied

            Image(
                painter = BitmapPainter(viewModel.renderedMap), // Replace with your image resource
                contentDescription = "Displayed Image",
                modifier = Modifier
                    .graphicsLayer(
                        scaleX = scale.value,
                        scaleY = scale.value,
                        translationX = offsetX.value,
                        translationY = offsetY.value
                    )
                    .aspectRatio(viewModel.mapScale) // Adjust as needed
            )

            CreatePath(
                dotSize = 8,
                spaceBetweenDots = 15,
                pathPoints = listOf(
                    Pair(125, 230),
                    Pair(115, 110),
                    Pair(70, 110),
                    Pair(60, -105)
                ),
                currentPosition = Pair(
                    currentNewMethodPosition.value.first.toInt(),
                    currentNewMethodPosition.value.second.toInt()
                ),
                scale = scale,
                offsetX = offsetX,
                offsetY = offsetY
            )

            // Pointer image remains fixed (not scaled or moved)
            Image(
                painter = painterResource(id = R.drawable.pointer), // Replace with your image resource
                contentDescription = "Pointer Image",
                modifier = Modifier
                    .size(viewModel.pointerSize.dp)
                    .graphicsLayer(
                        scaleX = scale.value,
                        scaleY = scale.value,
                        translationX = offsetX.value,
                        translationY = offsetY.value
                    )
                    .offset(
                        x = currentNewMethodPosition.value.first.dp,
                        y = currentNewMethodPosition.value.second.dp
                    )
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun ToursScreen(navController: NavController, viewModel: MainViewModel) {
    Scaffold(
        topBar = {
// Top App Bar with "Back to Home" button
            TopAppBar(
                title = { Text("Tours") },
                actions = {
                    // Button in the top-right corner
                    Button(
                        onClick = {
                            navController.navigate(Screen.Home.name)
                            viewModel.playSoundEffect(buttonSoundEffect_main)
                            viewModel.setIsExitingScreen(true)
                        },
                        modifier = Modifier
                            .padding(end = 16.dp) // Padding for spacing
                            .height(60.dp)
                            .width(120.dp)
                    ) {
                        Text("HOME", fontSize = 24.sp)
                    }
                }
            )
        }
    ) { paddingValues ->
// Use paddingValues to adjust for top app bar height if necessary
        BlackBackgroundBox()

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .padding(paddingValues) // Apply any padding based on Scaffold
                .fillMaxSize(), // Ensure it fills the screen
        ) {
            Button(
                onClick = {
                    viewModel.speakForUi(
                        "Currently no tour has been developed, feel free to browse my other options",
                        true
                    )
                },
                modifier = Modifier
                    .size(500.dp) // Make button square (500x500)
                    .padding(16.dp), // Add padding around the button if needed
                shape = RoundedCornerShape(10.dp), // Adjust corner radius
            ) {
                Text(
                    text = "Start Tour",
                    style = TextStyle(
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 75.sp // Large font size
                    )
                )
            }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun RBScreen(navController: NavController, viewModel: MainViewModel) {
    Scaffold(
        topBar = {
// Top App Bar with "Back to Home" button
            TopAppBar(
                title = { Text("Explanation") },
                actions = {
                    // Button in the top-right corner
                    Button(
                        onClick = {
                            navController.navigate(Screen.Home.name)
                            viewModel.playSoundEffect(buttonSoundEffect_main)
                            viewModel.setIsExitingScreen(true)
                        },
                        modifier = Modifier
                            .padding(end = 16.dp) // Padding for spacing
                            .height(60.dp)
                            .width(120.dp)
                    ) {
                        Text("HOME", fontSize = 24.sp)
                    }
                }
            )
        }
    ) { paddingValues ->
// Use paddingValues to adjust for top app bar height if necessary
        BlackBackgroundBox()

        var userInput by remember { mutableStateOf("") }
        var isAnswerCorrect by remember { mutableStateOf<Boolean?>(null) }
        var image by remember { mutableStateOf(R.drawable.sample_image) }

        LaunchedEffect(Unit) {
            viewModel.speakForUi("Hello, this is doing some other thing", false)
            image = R.drawable.sample_image
            delay(3000)

            viewModel.speakForUi("Hello, this is doing another other thing", false)
            image = R.drawable.pointer
            delay(3000)

            viewModel.speakForUi("Hello, this is doing other things", false)
            image = R.drawable.map_r407
            delay(3000)

            navController.navigate(Screen.Home.name)
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .padding(paddingValues) // Apply any padding based on Scaffold
                .fillMaxSize(), // Ensure it fills the screen
        ) {
            Image(
                painter = painterResource(id = image), // Replace with your image resource
                contentDescription = "Displayed Image",
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f) // Adjust as needed
            )

        }
    }
}

/*
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter", "UseOfNonLambdaOffsetOverload")
@Composable
fun DirectionsAndLocationsScreen(navController: NavController, viewModel: MainViewModel) {
RefreshExit(viewModel, delay = 100)

val showImage by viewModel.showImageFlag.collectAsState()

var point by remember { mutableIntStateOf(1) }

// MutableState to hold the current position
val currentPosition =
remember { mutableStateOf(Pair(viewModel.getPosition().x, viewModel.getPosition().y)) }

fun degreesToRadians(degrees: Float): Float {
return (degrees * (Math.PI).toFloat() / 180f).toFloat()
}

// These are reference points for doing conversion
// Find two point on the temi map and correlate them to the map you made
val realPoint1: Pair<Float, Float> = Pair(1.009653f, 0.078262f)
val realPoint2: Pair<Float, Float> = Pair(1.769055f, -5.273465f)
val mapPoint1: Pair<Float, Float> = Pair(-155f, 150f)
val mapPoint2: Pair<Float, Float> = Pair(-190f, -35f)
val axisAngleDifference = degreesToRadians(-2.5f)

fun convertCoordinates(
realX: Float,
realY: Float,
): Pair<Float, Float> {
fun adjustPointsToNewAxis(point: Pair<Float, Float>, radian: Float): Pair<Float, Float> {
val newX = point.first * cos(radian) + point.second * sin(radian)
val newY = point.first * sin(radian) + point.second * cos(radian)

return Pair(newX, newY)
}

val realPoint1Adjusted = adjustPointsToNewAxis(realPoint1, axisAngleDifference)
val realPoint2Adjusted = adjustPointsToNewAxis(realPoint2, axisAngleDifference)

// The idea is to set the first point as the origin
val secondPointReal =
Pair(
realPoint2Adjusted.first - realPoint1Adjusted.first,
realPoint2Adjusted.second - realPoint1Adjusted.second
)
val secondPointMap =
Pair(mapPoint2.first - mapPoint1.first, mapPoint2.second - mapPoint1.second)

val scaleX = secondPointMap.first / secondPointReal.first
val scaleY = secondPointMap.second / secondPointReal.second

Log.i(
"TESTING2",
"secondPointMap.first: $secondPointMap.first, secondPointReal.first: $secondPointReal.first"
)
Log.i("TESTING2", "scaleX: $scaleX, scaleY: $scaleY")
Log.i("TESTING2", "realX: $realX, realPoint1.first: ${realPoint1Adjusted.first}")
Log.i("TESTING2", "realY: $realY, realPoint1.second: ${realPoint1Adjusted.second}")

val offsetX = mapPoint1.first
val offsetY = mapPoint1.second

val mappedX = ((realX - realPoint1Adjusted.first) * scaleX + offsetX).toInt()
val mappedY = ((realY - realPoint1Adjusted.second) * scaleY + offsetY).toInt()

Log.i("TESTING", "x: $mappedX and y: $mappedY")
return Pair(mappedX.toFloat(), mappedY.toFloat())
}

val position = when (point) {
1 -> {
val (x, y) = convertCoordinates(1.009653f, 0.078262f) // Coordinates for point 1
Offset(x.toFloat(), y.toFloat())
}

2 -> {
val (x, y) = convertCoordinates(0.830383f, -7.916466f) // Coordinates for point 2
Offset(x.toFloat(), y.toFloat())
}

3 -> {
val (x, y) = convertCoordinates(1.769055f, -5.273465f) // Coordinates for point 3
Offset(x.toFloat(), y.toFloat())
}

4 -> {
val (x, y) = convertCoordinates(1.916642f, -2.222844f) // Coordinates for point 4
Offset(x.toFloat(), y.toFloat())
}

else -> Offset(0f, 0f) // Default position
}

// -- Control UI elements --
// Button
val buttonWidthAndHeight = 400.dp
val roundedCorners = 12.dp
//Text
val fontSize = 48.sp

//****
val mapScale = 1f
val pointerSize = 30

val realPointOne =
Pair(viewModel.getMapData()?.mapInfo?.originX, viewModel.getMapData()?.mapInfo?.originY)
val realPointTwo = Pair(
viewModel.getMapData()?.mapInfo?.originX?.plus(viewModel.getMapData()?.mapInfo?.width!!)
?: 0.0f,
viewModel.getMapData()?.mapInfo?.originY?.plus(viewModel.getMapData()?.mapInfo?.height!!)
?: 0.0f
)

val xOffset = (viewModel.renderedMap.width / 4)
val yOffset = (viewModel.renderedMap.height / 4)

val mapPointOne = Pair(xOffset, yOffset)
val mapPointTwo = Pair(-xOffset, -yOffset)

// Define the dynamic coordinate conversion
val dynamicCoordinateConvert: (Float, Float) -> Pair<Float, Float> = { a, b ->
viewModel.convertCoordinates(a, b, realPointOne, realPointTwo, mapPointOne, mapPointTwo)
}

// MutableState to hold the current position
val currentNewMethodPosition = remember {
mutableStateOf(
dynamicCoordinateConvert(
viewModel.getPosition().x,
viewModel.getPosition().y
)
)
}
//****

// Observe changes to the position from the viewModel
LaunchedEffect(viewModel) {
viewModel.positionFlow.collect { newPosition ->
//             Convert the real-world coordinates to mapped coordinates
val mappedPosition = convertCoordinates(
realX = newPosition.x,
realY = newPosition.y
)

val mappedPositionTwo = dynamicCoordinateConvert(
viewModel.getPosition().x,
viewModel.getPosition().y
)

currentPosition.value = mappedPosition
currentNewMethodPosition.value = mappedPositionTwo
}
}

Scaffold(
topBar = {
// Top App Bar with "Back to Home" button
androidx.compose.material3.TopAppBar(
title = { Text("Directions/Locations") },
actions = {
    // Button in the top-right corner
    Button(
        onClick = {
            navController.navigate(Screen.Home.name)
            viewModel.playSoundEffect(buttonSoundEffect_main)
            viewModel.setIsExitingScreen(true)
        },
        modifier = Modifier
            .padding(end = 16.dp) // Padding for spacing
            .height(60.dp)
            .width(120.dp)
    ) {
        Text("HOME", fontSize = 24.sp)
    }
}
)
},
content = {
Box(
modifier = Modifier.fillMaxSize(),
contentAlignment = Alignment.Center
) {
// Create the two rows of buttons
Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
    modifier = Modifier.fillMaxSize()
) {
    // First row of buttons (test 1, test 2)
    Row(
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth()
    ) {
        Button(
            onClick = {
                viewModel.playSoundEffect(buttonSoundEffect_main)
                // viewModel.goToPosition(1)
                viewModel.setShowImageFlag(true)
                point = 1
                viewModel.queryLocation("test point 1")
            },
            modifier = Modifier
                .width(buttonWidthAndHeight)
                .height(buttonWidthAndHeight),
            shape = RoundedCornerShape(roundedCorners) // Adjust corner radius
        ) {
            Text("Test 1", fontSize = fontSize)
        }
        Spacer(modifier = Modifier.width(16.dp)) // Add space between buttons
        Button(
            onClick = {
                viewModel.playSoundEffect(buttonSoundEffect_main)
                // viewModel.goToPosition(2)
                viewModel.setShowImageFlag(true)
                point = 2
                viewModel.queryLocation("test point 2")
            },
            modifier = Modifier
                .width(buttonWidthAndHeight)
                .height(buttonWidthAndHeight),
            shape = RoundedCornerShape(roundedCorners) // Adjust corner radius
        ) {
            Text("Test 2", fontSize = fontSize)
        }
    }

    Spacer(modifier = Modifier.height(16.dp)) // Add space between rows

    // Second row of buttons (test 3, test 4)
    Row(
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth()
    ) {
        Button(
            onClick = {
                viewModel.playSoundEffect(buttonSoundEffect_main)// viewModel.goToPosition(3)
                viewModel.setShowImageFlag(true)
                point = 3
                viewModel.queryLocation("test point 3")
            },
            modifier = Modifier
                .width(buttonWidthAndHeight)
                .height(buttonWidthAndHeight),
            shape = RoundedCornerShape(roundedCorners) // Adjust corner radius
        ) {
            Text("Test 3", fontSize = fontSize)
        }
        Spacer(modifier = Modifier.width(16.dp)) // Add space between buttons
        Button(
            onClick = {
                viewModel.playSoundEffect(buttonSoundEffect_main)// viewModel.goToPosition(4)
                viewModel.setShowImageFlag(true)
                point = 4
                viewModel.queryLocation("test point 4")
            },
            modifier = Modifier
                .width(buttonWidthAndHeight)
                .height(buttonWidthAndHeight),
            shape = RoundedCornerShape(roundedCorners) // Adjust corner radius
        ) {
            Text("Test 4", fontSize = fontSize)
        }
    }
}
}
}
)

if (showImage) {
// Display the image when showImage is true
Box(
contentAlignment = Alignment.Center,
modifier = Modifier
.fillMaxSize()
.background(Color.Black) // Set the background to white
.pointerInput(Unit) {
    detectTapGestures(onTap = {
        // Hide image when tapped
        if (!viewModel.isSpeaking.value) {
            viewModel.setShowImageFlag(false)
        }
    })
}
) {
if (false) {
Image(
    painter = painterResource(id = R.drawable.map_r407), // Replace with your image resource
    contentDescription = "Displayed Image",
    modifier = Modifier
        .aspectRatio(2f) // Adjust as needed
)

Box(
    modifier = Modifier
        .size(30.dp)
        .offset(
            x = position.x.dp,
            y = position.y.dp
        ) // Position determined by `point`
        .background(Color.Blue, shape = CircleShape)
)

@Composable
fun CreatePath(
    dotSize: Int,
    spaceBetweenDots: Int,
    pathPoints: List<Pair<Int, Int>>, // List of points defining the path
    currentPosition: Pair<Int, Int>
) {
    // List to hold point data: number, distance, coordinates (pair), and steps
    val pointData: MutableList<Triple<Int, Int, Pair<Int, Int>>> = mutableListOf()
    var numberOfPoints = 0
    var currentPoint: Pair<Int, Int>

    // Loop through all points except the last one
    for (i in 0 until pathPoints.size - 1) {
        currentPoint = pathPoints[i]

        fun stepRatio(
            startPoint: Pair<Int, Int>,
            finishPoint: Pair<Int, Int>,
            spaceBetweenDots: Int
        ): Pair<Int, Int> {
            val xDifference = finishPoint.first - startPoint.first
            val yDifference = finishPoint.second - startPoint.second

            if (xDifference == 0 && yDifference == 0) {
                return Pair(0, 0) // No movement needed
            } else if (xDifference == 0) {
                return Pair(0, spaceBetweenDots * if (yDifference > 0) 1 else -1)
            } else if (yDifference == 0) {
                return Pair(spaceBetweenDots * if (xDifference > 0) 1 else -1, 0)
            }

            val angleInRadians = atan2(yDifference.toFloat(), xDifference.toFloat())
            val x = spaceBetweenDots * cos(angleInRadians)
            val y = spaceBetweenDots * sin(angleInRadians)

            return Pair(x.roundToInt(), y.roundToInt())
        }

        // Calculate the total length of the line
        val totalDistance = sqrt(
            ((pathPoints[i + 1].first - pathPoints[i].first).toDouble().pow(2)) +
                    ((pathPoints[i + 1].second - pathPoints[i].second).toDouble()
                        .pow(2))
        )

        // Get step increments
        val steps = stepRatio(pathPoints[i], pathPoints[i + 1], spaceBetweenDots)
        var cumulativeDistance = 0.0

        // Loop through and add points until total distance is covered
        while (cumulativeDistance < totalDistance) {
            val xDifference =
                (currentPoint.first - currentPosition.first).toDouble()
            val yDifference =
                (currentPoint.second - currentPosition.second).toDouble()
            val distance = sqrt(xDifference.pow(2) + yDifference.pow(2))
            pointData.add(
                Triple(
                    numberOfPoints++,
                    distance.roundToInt(),
                    Pair(currentPoint.first, currentPoint.second)
                )
            )

            // Update current point
            currentPoint = Pair(
                currentPoint.first + steps.first,
                currentPoint.second + steps.second
            )

            // Update cumulative distance
            cumulativeDistance = sqrt(
                ((currentPoint.first - pathPoints[i].first).toDouble().pow(2)) +
                        ((currentPoint.second - pathPoints[i].second).toDouble()
                            .pow(2))
            )
        }
    }

    // Find the index of the point with the smallest distance
    val minDistanceIndex =
        pointData.indices.minByOrNull { pointData[it].second } ?: 0
    currentPoint = pathPoints[0]

    // Render the dots starting from the point with the smallest distance
    for (i in minDistanceIndex until numberOfPoints) {
        val (x, y) = pointData[i].third // Destructure the Pair
        Box(
            modifier = Modifier
                .size(dotSize.dp)
                .offset(x = x.dp, y = y.dp)
                .background(Color.Red, shape = CircleShape)
        )
    }
}

CreatePath(
    10,
    20,
    listOf(Pair(-35, 165), Pair(-35, -205), Pair(-170, -205), Pair(-270, -105)),
    Pair(currentPosition.value.first.toInt(), currentPosition.value.second.toInt())
)

// UI displaying the image
Image(
    painter = painterResource(id = R.drawable.pointer), // Replace with your image resource
    contentDescription = "Displayed Image",
    modifier = Modifier
        .size(30.dp)
        .offset(
            x = currentPosition.value.first.dp,
            y = currentPosition.value.second.dp
        ) // Position updates in real-time
)
} else {

/*
                Log.i(
    "TESTING7",
    "realPointOne: $realPointOne"
)

Log.i(
    "TESTING7",
    "realPointTwo: $realPointTwo"
)

Log.i(
    "TESTING7",
    "mapPointOne: $mapPointOne"
)

Log.i(
    "TESTING7",
    "mapPointOne: $mapPointTwo"
)

Log.i(
    "TESTING7",
    "mapPointOne: $mapPointTwo"
)
 */

Image(
    painter = BitmapPainter(viewModel.renderedMap), // Replace with your image resource
    contentDescription = "Displayed Image",
    modifier = Modifier
        .aspectRatio(mapScale) // Adjust as needed
)

Log.i(
    "TESTING7",
    "Y: ${viewModel.getMapData()?.locations?.get(1)?.layerPoses}"
)


// Update the position dynamically
currentNewMethodPosition.value = dynamicCoordinateConvert(
    viewModel.getPosition().x,
    viewModel.getPosition().y
)

Log.i(
    "TESTING7",
    "Location: ${
        Pair(
            currentNewMethodPosition.value.first.dp,
            currentNewMethodPosition.value.second.dp
        )
    }"
)

Log.i(
    "TESTING7",
    "LocationReal: ${currentPosition}"
)

Image(
    painter = painterResource(id = R.drawable.pointer), // Replace with your image resource
    contentDescription = "Displayed Image",
    modifier = Modifier
        .size(pointerSize.dp)
        .offset(
            x = currentNewMethodPosition.value.first.dp,
            y = currentNewMethodPosition.value.second.dp
//                            x = (xOffset).dp,
//                            y = (yOffset - pointerSize / 2).dp
        ) // Position updates in real-time
)
}

}
}

}
*/