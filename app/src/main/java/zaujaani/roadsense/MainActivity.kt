package zaujaani.roadsense

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.NavigationUI
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import timber.log.Timber
import zaujaani.roadsense.core.maps.OfflineMapManager
import zaujaani.roadsense.core.service.TrackingForegroundService
import zaujaani.roadsense.data.repository.UserPreferencesRepository
import zaujaani.roadsense.databinding.ActivityMainBinding
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController
    private lateinit var appBarConfiguration: AppBarConfiguration

    @Inject
    lateinit var offlineMapManager: OfflineMapManager

    @Inject
    lateinit var userPreferencesRepo: UserPreferencesRepository

    // ========== PERMISSIONS ==========
    private val requiredPermissions = mutableListOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            add(Manifest.permission.BLUETOOTH)
            add(Manifest.permission.BLUETOOTH_ADMIN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            add(Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC)
            add(Manifest.permission.FOREGROUND_SERVICE_LOCATION)
        }
    }.toTypedArray()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            Timber.d("✅ All permissions granted")
            startForegroundService()
            updateOSMConfiguration()
        } else {
            Timber.w("⚠️ Some permissions denied")
            showPermissionRationale()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        setupNavigation()
        setupNonNavMenuItems()

        if (hasRequiredPermissions()) {
            Timber.d("✅ All permissions already granted")
            startForegroundService()
            updateOSMConfiguration()
        } else {
            checkAndRequestPermissions()
        }
    }

    private fun updateOSMConfiguration() {
        lifecycleScope.launch {
            offlineMapManager.updateOSMConfiguration()
            val email = userPreferencesRepo.getCurrentEmail()
            if (email.isNullOrBlank()) {
                Toast.makeText(
                    this@MainActivity,
                    "Silakan isi email di Pengaturan untuk mendukung server OSM",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun setupNavigation() {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController

        appBarConfiguration = AppBarConfiguration(
            setOf(
                R.id.mapSurveyFragment,
                R.id.summaryFragment,
                R.id.calibrationFragment,
                R.id.settingsFragment,
                R.id.offlineMapsFragment
            ),
            binding.drawerLayout
        )

        setupActionBarWithNavController(navController, appBarConfiguration)
        binding.navView.setupWithNavController(navController)
    }

    private fun setupNonNavMenuItems() {
        binding.navView.setNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.nav_help -> {
                    showHelpDialog()
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                    true
                }
                else -> {
                    val handled = NavigationUI.onNavDestinationSelected(menuItem, navController)
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                    handled
                }
            }
        }
    }

    private fun showHelpDialog() {
        AlertDialog.Builder(this)
            .setTitle("Bantuan")
            .setMessage("RoadSense v1.0\n\nUntuk bantuan lebih lanjut, hubungi tim support.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun hasRequiredPermissions(): Boolean {
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest)
        } else {
            startForegroundService()
            updateOSMConfiguration()
        }
    }

    private fun startForegroundService() {
        val intent = Intent(this, TrackingForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun showPermissionRationale() {
        val message = buildString {
            append("RoadSense memerlukan izin berikut:\n\n")
            append("📍 Akses Lokasi (Background & Foreground)\n")
            append("   • Untuk melacak perjalanan survei\n")
            append("   • Menampilkan lokasi di peta\n\n")
            append("📡 Akses Bluetooth\n")
            append("   • Untuk terhubung ke sensor ESP32\n\n")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                append("🔔 Izin Notifikasi\n")
                append("   • Menampilkan status survei\n\n")
            }
            append("⚠️ Sensor adalah sumber jarak, GPS hanya referensi posisi.\n")
            append("Tanpa izin ini, aplikasi tidak dapat berfungsi sebagai logger profesional.")
        }

        AlertDialog.Builder(this)
            .setTitle("Izin Diperlukan")
            .setMessage(message)
            .setPositiveButton("Buka Pengaturan") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                intent.data = Uri.fromParts("package", packageName, null)
                startActivity(intent)
            }
            .setNegativeButton("Tutup Aplikasi") { _, _ ->
                finish()
            }
            .setCancelable(false)
            .show()
    }

    override fun onSupportNavigateUp(): Boolean {
        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
    }

    override fun onResume() {
        super.onResume()
        if (!hasRequiredPermissions()) {
            Toast.makeText(
                this,
                "Izin lokasi dan bluetooth diperlukan",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}