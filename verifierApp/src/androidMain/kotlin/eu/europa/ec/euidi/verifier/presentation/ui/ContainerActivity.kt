/*
 * Copyright (c) 2026 European Commission
 *
 * Licensed under the EUPL, Version 1.2 or - as soon they will be approved by the European
 * Commission - subsequent versions of the EUPL (the "Licence"); You may not use this work
 * except in compliance with the Licence.
 *
 * You may obtain a copy of the Licence at:
 * https://joinup.ec.europa.eu/software/page/eupl
 *
 * Unless required by applicable law or agreed to in writing, software distributed under
 * the Licence is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF
 * ANY KIND, either express or implied. See the Licence for the specific language
 * governing permissions and limitations under the Licence.
 */

package eu.europa.ec.euidi.verifier.presentation.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import eu.europa.ec.euidi.verifier.core.controller.AndroidPlatformController
import eu.europa.ec.euidi.verifier.core.controller.PlatformController
import eu.europa.ec.euidi.verifier.presentation.ui.container.ContainerView
import org.koin.android.ext.android.inject

class ContainerActivity : ComponentActivity() {

    private val platformController: PlatformController by inject()

    // Wi-Fi Aware proximity (NAN) needs NEARBY_WIFI_DEVICES on API 33+. The shared
    // moko-permissions flow has no constant for it, so request it here on the Android side.
    // On API 29-32 Aware discovery is covered by the location permissions requested via moko.
    private val nearbyWifiPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        registerActivityWithPlatformController()
        requestNearbyWifiDevicesIfNeeded()
        setContent {
            ContainerView()
        }
    }

    private fun requestNearbyWifiDevicesIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.NEARBY_WIFI_DEVICES
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            nearbyWifiPermissionLauncher.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
    }

    private fun registerActivityWithPlatformController() {
        (platformController as? AndroidPlatformController)?.registerActivity(this)
    }
}