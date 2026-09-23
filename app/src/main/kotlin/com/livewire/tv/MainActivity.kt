package com.livewire.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.livewire.tv.feature.providers.data.ProviderStorage
import com.livewire.tv.navigation.LiveWireNavHost
import com.livewire.tv.ui.theme.LiveWireTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var providerStorage: ProviderStorage

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // First run (no provider configured) → onboarding, otherwise → home.
        val startAtHome = providerStorage.hasAny()
        setContent {
            LiveWireTheme {
                LiveWireNavHost(startAtHome = startAtHome)
            }
        }
    }
}
