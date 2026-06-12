package llc.lookatwhataicando.touchdemo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.flow.collect
import androidx.compose.runtime.LaunchedEffect
import llc.lookatwhataicando.composedesktoptouch.compose.WindowsTouch
import llc.lookatwhataicando.composedesktoptouch.compose.touchScrollable
import llc.lookatwhataicando.composedesktoptouch.win32.TouchEvent

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "compose-desktop-touch demo") {
        var installationActive by remember { mutableStateOf(false) }
        val eventLog = remember { mutableStateListOf<String>() }

        DisposableEffect(Unit) {
            val installation = WindowsTouch.install(window)
            installationActive = installation.isActive
            onDispose { installation.uninstall() }
        }
        LaunchedEffect(Unit) {
            WindowsTouch.install(window).rawEvents.collect { event ->
                println("raw: $event")
                eventLog.add(event.brief())
                if (eventLog.size > 200) eventLog.removeRange(0, eventLog.size - 200)
            }
        }

        MaterialTheme(colorScheme = darkColorScheme()) {
            Surface(modifier = Modifier.fillMaxSize()) {
                Row(modifier = Modifier.fillMaxSize()) {
                    // Pane 1: LazyColumn of 200 items
                    val listState = rememberLazyListState()
                    var lastClicked by remember { mutableStateOf("none") }
                    Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        Text(
                            "LazyColumn — touch active: $installationActive, last click: $lastClicked",
                            modifier = Modifier.padding(8.dp),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .touchScrollable(listState),
                        ) {
                            items(200) { index ->
                                Text(
                                    "Item $index",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { lastClicked = "Item $index" }
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                )
                            }
                        }
                    }

                    // Pane 2: plain Column with verticalScroll
                    val scrollState = rememberScrollState()
                    Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        Text(
                            "Column(verticalScroll)",
                            modifier = Modifier.padding(8.dp),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(scrollState)
                                .touchScrollable(scrollState),
                        ) {
                            repeat(200) { index ->
                                Text(
                                    "Row $index",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                )
                            }
                        }
                    }

                    // Pane 3: raw event log (deliberately NOT touchScrollable)
                    val logState = rememberLazyListState()
                    LaunchedEffect(eventLog.size) {
                        if (eventLog.isNotEmpty()) logState.scrollToItem(eventLog.size - 1)
                    }
                    Column(
                        modifier = Modifier
                            .weight(1.2f)
                            .fillMaxHeight()
                            .background(Color(0xFF101418)),
                    ) {
                        Text(
                            "Raw TouchEvents (${eventLog.size})",
                            modifier = Modifier.padding(8.dp),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        LazyColumn(state = logState, modifier = Modifier.fillMaxSize()) {
                            items(eventLog.size) { index ->
                                Text(
                                    eventLog[index],
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF80CBC4),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 1.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun TouchEvent.brief(): String =
    "#$pointerId ${phase.name.padEnd(6)} client=(${xPx.toInt()}, ${yPx.toInt()}) t=$timeMs"
