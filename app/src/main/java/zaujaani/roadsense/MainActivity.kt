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
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import zaujaani.roadsense.core.service.TrackingForegroundService
import zaujaani.roadsense.databinding.ActivityMainBinding

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController
    private lateinit var appBarConfiguration: AppBarConfiguration

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
            // Start foreground service setelah permission granted
            startForegroundService()
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

        // Setup drawer menu item click listener
        setupDrawerMenu()

        // Check permissions
        if (hasRequiredPermissions()) {
            Timber.d("✅ All permissions already granted")
            startForegroundService()
        } else {
            checkAndRequestPermissions()
        }

        Timber.d("🚀 MainActivity created")
    }

    private fun setupNavigation() {
        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment)
                    as NavHostFragment

        navController = navHostFragment.navController

        // Top-level destinations - akan menampilkan hamburger icon
        appBarConfiguration = AppBarConfiguration(
            setOf(R.id.mapSurveyFragment, R.id.summaryFragment),
            binding.drawerLayout
        )

        setupActionBarWithNavController(navController, appBarConfiguration)

        // Setup navigation view dengan nav controller
        binding.navView.setupWithNavController(navController)
    }

    private fun setupDrawerMenu() {
        binding.navView.setNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.mapSurveyFragment -> {
                    // Jika belum di fragment map survey, navigate ke sana
                    if (navController.currentDestination?.id != R.id.mapSurveyFragment) {
                        navController.navigate(R.id.mapSurveyFragment)
                    }
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                    true
                }

                R.id.summaryFragment -> {
                    // Navigate ke summary dengan sessionId default -1
                    if (navController.currentDestination?.id != R.id.summaryFragment) {
                        val bundle = Bundle().apply {
                            putLong("sessionId", -1L)
                        }
                        navController.navigate(R.id.summaryFragment, bundle)
                    }
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                    true
                }

                R.id.nav_settings -> {
                    // Navigate ke settings fragment
                    navController.navigate(R.id.settingsFragment)
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                    true
                }

                R.id.nav_calibration -> {
                    // Navigate ke calibration screen
                    navController.navigate(R.id.calibrationFragment)
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                    true
                }

                else -> false
            }
        }
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
            Timber.d("📝 Requesting permissions: ${permissionsToRequest.asList()}")
            permissionLauncher.launch(permissionsToRequest)
        } else {
            Timber.d("✅ All permissions already granted")
            startForegroundService()
        }
    }

    private fun startForegroundService() {
        // Start foreground service untuk menjaga koneksi hardware
        val serviceIntent = Intent(this, TrackingForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun showPermissionRationale() {
        val message = buildString {
            append("RoadSense memerlukan izin berikut:\n\n")
            append("📍 Akses Lokasi (Background & Foreground)\n")
            append("   • Untuk melacak perjalanan survei\n")
            append("   • Menampilkan lokasi di peta\n")
            append("   • Survey tetap berjalan saat GPS drop\n\n")
            append("📡 Akses Bluetooth\n")
            append("   • Untuk terhubung ke sensor ESP32\n")
            append("   • Menerima data sensor sebagai sumber jarak utama\n\n")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                append("🔔 Izin Notifikasi\n")
                append("   • Menampilkan status survey yang berjalan\n")
                append("   • Peringatan battery rendah dari ESP32\n\n")
            }

            append("⚠️ PRINSIP UTAMA: Sensor adalah sumber jarak, GPS hanya referensi posisi\n\n")
            append("Tanpa izin ini, aplikasi tidak dapat berfungsi sebagai logger profesional.")
        }

        AlertDialog.Builder(this)
            .setTitle("Izin Diperlukan - Logger Profesional")
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
        // Update permissions status when returning from settings
        if (!hasRequiredPermissions()) {
            Toast.makeText(
                this,
                "Izin lokasi dan bluetooth diperlukan untuk fungsionalitas penuh",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}