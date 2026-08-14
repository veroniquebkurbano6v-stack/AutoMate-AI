package com.palmagent.app.ui.guide

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.palmagent.app.R
import com.palmagent.app.service.LocationService
import com.palmagent.app.utils.KVUtils
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class GuideActivity : ComponentActivity() {

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1001
    }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true

        if (fineGranted && coarseGranted) {
            Toast.makeText(this, "位置权限已授予", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "位置权限未完全授予，高德地图功能可能受限", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_guide)

        bindSection(
            findViewById(R.id.guideAccessibility),
            R.mipmap.ic_launcher,
            R.string.guide_title_accessibility,
            R.string.guide_desc_accessibility
        )
        bindSection(
            findViewById(R.id.guideNotification),
            R.mipmap.ic_launcher,
            R.string.guide_title_notification,
            R.string.guide_desc_notification
        )
        bindSection(
            findViewById(R.id.guideOverlay),
            R.mipmap.ic_launcher,
            R.string.guide_title_overlay,
            R.string.guide_desc_overlay
        )
        bindSection(
            findViewById(R.id.guideBattery),
            R.mipmap.ic_launcher,
            R.string.guide_title_battery,
            R.string.guide_desc_battery
        )
        bindSection(
            findViewById(R.id.guideStorage),
            R.mipmap.ic_launcher,
            R.string.guide_title_storage,
            R.string.guide_desc_storage
        )
        bindLocationSection()

        findViewById<View>(R.id.btnStart).setOnClickListener { finishGuide() }
        findViewById<View>(R.id.tvSkip).setOnClickListener { finishGuide() }
    }

    private fun bindLocationSection() {
        val locationView = findViewById<View>(R.id.guideLocation)
        locationView.findViewById<ImageView>(R.id.ivIcon).setImageResource(R.mipmap.ic_launcher)
        locationView.findViewById<TextView>(R.id.tvTitle).setText(R.string.guide_title_location)
        locationView.findViewById<TextView>(R.id.tvDescription).setText(R.string.guide_desc_location)

        // 点击位置权限 section 时请求权限
        locationView.setOnClickListener {
            requestLocationPermission()
        }
    }

    private fun requestLocationPermission() {
        if (LocationService.hasLocationPermission(this)) {
            Toast.makeText(this, "位置权限已授予", Toast.LENGTH_SHORT).show()
            return
        }

        locationPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    private fun bindSection(view: View, iconRes: Int, titleRes: Int, descRes: Int) {
        view.findViewById<ImageView>(R.id.ivIcon).setImageResource(iconRes)
        view.findViewById<TextView>(R.id.tvTitle).setText(titleRes)
        view.findViewById<TextView>(R.id.tvDescription).setText(descRes)
    }

    private fun finishGuide() {
        KVUtils.setGuideShown(true)
        finish()
    }
}
