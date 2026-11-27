package com.foundryos.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.foundryos.app.data.FoundryRepository
import com.foundryos.app.service.AgentExecutionService
import com.foundryos.app.service.ApprovalNotifier
import com.foundryos.app.ui.screens.FoundryMainScreen
import com.foundryos.app.ui.theme.FoundryTheme

class MainActivity : ComponentActivity() {
    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ApprovalNotifier.ensureChannel(this)
        AgentExecutionService.ensureChannel(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        val repo = FoundryRepository(applicationContext)
        setContent {
            FoundryTheme {
                FoundryMainScreen(repo = repo)
            }
        }
    }
}
