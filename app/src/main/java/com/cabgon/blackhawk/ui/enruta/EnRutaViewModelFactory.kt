package com.cabgon.blackhawk.ui.enruta

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.cabgon.blackhawk.data.enruta.EnRutaRepository

class EnRutaViewModelFactory(
    private val repo: EnRutaRepository,
    private val currentEditorProvider: () -> EnRutaViewModel.EditorIdentity?
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(EnRutaViewModel::class.java)) {
            return EnRutaViewModel(
                repository = repo,
                currentEditorProvider = currentEditorProvider
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
