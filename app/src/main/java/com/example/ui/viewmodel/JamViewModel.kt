package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.model.ChatMessage
import com.example.data.model.Track
import com.example.data.model.TrackSongBridge
import com.example.jam.JamChatManager
import com.example.jam.JamManager
import com.example.player.MusicPlayer
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class JamUiState(
    val roomCode: String? = null,
    val isHost: Boolean = false,
    val isInRoom: Boolean = false,
    val isConnecting: Boolean = false,
    val errorMessage: String? = null
)

/**
 * Real cross-device Jam + live chat, backed by Firebase Realtime Database
 * (JamManager for playback sync, JamChatManager for messages/typing).
 * Replaces the previous single-device ChatRepository simulation.
 */
class JamViewModel(
    private val jamManager: JamManager,
    private val jamChatManager: JamChatManager,
    val musicPlayer: MusicPlayer
) : ViewModel() {

    private val _uiState = MutableStateFlow(JamUiState())
    val uiState: StateFlow<JamUiState> = _uiState.asStateFlow()

    private val _participants = MutableStateFlow<List<JamManager.JamParticipant>>(emptyList())
    val participants: StateFlow<List<JamManager.JamParticipant>> = _participants.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _typingUsers = MutableStateFlow<Set<String>>(emptySet())
    val typingUsers: StateFlow<Set<String>> = _typingUsers.asStateFlow()

    private val _replyMessage = MutableStateFlow<ChatMessage?>(null)
    val replyMessage: StateFlow<ChatMessage?> = _replyMessage.asStateFlow()

    val myUid: String? get() = FirebaseAuth.getInstance().currentUser?.uid

    init {
        jamManager.onParticipantsChanged = { list -> _participants.value = list }
        jamManager.onRemoteSongChange = { song -> musicPlayer.applyRemoteSongChange(song) }
        jamManager.onRemotePlayPause = { isPlaying, positionMs -> musicPlayer.applyRemotePlayPause(isPlaying, positionMs) }
        jamManager.onRemoteSeek = { positionMs -> musicPlayer.applyRemoteSeek(positionMs) }
        jamManager.onLog = { msg -> _uiState.value = _uiState.value.copy(errorMessage = msg) }

        // Whenever THIS device changes song/play-pause/seek locally, broadcast it -
        // but only while we're actually in a room.
        musicPlayer.onLocalSongChange = { track ->
            if (jamManager.roomCode != null) jamManager.pushSongChange(TrackSongBridge.toSong(track))
        }
        musicPlayer.onLocalPlayPause = { isPlaying, positionMs ->
            if (jamManager.roomCode != null) jamManager.pushPlayPause(isPlaying, positionMs)
        }
        musicPlayer.onLocalSeek = { positionMs ->
            if (jamManager.roomCode != null) jamManager.pushSeek(positionMs)
        }

        jamChatManager.onMessageAdded = { msg -> _messages.value = _messages.value + msg }
        jamChatManager.onMessageChanged = { msg ->
            _messages.value = _messages.value.map { if (it.id == msg.id) msg else it }
        }
        jamChatManager.onTypingUsersChanged = { uids ->
            _typingUsers.value = uids - setOfNotNull(myUid)
        }
    }

    fun createRoom(displayName: String, avatar: String = "🎧") {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isConnecting = true, errorMessage = null)
            try {
                val currentTrack = musicPlayer.currentTrack.value
                val code = jamManager.createRoom(
                    displayName = displayName,
                    avatar = avatar,
                    currentSong = currentTrack?.let { TrackSongBridge.toSong(it) },
                    isPlaying = musicPlayer.isPlaying.value,
                    positionMs = musicPlayer.playbackPosition.value
                )
                jamChatManager.attach(code)
                _uiState.value = JamUiState(roomCode = code, isHost = true, isInRoom = true)
            } catch (e: Exception) {
                _uiState.value = JamUiState(errorMessage = e.message ?: "Failed to create Jam room")
            }
        }
    }

    fun joinRoom(code: String, displayName: String, avatar: String = "🎧") {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isConnecting = true, errorMessage = null)
            try {
                val joined = jamManager.joinRoom(code, displayName, avatar)
                if (joined) {
                    val roomCode = jamManager.roomCode!!
                    jamChatManager.attach(roomCode)
                    _uiState.value = JamUiState(roomCode = roomCode, isHost = false, isInRoom = true)
                } else {
                    _uiState.value = JamUiState(errorMessage = "Room not found. Double-check the code and try again.")
                }
            } catch (e: Exception) {
                _uiState.value = JamUiState(errorMessage = e.message ?: "Failed to join Jam room")
            }
        }
    }

    fun leaveRoom() {
        jamManager.leaveRoom()
        jamChatManager.detach()
        _messages.value = emptyList()
        _participants.value = emptyList()
        _typingUsers.value = emptySet()
        _uiState.value = JamUiState()
    }

    fun dismissError() {
        _uiState.value = _uiState.value.copy(errorMessage = null, isConnecting = false)
    }

    fun setReplyTo(message: ChatMessage?) {
        _replyMessage.value = message
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        val uid = myUid ?: return
        val me = _participants.value.find { it.uid == uid }
        val reply = _replyMessage.value
        jamChatManager.sendMessage(
            senderId = uid,
            senderName = me?.name ?: "Me",
            senderAvatarUrl = me?.avatar ?: "🎧",
            text = text,
            replyToId = reply?.id,
            replyToText = reply?.text,
            replyToSenderName = reply?.senderName
        )
        _replyMessage.value = null
    }

    fun toggleReaction(messageId: String, emoji: String) {
        val uid = myUid ?: return
        jamChatManager.toggleReaction(messageId, emoji, uid)
    }

    fun setUserTyping(isTyping: Boolean) {
        val uid = myUid ?: return
        jamChatManager.setTyping(uid, isTyping)
    }

    /** Host or any participant picks a track inside the Jam - broadcasts via MusicPlayer's onLocalSongChange hook. */
    fun hostPlayTrack(track: Track, tracksList: List<Track>) {
        musicPlayer.setQueue(tracksList, tracksList.indexOfFirst { it.id == track.id }.coerceAtLeast(0))
    }

    override fun onCleared() {
        super.onCleared()
        jamManager.leaveRoom()
        jamChatManager.detach()
    }

    class Factory(
        private val jamManager: JamManager,
        private val jamChatManager: JamChatManager,
        private val musicPlayer: MusicPlayer
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return JamViewModel(jamManager, jamChatManager, musicPlayer) as T
        }
    }
}
