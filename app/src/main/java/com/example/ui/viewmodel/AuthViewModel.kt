package com.example.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.firebase.Firebase
import com.google.firebase.auth.actionCodeSettings
import com.google.firebase.auth.auth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/** UI state for the auth/magic-link screen. */
sealed interface AuthUiState {
    data object SignedOut : AuthUiState
    data object Sending : AuthUiState
    /** Link emailed successfully - waiting for the user to tap it. */
    data class LinkSent(val email: String) : AuthUiState
    data object Verifying : AuthUiState
    data class Error(val message: String) : AuthUiState
}

/**
 * Real login: Firebase "email link" (passwordless / magic-link) sign-in.
 * The user enters their email, we mail them a sign-in link, and tapping
 * that link (handled as a deep link in MainActivity) verifies the email
 * and signs them in via FirebaseAuth - no password, no typed code, and
 * it's genuinely free (Firebase Auth's free Spark plan covers this).
 *
 * Login state itself comes straight from FirebaseAuth.currentUser, which
 * Firebase persists on-device automatically - so the user stays logged in
 * across app restarts without any extra work here. The username is just a
 * local display-name profile field, stored in SharedPreferences.
 */
class AuthViewModel(private val context: Context) : ViewModel() {

    private val auth = Firebase.auth
    private val prefs = context.applicationContext
        .getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)

    private val _isLoggedIn = MutableStateFlow(auth.currentUser != null)
    val isLoggedIn = _isLoggedIn.asStateFlow()

    private val _username = MutableStateFlow(prefs.getString(KEY_USERNAME, "MelodyMinds") ?: "MelodyMinds")
    val username = _username.asStateFlow()

    private val _email = MutableStateFlow(auth.currentUser?.email ?: prefs.getString(KEY_PENDING_EMAIL, "") ?: "")
    val email = _email.asStateFlow()

    private val _uiState = MutableStateFlow<AuthUiState>(
        if (auth.currentUser != null) AuthUiState.SignedOut else AuthUiState.SignedOut
    )
    val uiState = _uiState.asStateFlow()

    /** Step 1: user submits email + display name -> we email them a sign-in link. */
    fun sendSignInLink(emailInput: String, usernameInput: String) {
        val trimmedEmail = emailInput.trim()
        val trimmedUsername = usernameInput.trim()
        if (trimmedEmail.isEmpty() || trimmedUsername.isEmpty()) return

        _uiState.value = AuthUiState.Sending

        val actionCodeSettings = actionCodeSettings {
            // Firebase Hosting default domain for this project - must be an
            // Authorized domain in Firebase console > Authentication > Settings.
            url = "https://nirvanamusic-75348.firebaseapp.com/finishSignIn"
            handleCodeInApp = true
            setAndroidPackageName(
                "com.aistudio.harmonixmusic.vkzpnb",
                true, // installIfNotAvailable
                ""    // minimumVersion (none enforced)
            )
        }

        viewModelScope.launch {
            try {
                auth.sendSignInLinkToEmail(trimmedEmail, actionCodeSettings).await()
                // Remember which email + username this link belongs to, so we can
                // finish sign-in when the user taps the link (possibly after the
                // process was killed while they were checking their inbox).
                prefs.edit()
                    .putString(KEY_PENDING_EMAIL, trimmedEmail)
                    .putString(KEY_PENDING_USERNAME, trimmedUsername)
                    .apply()
                _email.value = trimmedEmail
                _uiState.value = AuthUiState.LinkSent(trimmedEmail)
            } catch (e: Exception) {
                _uiState.value = AuthUiState.Error(e.message ?: "Couldn't send the sign-in email. Try again.")
            }
        }
    }

    /**
     * Step 2: called from MainActivity when the app is opened via the emailed
     * link (deep link). Verifies the link and completes sign-in.
     */
    fun handleSignInLink(link: String?) {
        if (link == null || !auth.isSignInWithEmailLink(link)) return

        val pendingEmail = prefs.getString(KEY_PENDING_EMAIL, null)
        if (pendingEmail == null) {
            _uiState.value = AuthUiState.Error(
                "Ye link kisi aur email session ka lagta hai. Same device pe dobara email daal ke try karo."
            )
            return
        }

        _uiState.value = AuthUiState.Verifying

        viewModelScope.launch {
            try {
                auth.signInWithEmailLink(pendingEmail, link).await()
                val savedUsername = prefs.getString(KEY_PENDING_USERNAME, null) ?: "MelodyMinds"

                _username.value = savedUsername
                _email.value = pendingEmail
                _isLoggedIn.value = true
                _uiState.value = AuthUiState.SignedOut // reset, screen won't show since isLoggedIn is now true

                prefs.edit()
                    .putString(KEY_USERNAME, savedUsername)
                    .remove(KEY_PENDING_EMAIL)
                    .remove(KEY_PENDING_USERNAME)
                    .apply()
            } catch (e: Exception) {
                _uiState.value = AuthUiState.Error(e.message ?: "Login link expire ho gaya ya invalid hai. Dobara try karo.")
            }
        }
    }

    fun dismissError() {
        _uiState.value = AuthUiState.SignedOut
    }

    fun logout() {
        auth.signOut()
        _isLoggedIn.value = false
        _uiState.value = AuthUiState.SignedOut
    }

    companion object {
        private const val KEY_USERNAME = "username"
        private const val KEY_PENDING_EMAIL = "pending_email"
        private const val KEY_PENDING_USERNAME = "pending_username"
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return AuthViewModel(context) as T
        }
    }
}
