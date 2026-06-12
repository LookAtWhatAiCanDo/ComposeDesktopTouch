package com.whataicando.touch.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.defaultScrollbarStyle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.WinDef.HWND
import kotlinx.coroutines.delay
import com.whataicando.touch.compose.WindowsTouch
import com.whataicando.touch.compose.touchScrollable
import com.whataicando.touch.compose.touchScrim
import com.whataicando.touch.compose.TouchInstallation
import com.whataicando.touch.win32.TouchEvent
import com.whataicando.touch.win32.Win32TouchRegistry

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Compose Desktop Touch Demo"
    ) {
        val windowInstance = window
        var isInstalled by remember { mutableStateOf(false) }
        var hookEnabled by remember { mutableStateOf(true) }
        var showOverlay by remember { mutableStateOf(false) }
        
        var lazyColumnHookEnabled by remember { mutableStateOf(true) }
        var regularColumnHookEnabled by remember { mutableStateOf(true) }
        var overlayScrollHookEnabled by remember { mutableStateOf(true) }
        var scrimEnabled by remember { mutableStateOf(true) }
        
        var touchEvents by remember { mutableStateOf(emptyList<TouchEvent>()) }
        var hookInstallation by remember { mutableStateOf<TouchInstallation?>(null) }
        var eventListBounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }

        val activeEventListPointers = remember { mutableSetOf<Int>() }
        val logListener: (TouchEvent) -> Unit = remember {
            { event ->
                val bounds = eventListBounds
                if (event.phase == com.whataicando.touch.win32.TouchPhase.DOWN) {
                    val isInside = bounds?.contains(androidx.compose.ui.geometry.Offset(event.xPx, event.yPx)) == true
                    if (isInside) {
                        activeEventListPointers.add(event.id)
                    }
                }
                
                val shouldIgnore = activeEventListPointers.contains(event.id)
                
                if (event.phase == com.whataicando.touch.win32.TouchPhase.UP || 
                    event.phase == com.whataicando.touch.win32.TouchPhase.CANCEL) {
                    activeEventListPointers.remove(event.id)
                }
                
                if (!shouldIgnore) {
                    // Allow the events list to grow very long (capped at 5000 to prevent memory exhaustion)
                    val current = touchEvents
                    val next = if (current.size >= 5000) {
                        current.drop(1) + event
                    } else {
                        current + event
                    }
                    touchEvents = next
                }
            }
        }
        
        // Dynamically manage touch support based on checkbox state
        LaunchedEffect(windowInstance, hookEnabled) {
            if (hookEnabled) {
                while (!windowInstance.isDisplayable) {
                    delay(10)
                }
                val installation = WindowsTouch.install(windowInstance)
                hookInstallation = installation
                isInstalled = true
                
                // Register log listener
                val hwndLong = com.sun.jna.Native.getWindowID(windowInstance)
                val hWnd = HWND(Pointer(hwndLong))
                Win32TouchRegistry.registerListener(hWnd, logListener)
            } else {
                val hwndLong = com.sun.jna.Native.getWindowID(windowInstance)
                val hWnd = HWND(Pointer(hwndLong))
                Win32TouchRegistry.unregisterListener(hWnd, logListener)
                
                hookInstallation?.uninstall()
                hookInstallation = null
                isInstalled = false
            }
        }

        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = Color(0xFF00E5FF),
                background = Color(0xFF0B0D13),
                surface = Color(0xFF151922)
            )
        ) {
            CompositionLocalProvider(
                LocalScrollbarStyle provides defaultScrollbarStyle().copy(
                    shape = RoundedCornerShape(4.dp),
                    unhoverColor = Color.White.copy(alpha = 0.3f),
                    hoverColor = Color(0xFF00E5FF).copy(alpha = 0.8f)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize().background(Color(0xFF0B0D13)),
                    color = Color(0xFF0B0D13)
                ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF0B0D13))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Control Panel Header Card
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, Color(0xFF2C3549), RoundedCornerShape(12.dp)),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF151922))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Checkbox(
                                        checked = hookEnabled,
                                        onCheckedChange = { hookEnabled = it },
                                        colors = CheckboxDefaults.colors(
                                            checkedColor = Color(0xFF00E5FF),
                                            uncheckedColor = Color(0xFF94A3B8),
                                            checkmarkColor = Color.Black
                                        )
                                    )
                                    Column {
                                        Text(
                                            text = "Native WndProc Touch Hook",
                                            color = Color.White,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "Subclasses AWT canvas for direct Win32 WM_POINTER event interception",
                                            color = Color(0xFF94A3B8),
                                            fontSize = 11.sp
                                        )
                                    }
                                }

                                Button(
                                    onClick = { showOverlay = true },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF00E5FF),
                                        contentColor = Color.Black
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.height(40.dp)
                                ) {
                                    Text("Open Overlay Dialog", fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }

                        // Main Content Columns
                        Row(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            // Left column: Scrollable container (LazyColumn)
                            Column(
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "LazyColumn",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color(0xFF94A3B8)
                                    )
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            text = "Scroll Hook",
                                            fontSize = 12.sp,
                                            color = if (lazyColumnHookEnabled) Color(0xFF00E5FF) else Color(0xFF94A3B8)
                                        )
                                        Checkbox(
                                            checked = lazyColumnHookEnabled,
                                            onCheckedChange = { lazyColumnHookEnabled = it },
                                            colors = CheckboxDefaults.colors(
                                                checkedColor = Color(0xFF00E5FF),
                                                uncheckedColor = Color(0xFF94A3B8),
                                                checkmarkColor = Color.Black
                                            )
                                        )
                                    }
                                }
                                val lazyListState = rememberLazyListState()
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                        .background(Color(0xFF151922), RoundedCornerShape(12.dp))
                                        .border(1.dp, Color(0xFF2C3549), RoundedCornerShape(12.dp))
                                ) {
                                    LazyColumn(
                                        state = lazyListState,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .then(if (lazyColumnHookEnabled) Modifier.touchScrollable(lazyListState) else Modifier)
                                            .padding(end = 16.dp, start = 10.dp, top = 10.dp, bottom = 10.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        items(200) { index ->
                                            Card(
                                                modifier = Modifier.fillMaxWidth().height(48.dp),
                                                shape = RoundedCornerShape(8.dp),
                                                colors = CardDefaults.cardColors(containerColor = Color(0xFF1F2633))
                                            ) {
                                                Box(
                                                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                                                    contentAlignment = Alignment.CenterStart
                                                ) {
                                                    Text("Item #$index", color = Color.White, fontSize = 14.sp)
                                                }
                                            }
                                        }
                                    }
                                    VerticalScrollbar(
                                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 6.dp, horizontal = 4.dp),
                                        adapter = rememberScrollbarAdapter(scrollState = lazyListState)
                                    )
                                }
                            }

                            // Middle column: Scrollable container (Column with verticalScroll)
                            Column(
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Regular Column",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color(0xFF94A3B8)
                                    )
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            text = "Scroll Hook",
                                            fontSize = 12.sp,
                                            color = if (regularColumnHookEnabled) Color(0xFF00E5FF) else Color(0xFF94A3B8)
                                        )
                                        Checkbox(
                                            checked = regularColumnHookEnabled,
                                            onCheckedChange = { regularColumnHookEnabled = it },
                                            colors = CheckboxDefaults.colors(
                                                checkedColor = Color(0xFF00E5FF),
                                                uncheckedColor = Color(0xFF94A3B8),
                                                checkmarkColor = Color.Black
                                            )
                                        )
                                    }
                                }
                                val scrollState = rememberScrollState()
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                        .background(Color(0xFF151922), RoundedCornerShape(12.dp))
                                        .border(1.dp, Color(0xFF2C3549), RoundedCornerShape(12.dp))
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .then(if (regularColumnHookEnabled) Modifier.touchScrollable(scrollState) else Modifier)
                                            .verticalScroll(scrollState)
                                            .padding(end = 16.dp, start = 10.dp, top = 10.dp, bottom = 10.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        for (index in 0 until 50) {
                                            Card(
                                                modifier = Modifier.fillMaxWidth().height(60.dp),
                                                shape = RoundedCornerShape(8.dp),
                                                colors = CardDefaults.cardColors(containerColor = Color(0xFF1F2633))
                                            ) {
                                                Box(
                                                    modifier = Modifier.fillMaxSize(),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text("Scrollable Block #$index", color = Color.White, fontSize = 14.sp)
                                                }
                                            }
                                        }
                                    }
                                    VerticalScrollbar(
                                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 6.dp, horizontal = 4.dp),
                                        adapter = rememberScrollbarAdapter(scrollState = scrollState)
                                    )
                                }
                            }

                            // Right column: Live event logs
                            Column(
                                modifier = Modifier.width(320.dp).fillMaxHeight(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Live WM_POINTER Events",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color(0xFF00E5FF)
                                    )
                                    Text(
                                        text = "Clear",
                                        color = Color(0xFF00E5FF),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.clickable { touchEvents = emptyList() }
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                        .background(Color(0xFF0B0D13), RoundedCornerShape(12.dp))
                                        .border(1.dp, Color(0xFF2C3549), RoundedCornerShape(12.dp))
                                        .padding(12.dp)
                                ) {
                                    Column(
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier.fillMaxSize()
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(8.dp)
                                                    .background(
                                                        if (isInstalled) Color(0xFF10B981) else Color(0xFFEF4444),
                                                        RoundedCornerShape(4.dp)
                                                    )
                                            )
                                            Text(
                                                text = if (isInstalled) "Interceptor: ACTIVE" else "Interceptor: INACTIVE",
                                                color = if (isInstalled) Color(0xFF10B981) else Color(0xFFEF4444),
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                        HorizontalDivider(
                                            color = Color(0xFF2C3549),
                                            modifier = Modifier.padding(vertical = 4.dp)
                                        )
                                        
                                        val eventsListState = rememberLazyListState()
                                        Box(
                                            modifier = Modifier.weight(1f).fillMaxWidth()
                                        ) {
                                            LazyColumn(
                                                state = eventsListState,
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .touchScrollable(eventsListState)
                                                    .onGloballyPositioned { coordinates ->
                                                        eventListBounds = coordinates.boundsInWindow()
                                                    }
                                                    .padding(end = 12.dp),
                                                verticalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                items(touchEvents.reversed()) { event ->
                                                    Text(
                                                        text = "ID: ${event.id} | ${event.phase} | (${event.xPx.toInt()}, ${event.yPx.toInt()})",
                                                        color = when(event.phase) {
                                                            com.whataicando.touch.win32.TouchPhase.DOWN -> Color(0xFF10B981)
                                                            com.whataicando.touch.win32.TouchPhase.UP -> Color(0xFFEF4444)
                                                            com.whataicando.touch.win32.TouchPhase.CANCEL -> Color(0xFFFBBF24)
                                                            else -> Color(0xFF94A3B8)
                                                        },
                                                        fontSize = 11.sp,
                                                        fontFamily = FontFamily.Monospace
                                                    )
                                                }
                                            }
                                            VerticalScrollbar(
                                                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                                                adapter = rememberScrollbarAdapter(scrollState = eventsListState)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Foreground Dialog Overlay with touchScrim test
                    if (showOverlay) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .then(if (scrimEnabled) Modifier.touchScrim(priority = 1) else Modifier)
                                .background(Color.Black.copy(alpha = 0.7f))
                                .clickable { showOverlay = false },
                            contentAlignment = Alignment.Center
                        ) {
                            Card(
                                modifier = Modifier
                                    .width(480.dp)
                                    .padding(16.dp)
                                    .touchScrim(priority = 1)
                                    .border(1.dp, Color(0xFF2C3549), RoundedCornerShape(16.dp)),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF151922))
                            ) {
                                Column(
                                    modifier = Modifier
                                        .padding(24.dp)
                                        .clickable(enabled = false) {}, // Prevent overlay click-out through the card
                                    verticalArrangement = Arrangement.spacedBy(16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = "Overlay Dialog Demo",
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                    Text(
                                        text = "With Modifier.touchScrim() applied to the overlay root, dragging here does NOT scroll the lists in the background. Clicks and drags are promoted to mouse events natively.",
                                        fontSize = 13.sp,
                                        color = Color(0xFF94A3B8),
                                        textAlign = TextAlign.Center
                                    )
                                    
                                    // Checkbox to toggle the touchScrim itself!
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(Color(0xFF1F2633), RoundedCornerShape(8.dp))
                                            .padding(horizontal = 16.dp, vertical = 10.dp)
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "Enable Overlay Scrim Hook",
                                                color = Color.White,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Text(
                                                text = "If disabled, touch drags on the card will scroll background panels.",
                                                color = Color(0xFF94A3B8),
                                                fontSize = 11.sp
                                            )
                                        }
                                        Checkbox(
                                            checked = scrimEnabled,
                                            onCheckedChange = { scrimEnabled = it },
                                            colors = CheckboxDefaults.colors(
                                                checkedColor = Color(0xFF00E5FF),
                                                uncheckedColor = Color(0xFF94A3B8),
                                                checkmarkColor = Color.Black
                                            )
                                        )
                                    }

                                    // Scrollable content inside the overlay dialog!
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "Scrollable Overlay Content",
                                                color = Color.White,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                Text(
                                                    text = "Scroll Hook",
                                                    fontSize = 12.sp,
                                                    color = if (overlayScrollHookEnabled) Color(0xFF00E5FF) else Color(0xFF94A3B8)
                                                )
                                                Checkbox(
                                                    checked = overlayScrollHookEnabled,
                                                    onCheckedChange = { overlayScrollHookEnabled = it },
                                                    colors = CheckboxDefaults.colors(
                                                        checkedColor = Color(0xFF00E5FF),
                                                        uncheckedColor = Color(0xFF94A3B8),
                                                        checkmarkColor = Color.Black
                                                    )
                                                )
                                            }
                                        }

                                        val overlayListState = rememberLazyListState()
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(140.dp)
                                                .background(Color(0xFF0B0D13), RoundedCornerShape(8.dp))
                                                .border(1.dp, Color(0xFF2C3549), RoundedCornerShape(8.dp))
                                        ) {
                                            LazyColumn(
                                                state = overlayListState,
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .then(if (overlayScrollHookEnabled) Modifier.touchScrollable(overlayListState, priority = 1) else Modifier)
                                                    .padding(end = 16.dp, start = 6.dp, top = 6.dp, bottom = 6.dp),
                                                verticalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                items(30) { index ->
                                                    Card(
                                                        modifier = Modifier.fillMaxWidth().height(36.dp),
                                                        shape = RoundedCornerShape(6.dp),
                                                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1F2633))
                                                    ) {
                                                        Box(
                                                            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                                                            contentAlignment = Alignment.CenterStart
                                                        ) {
                                                            Text("Overlay Item #$index", color = Color.White, fontSize = 12.sp)
                                                        }
                                                    }
                                                }
                                            }
                                            VerticalScrollbar(
                                                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 4.dp, horizontal = 2.dp),
                                                adapter = rememberScrollbarAdapter(scrollState = overlayListState)
                                            )
                                        }
                                    }

                                    Button(
                                        onClick = { showOverlay = false },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = Color(0xFF00E5FF),
                                            contentColor = Color.Black
                                        ),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.fillMaxWidth().height(42.dp)
                                    ) {
                                        Text("Close Overlay", fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            }
        }
    }
}
