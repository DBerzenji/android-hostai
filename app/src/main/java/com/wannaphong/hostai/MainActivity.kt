package com.wannaphong.hostai

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.OpenableColumns
import android.text.format.Formatter
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.wannaphong.hostai.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding
    private var apiServerService: ApiServerService? = null
    private var isBound = false
    private var selectedModelPath: String? = null
    private var selectedModelName: String? = null
    private var wasServerRunningBeforeModelChange = false
    private lateinit var modelManager: ModelManager
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as ApiServerService.LocalBinder
            apiServerService = binder.getService()
            isBound = true
            updateUI()
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            apiServerService = null
            isBound = false
            updateUI()
        }
    }
    
    
    private val modelManagementLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // Model selection changed, reload from manager
            loadSelectedModelFromManager()
            updateUI()
        }
    }
    
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            LogManager.i("MainActivity", "Notification permission granted")
            proceedToStartServer()
        } else {
            LogManager.w("MainActivity", "Notification permission denied")
            Toast.makeText(
                this,
                getString(R.string.notification_permission_required),
                Toast.LENGTH_LONG
            ).show()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        modelManager = ModelManager(this)
        loadSelectedModelFromManager()
        
        setupUI()
        bindToService()
    }
    
    private fun setupUI() {
        binding.startStopButton.setOnClickListener {
            if (isServerRunning()) {
                stopServer()
            } else {
                startServer()
            }
        }
        
        binding.openBrowserButton.setOnClickListener {
            openInBrowser()
        }
        
        // Setup Model Selector
        setupModelSelector()
        
        binding.viewLogsCard.setOnClickListener {
            openLogViewer()
        }
        
        binding.manageCompletionsCard.setOnClickListener {
            openStoredCompletions()
        }
        
        binding.manageModelsCard.setOnClickListener {
            openModelManagement()
        }
        
        binding.monitorUsageCard.setOnClickListener {
            openMonitorUsage()
        }
        
        binding.settingsCard.setOnClickListener {
            openSettings()
        }
        
        binding.exitButton.setOnClickListener {
            exitApp()
        }
        
        updateUI()
    }

    private fun setupModelSelector() {
        val models = modelManager.getModels()
        val modelNames = models.map { it.name }
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, modelNames)
        binding.modelSelector.setAdapter(adapter)
        
        // Set current selection
        val selectedModel = modelManager.getSelectedModel()
        selectedModel?.let {
            binding.modelSelector.setText(it.name, false)
        }
        
        binding.modelSelector.setOnItemClickListener { _, _, position, _ ->
            val selectedModel = models[position]
            modelManager.setSelectedModelId(selectedModel.id)
            selectedModelPath = selectedModel.path
            selectedModelName = selectedModel.name
            
            if (isServerRunning()) {
                // If server is running, offer to restart or just stop
                changeModel()
            } else {
                Toast.makeText(this, "Model selected: ${selectedModel.name}", Toast.LENGTH_SHORT).show()
                updateUI()
            }
        }
    }

    private fun openInBrowser() {
        val ipAddress = getLocalIpAddress()
        val port = apiServerService?.getServerPort() ?: ApiServerService.DEFAULT_PORT
        val url = "http://$ipAddress:$port"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        startActivity(intent)
    }
    
    private fun loadSelectedModelFromManager() {
        val selectedModel = modelManager.getSelectedModel()
        if (selectedModel != null) {
            selectedModelPath = selectedModel.path
            selectedModelName = selectedModel.name
            LogManager.i("MainActivity", "Loaded selected model from manager: ${selectedModel.name}")
        }
    }
    
    private fun openSettings() {
        val intent = Intent(this, SettingsActivity::class.java)
        startActivity(intent)
    }
    
    private fun openModelManagement() {
        val intent = Intent(this, ModelManagementActivity::class.java)
        modelManagementLauncher.launch(intent)
    }
    
    private fun openMonitorUsage() {
        val intent = Intent(this, MonitorActivity::class.java)
        startActivity(intent)
    }
    
    private fun exitApp() {
        LogManager.i("MainActivity", "User requested to exit app")
        
        // Stop server if running
        if (isServerRunning()) {
            stopServer()
        }
        
        // Unbind service
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        
        // Finish activity and exit
        finishAffinity()
    }
    
    private fun bindToService() {
        val intent = Intent(this, ApiServerService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }
    
    private fun startServer() {
        LogManager.i("MainActivity", "User requested to start server")
        
        // Check for notification permission on Android 13+ (API 33+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> {
                    // Permission already granted
                    proceedToStartServer()
                }
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                    // Show rationale and request permission
                    Toast.makeText(
                        this,
                        getString(R.string.notification_permission_required),
                        Toast.LENGTH_LONG
                    ).show()
                    requestNotificationPermission()
                }
                else -> {
                    // Request permission directly
                    requestNotificationPermission()
                }
            }
        } else {
            // No permission needed for older Android versions
            proceedToStartServer()
        }
    }
    
    private fun requestNotificationPermission() {
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    
    private fun proceedToStartServer() {
        LogManager.i("MainActivity", "Proceeding to start server")
        
        // Get custom port from settings
        val settingsManager = SettingsManager(this)
        val customPort = settingsManager.getCustomPort()
        
        val intent = Intent(this, ApiServerService::class.java).apply {
            action = ApiServerService.ACTION_START
            putExtra(ApiServerService.EXTRA_PORT, customPort)
            selectedModelPath?.let { 
                LogManager.i("MainActivity", "Starting server with model: $selectedModelName")
                putExtra(ApiServerService.EXTRA_MODEL_PATH, it)
            } ?: run {
                LogManager.i("MainActivity", "Starting server with mock model (no model selected)")
            }
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        
        // Wait a moment for service to start, then update UI
        binding.root.postDelayed({
            updateUI()
        }, 500)
    }
    
    private fun stopServer() {
        LogManager.i("MainActivity", "User requested to stop server")
        
        val intent = Intent(this, ApiServerService::class.java).apply {
            action = ApiServerService.ACTION_STOP
        }
        startService(intent)
        
        binding.root.postDelayed({
            updateUI()
        }, 500)
    }
    
    private fun isServerRunning(): Boolean {
        return apiServerService?.isServerRunning() ?: false
    }
    
    private fun updateUI() {
        val isRunning = isServerRunning()
        
        if (isRunning) {
            binding.serverStatusText.text = "Server: Running"
            binding.statusDot.setImageResource(R.drawable.ic_dot_green)
            binding.startStopButton.text = "Stop Server"
            
            binding.openBrowserButton.visibility = View.VISIBLE
            
            // Disable model selector while running (or handle restart)
            binding.modelSelectorLayout.isEnabled = true
        } else {
            binding.serverStatusText.text = "Server: Stopped"
            binding.statusDot.setImageResource(R.drawable.ic_dot_red)
            binding.startStopButton.text = "Start Server"
            
            binding.openBrowserButton.visibility = View.GONE
            
            binding.modelSelectorLayout.isEnabled = true
        }
    }
    
    private fun getLocalIpAddress(): String {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // For Android 12+, use ConnectivityManager
                val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val network = connectivityManager.activeNetwork
                val linkProperties = connectivityManager.getLinkProperties(network)
                linkProperties?.linkAddresses?.forEach { linkAddress ->
                    val address = linkAddress.address
                    if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                        return address.hostAddress ?: "localhost"
                    }
                }
            } else {
                // For older Android versions, use WifiManager
                @Suppress("DEPRECATION")
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                val ipAddress = wifiManager.connectionInfo.ipAddress
                return Formatter.formatIpAddress(ipAddress)
            }
        } catch (e: Exception) {
            return "localhost"
        }
        return "localhost"
    }
    
    
    private fun changeModel() {
        LogManager.i("MainActivity", "User requested to change model while server is running")
        
        // Stop the server first
        wasServerRunningBeforeModelChange = true
        stopServer()
        
        Toast.makeText(this, R.string.server_stopped_to_change_model, Toast.LENGTH_SHORT).show()
        
        // Wait for server to stop
        binding.root.postDelayed({
            updateUI()
        }, 500)
    }
    
    
    private fun openLogViewer() {
        val intent = Intent(this, LogViewerActivity::class.java)
        startActivity(intent)
    }
    
    private fun openStoredCompletions() {
        val intent = Intent(this, StoredCompletionsActivity::class.java)
        startActivity(intent)
    }
    
    
    override fun onResume() {
        super.onResume()
        // Reload selected model from manager in case it was changed
        loadSelectedModelFromManager()
        updateUI()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }
}
