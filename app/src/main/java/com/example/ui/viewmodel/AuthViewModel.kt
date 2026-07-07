package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class AuthViewModel : ViewModel() {
    private val _isLoggedIn = MutableStateFlow(false)
    val isLoggedIn = _isLoggedIn.asStateFlow()

    private val _username = MutableStateFlow("MelodyMinds")
    val username = _username.asStateFlow()

    private val _email = MutableStateFlow("")
    val email = _email.asStateFlow()

    fun login(emailInput: String, usernameInput: String) {
        if (emailInput.isNotEmpty() && usernameInput.isNotEmpty()) {
            _email.value = emailInput
            _username.value = usernameInput
            _isLoggedIn.value = true
        }
    }

    fun logout() {
        _isLoggedIn.value = false
    }
}
