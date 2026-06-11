package llc.lookatwhataicando.touch.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.WinDef.HWND
import kotlinx.coroutines.delay
import llc.lookatwhataicando.touch.compose.WindowsTouch
import llc.lookatwhataicando.touch.compose.touchScrollable
import llc.lookatwhataicando.touch.win32.TouchEvent
import llc.lookatwhataicando.touch.win32.Win32TouchRegistry

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Compose Desktop Touch Demo"
    ) {
        val windowInstance = window
        var isInstalled by remember { mutableStateOf(false) }
        val touchEvents = remember { mutableStateListOf<TouchEvent>() }
        
        // Install touch support
        LaunchedEffect(windowInstance) {
            // Wait until AWT window has a peer
            while (!windowInstance.isDisplayable) {
                delay(10)
            }
            WindowsTouch.install(windowInstance)
            isInstalled = true
            
            // Listen to raw events to display in the log
            val hwndLong = com.sun.jna.Native.getWindowID(windowInstance)
            val hWnd = HWND(Pointer(hwndLong))
            Win32TouchRegistry.registerListener(hWnd) { event ->
                println("TouchEvent: $event")
                // Keep last 15 events
                if (touchEvents.size >= 15) {
                    touchEvents.removeAt(0)
                }
                touchEvents.add(event)
            }
        }

        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = Color(0xFF00E5FF),
                background = Color(0xFF121212),
                surface = Color(0xFF1E1E1E)
            )
        ) {
            Surface(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Left column: Scrollable container (LazyColumn)
                    Column(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("LazyColumn (touchScrollable)", fontSize = 16.sp, color = Color.White)
                        val lazyListState = rememberLazyListState()
                        LazyColumn(
                            state = lazyListState,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .border(1.dp, Color.Gray, RoundedCornerShape(8.dp))
                                .background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp))
                                .touchScrollable(lazyListState)
                                .padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(200) { index ->
                                Card(
                                    modifier = Modifier.fillMaxWidth().height(48.dp),
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2E2E2E))
                                ) {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.CenterStart
                                    ) {
                                        Text("  Item #$index", color = Color.White)
                                    }
                                }
                            }
                        }
                    }

                    // Middle column: Scrollable container (Column with verticalScroll)
                    Column(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Regular Column (touchScrollable)", fontSize = 16.sp, color = Color.White)
                        val scrollState = rememberScrollState()
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .border(1.dp, Color.Gray, RoundedCornerShape(8.dp))
                                .background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp))
                                .touchScrollable(scrollState)
                                .verticalScroll(scrollState)
                                .padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            for (index in 0 until 50) {
                                Card(
                                    modifier = Modifier.fillMaxWidth().height(60.dp),
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF333333))
                                ) {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("Scrollable Block #$index", color = Color.White)
                                    }
                                }
                            }
                        }
                    }

                    // Right column: Live event logs
                    Column(
                        modifier = Modifier.width(300.dp).fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Live WM_POINTER Events", fontSize = 16.sp, color = Color(0xFF00E5FF))
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .border(1.dp, Color(0xFF00E5FF), RoundedCornerShape(8.dp))
                                .background(Color(0xFF0A0A0A), RoundedCornerShape(8.dp))
                                .padding(8.dp)
                        ) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text("Touch Installed: $isInstalled", color = Color.Green, fontSize = 12.sp)
                                HorizontalDivider(color = Color.DarkGray, modifier = Modifier.padding(vertical = 4.dp))
                                
                                touchEvents.reversed().forEach { event ->
                                    Text(
                                        text = "ID: ${event.id} | ${event.phase} | (${event.xPx.toInt()}, ${event.yPx.toInt()})",
                                        color = when(event.phase) {
                                            llc.lookatwhataicando.touch.win32.TouchPhase.DOWN -> Color.Green
                                            llc.lookatwhataicando.touch.win32.TouchPhase.UP -> Color.Red
                                            llc.lookatwhataicando.touch.win32.TouchPhase.CANCEL -> Color.Yellow
                                            else -> Color.LightGray
                                        },
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
