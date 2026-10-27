package com.mosu.lan

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.edlplan.ui.fragment.BaseFragment
import com.mosu.ui.lan.LanLobbyScreen
import com.mosu.ui.theme.MosuTheme
import com.osudroid.utils.mainThread
import ru.nsu.ccfit.zuev.osu.GlobalManager
import app.mosu.R

/**
 * Everything the LAN lobby displays.
 */
class LanLobbyState {
    var username by mutableStateOf("")
    var roomName by mutableStateOf("")
    var roomPassword by mutableStateOf("")
    var maxPlayers by mutableStateOf(8)
    var manualAddress by mutableStateOf("")
    var passwordPrompt by mutableStateOf<LanRoomAd?>(null)
    val rooms = mutableStateListOf<LanRoomAd>()
}

/**
 * Lobby of LAN multiplayer: lists rooms announced in the local network (shared Wi-Fi or a player's hotspot) and lets
 * the player host a room or join one, including by IP address.
 */
class LanLobby : BaseFragment() {

    override val layoutID = R.layout.lan_lobby_fragment

    private val state = LanLobbyState()

    private var scanner: LanScanner? = null


    override fun onLoadView() {
        state.username = LanSession.username
        state.roomName = "${LanSession.username}'s room"

        val compose = findViewById<ComposeView>(R.id.lan_lobby_compose)!!
        compose.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        compose.setContent {
            MosuTheme(darkTheme = true) {
                LanLobbyScreen(
                    state = state,
                    onClose = ::dismiss,
                    onCreate = ::createRoom,
                    onJoin = ::joinRoom,
                    onJoinAddress = ::joinAddress,
                )
            }
        }

        scanner = LanScanner(requireContext()).also { scanner ->
            scanner.start { rooms ->
                mainThread {
                    state.rooms.clear()
                    state.rooms.addAll(rooms)
                }
            }
        }
    }

    private fun saveUsername() {
        LanSession.username = state.username.ifBlank { "Player" }
    }

    private fun createRoom() {
        saveUsername()
        dismiss()
        LanSession.host(state.roomName, state.roomPassword.ifEmpty { null }, state.maxPlayers)
    }

    private fun joinRoom(room: LanRoomAd, password: String?) {
        if (room.isLocked && password == null) {
            state.passwordPrompt = room
            return
        }

        saveUsername()
        dismiss()
        LanSession.join(room.address, room.port, password)
    }

    private fun joinAddress() {
        val input = state.manualAddress.trim()
        val host = input.substringBefore(':')
        val port = input.substringAfter(':', "").toIntOrNull() ?: LanProtocol.ROOM_PORT

        if (host.isEmpty()) return

        saveUsername()
        dismiss()
        LanSession.join(host, port, null)
    }


    override fun callDismissOnBackPress() {
        if (state.passwordPrompt != null) {
            state.passwordPrompt = null
            return
        }

        dismiss()
    }

    override fun dismiss() {
        scanner?.stop()
        scanner = null

        if (!LanSession.isActive) {
            GlobalManager.getInstance().songService.isGaming = false
        }

        super.dismiss()
    }
}
