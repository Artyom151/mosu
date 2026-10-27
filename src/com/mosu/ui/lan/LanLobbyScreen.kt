package com.mosu.ui.lan

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mosu.lan.LanLobbyState
import com.mosu.lan.LanRoomAd

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LanLobbyScreen(
    state: LanLobbyState,
    onClose: () -> Unit,
    onCreate: () -> Unit,
    onJoin: (LanRoomAd, password: String?) -> Unit,
    onJoinAddress: () -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    fun hideKeyboard() {
        keyboard?.hide()
        focusManager.clearFocus()
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize()) {

            Column(Modifier.fillMaxSize()) {

                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                    Column(Modifier.weight(1f)) {
                        Text("LAN multiplayer", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Play with people on the same Wi-Fi or on your hotspot",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {

                    // Left: identity and room creation.
                    Column(
                        Modifier.width(360.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedTextField(
                            value = state.username,
                            onValueChange = { state.username = it.take(32) },
                            label = { Text("Your name") },
                            leadingIcon = { Icon(Icons.Rounded.Person, contentDescription = null) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { hideKeyboard() }),
                            modifier = Modifier.fillMaxWidth(),
                        )

                        ElevatedCard(shape = MaterialTheme.shapes.extraLarge) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("Host a room", style = MaterialTheme.typography.titleMedium)

                                OutlinedTextField(
                                    value = state.roomName,
                                    onValueChange = { state.roomName = it.take(64) },
                                    label = { Text("Room name") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                                    modifier = Modifier.fillMaxWidth(),
                                )

                                OutlinedTextField(
                                    value = state.roomPassword,
                                    onValueChange = { state.roomPassword = it.take(32) },
                                    label = { Text("Password (optional)") },
                                    singleLine = true,
                                    visualTransformation = PasswordVisualTransformation(),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(onDone = { hideKeyboard() }),
                                    modifier = Modifier.fillMaxWidth(),
                                )

                                Text("Players", style = MaterialTheme.typography.labelLarge)

                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    for (count in listOf(2, 4, 8, 16)) {
                                        FilterChip(
                                            selected = state.maxPlayers == count,
                                            onClick = { state.maxPlayers = count },
                                            label = { Text("$count") },
                                        )
                                    }
                                }

                                Button(onClick = { hideKeyboard(); onCreate() }, modifier = Modifier.fillMaxWidth()) {
                                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Text("Create room", modifier = Modifier.padding(start = 8.dp))
                                }
                            }
                        }

                        Card(shape = MaterialTheme.shapes.extraLarge) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("Join by address", style = MaterialTheme.typography.titleMedium)

                                OutlinedTextField(
                                    value = state.manualAddress,
                                    onValueChange = { state.manualAddress = it.take(64) },
                                    label = { Text("IP address, e.g. 192.168.43.1") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                                    keyboardActions = KeyboardActions(onGo = { hideKeyboard(); onJoinAddress() }),
                                    modifier = Modifier.fillMaxWidth(),
                                )

                                OutlinedButton(
                                    onClick = { hideKeyboard(); onJoinAddress() },
                                    enabled = state.manualAddress.isNotBlank(),
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("Join") }
                            }
                        }
                    }

                    // Right: rooms found in the network.
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                            Text("Rooms nearby", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            LoadingIndicator(Modifier.size(32.dp))
                        }

                        if (state.rooms.isEmpty()) {
                            Column(
                                Modifier.fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Icon(Icons.Rounded.Wifi, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                                Text("Searching for rooms…", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
                                Text(
                                    "Connect to the same Wi-Fi, or to the hotspot of the player hosting the room.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.widthIn(max = 420.dp).padding(top = 4.dp),
                                )
                            }
                        } else {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
                                items(state.rooms, key = { it.key }) { room -> RoomCard(room) { onJoin(room, null) } }
                                item { Spacer(Modifier.padding(bottom = 16.dp)) }
                            }
                        }
                    }
                }
            }

            state.passwordPrompt?.let { room ->
                PasswordDialog(
                    room = room,
                    onCancel = { state.passwordPrompt = null },
                    onJoin = { password ->
                        state.passwordPrompt = null
                        onJoin(room, password)
                    },
                )
            }
        }
    }
}

@Composable
private fun RoomCard(room: LanRoomAd, onJoin: () -> Unit) {
    val isFull = room.players >= room.maxPlayers

    ElevatedCard(shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (room.isLocked) {
                        Icon(Icons.Rounded.Lock, contentDescription = "Password protected", modifier = Modifier.size(18.dp).padding(end = 4.dp))
                    }
                    Text(room.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }

                Text(
                    "${room.host} • ${room.players}/${room.maxPlayers} players" + if (room.isPlaying) " • playing" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                room.beatmap?.let {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.MusicNote, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }

            FilledTonalButton(onClick = onJoin, enabled = !isFull, modifier = Modifier.padding(start = 12.dp)) {
                Text(if (isFull) "Full" else "Join")
            }
        }
    }
}

@Composable
private fun PasswordDialog(room: LanRoomAd, onCancel: () -> Unit, onJoin: (String) -> Unit) {
    var password by remember { mutableStateOf("") }

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable(onClick = onCancel),
        contentAlignment = Alignment.Center,
    ) {
        Card(shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.widthIn(max = 420.dp).padding(16.dp).clickable(enabled = false) {}) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(room.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("This room is password protected", style = MaterialTheme.typography.bodyMedium)

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it.take(32) },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { onJoin(password) }),
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancel") }
                    Button(onClick = { onJoin(password) }, modifier = Modifier.weight(1f)) { Text("Join") }
                }
            }
        }
    }
}
