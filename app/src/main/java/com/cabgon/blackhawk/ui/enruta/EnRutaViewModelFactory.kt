package com.cabgon.blackhawk.ui.enruta

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.cabgon.blackhawk.data.enruta.EnRutaRepository

class EnRutaViewModelFactory(
    private val repo: EnRutaRepository,
    private val currentUserIdProvider: () -> String?
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(EnRutaViewModel::class.java)) {
            return EnRutaViewModel(
                repository = repo,
                currentUserIdProvider = currentUserIdProvider
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
