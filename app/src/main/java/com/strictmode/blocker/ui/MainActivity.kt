package com.strictmode.blocker.ui

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.strictmode.blocker.data.AppRepository
import com.strictmode.blocker.data.InstalledAppInfo
import com.strictmode.blocker.data.LockSessionState
import com.strictmode.blocker.data.LockStateManager
import com.strictmode.blocker.service.LockEnforcementService
import com.strictmode.blocker.service.OverlayShieldManager
import com.strictmode.blocker.util.PermissionHelper
import kotlinx.coroutines.delay

private val StrictDarkColors = darkColorScheme(
    primary = Color(0xFFEF4444),
    secondary = Color(0xFFF87171),
    background = Color(0xFF0D1117),
    surface = Color(0xFF161B22),
    onPrimary = Color.White,
    onBackground = Color(0xFFF0F6FC),
    onSurface = Color(0xFFF0F6FC)
)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Refresh lock state immediately on startup
        val initialState = LockStateManager.refreshState(this)
        if (initialState.isLocked) {
            LockEnforcementService.startOrUpdate(this)
        }

        setContent {
            MaterialTheme(colorScheme = StrictDarkColors) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    StrictLockAppScreen()
                }
            }
        }
    }
}

@Composable
fun StrictLockAppScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val lockState by LockStateManager.stateFlow.collectAsState()

    var installedApps by remember { mutableStateOf<List<InstalledAppInfo>>(emptyList()) }
    var isLoadingApps by remember { mutableStateOf(true) }
    var selectedPackages by remember { mutableStateOf<Set<String>>(emptySet()) }
    var searchQuery by remember { mutableStateOf("") }

    var durationMinutes by remember { mutableIntStateOf(30) }
    var customMinutesText by remember { mutableStateOf("30") }
    var blockSettingsAndUninstall by remember { mutableStateOf(true) }
    var blockPowerMenu by remember { mutableStateOf(true) }
    var showConfirmStartDialog by remember { mutableStateOf(false) }

    // Permission states
    var hasAccessibility by remember { mutableStateOf(PermissionHelper.isAccessibilityServiceEnabled(context)) }
    var hasDeviceAdmin by remember { mutableStateOf(PermissionHelper.isDeviceAdminEnabled(context)) }
    var hasOverlay by remember { mutableStateOf(PermissionHelper.isOverlayPermissionGranted(context)) }
    var hasBatteryExemption by remember { mutableStateOf(PermissionHelper.isBatteryOptimizationIgnored(context)) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = {}
    )

    // Refresh permissions whenever the user returns from system settings
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasAccessibility = PermissionHelper.isAccessibilityServiceEnabled(context)
                hasDeviceAdmin = PermissionHelper.isDeviceAdminEnabled(context)
                hasOverlay = PermissionHelper.isOverlayPermissionGranted(context)
                hasBatteryExemption = PermissionHelper.isBatteryOptimizationIgnored(context)
                LockStateManager.refreshState(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Request notification permission on Android 13+ for foreground countdown visibility
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val initial = LockStateManager.refreshState(context)
        selectedPackages = initial.blockedPackages
        installedApps = AppRepository.getLaunchableApps(context)
        isLoadingApps = false
    }

    // Live 1-second UI countdown ticker while locked
    LaunchedEffect(lockState.isLocked) {
        while (lockState.isLocked) {
            LockStateManager.refreshState(context)
            delay(1000L)
        }
    }

    if (lockState.isLocked) {
        ActiveLockDashboard(
            lockState = lockState,
            installedApps = installedApps
        )
    } else {
        SetupLockScreen(
            installedApps = installedApps,
            isLoadingApps = isLoadingApps,
            selectedPackages = selectedPackages,
            onTogglePackage = { pkg ->
                val updated = if (pkg in selectedPackages) {
                    selectedPackages - pkg
                } else {
                    selectedPackages + pkg
                }
                selectedPackages = updated
                LockStateManager.saveSelectedPackages(context, updated)
            },
            onSelectAllVisible = { visiblePkgs ->
                val updated = selectedPackages + visiblePkgs
                selectedPackages = updated
                LockStateManager.saveSelectedPackages(context, updated)
            },
            onClearAll = {
                selectedPackages = emptySet()
                LockStateManager.saveSelectedPackages(context, emptySet())
            },
            searchQuery = searchQuery,
            onSearchQueryChange = { searchQuery = it },
            durationMinutes = durationMinutes,
            customMinutesText = customMinutesText,
            onDurationChange = { mins, rawText ->
                durationMinutes = mins
                customMinutesText = rawText
            },
            blockSettingsAndUninstall = blockSettingsAndUninstall,
            onBlockSettingsChange = { blockSettingsAndUninstall = it },
            blockPowerMenu = blockPowerMenu,
            onBlockPowerMenuChange = { blockPowerMenu = it },
            hasAccessibility = hasAccessibility,
            hasDeviceAdmin = hasDeviceAdmin,
            hasOverlay = hasOverlay,
            hasBatteryExemption = hasBatteryExemption,
            onStartLockClick = {
                if (!hasAccessibility || !hasDeviceAdmin || !hasOverlay) {
                    Toast.makeText(
                        context,
                        "Please enable all 3 required Anti-Cheat protections first!",
                        Toast.LENGTH_LONG
                    ).show()
                } else if (selectedPackages.isEmpty()) {
                    Toast.makeText(
                        context,
                        "Select at least 1 app to block!",
                        Toast.LENGTH_SHORT
                    ).show()
                } else if (durationMinutes < 1) {
                    Toast.makeText(
                        context,
                        "Please enter a timer duration of at least 1 minute.",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    showConfirmStartDialog = true
                }
            }
        )
    }

    if (showConfirmStartDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmStartDialog = false },
            containerColor = Color(0xFF161B22),
            titleContentColor = Color.White,
            textContentColor = Color(0xFFC9D1D9),
            icon = {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = Color(0xFFEF4444),
                    modifier = Modifier.size(32.dp)
                )
            },
            title = {
                Text(
                    text = "Activate Unbreakable Lock?",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "You are locking ${selectedPackages.size} app(s) for $durationMinutes minute(s)."
                    )
                    Text(
                        "• You CANNOT open any selected app until the timer reaches 00:00.\n" +
                            "• You CANNOT uninstall or force-stop StrictLock.\n" +
                            "• Android Settings and Package Installer will be blocked.\n" +
                            "• Power Off / Restart menu will be intercepted, and hardware reboots will auto-resume the timer.",
                        fontSize = 13.sp,
                        color = Color(0xFFFCA5A5)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirmStartDialog = false
                        LockStateManager.startLockSession(
                            context = context,
                            durationMinutes = durationMinutes,
                            blockedPackages = selectedPackages,
                            blockSettingsAndUninstall = blockSettingsAndUninstall,
                            blockPowerMenu = blockPowerMenu
                        )
                        LockEnforcementService.startOrUpdate(context)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
                ) {
                    Text("Lock Now", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmStartDialog = false }) {
                    Text("Cancel", color = Color(0xFF8B949E))
                }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SetupLockScreen(
    installedApps: List<InstalledAppInfo>,
    isLoadingApps: Boolean,
    selectedPackages: Set<String>,
    onTogglePackage: (String) -> Unit,
    onSelectAllVisible: (Set<String>) -> Unit,
    onClearAll: () -> Unit,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    durationMinutes: Int,
    customMinutesText: String,
    onDurationChange: (Int, String) -> Unit,
    blockSettingsAndUninstall: Boolean,
    onBlockSettingsChange: (Boolean) -> Unit,
    blockPowerMenu: Boolean,
    onBlockPowerMenuChange: (Boolean) -> Unit,
    hasAccessibility: Boolean,
    hasDeviceAdmin: Boolean,
    hasOverlay: Boolean,
    hasBatteryExemption: Boolean,
    onStartLockClick: () -> Unit
) {
    val context = LocalContext.current
    val filteredApps = remember(installedApps, searchQuery) {
        if (searchQuery.isBlank()) {
            installedApps
        } else {
            val q = searchQuery.trim().lowercase()
            installedApps.filter {
                it.appName.lowercase().contains(q) || it.packageName.lowercase().contains(q)
            }
        }
    }

    val allRequiredGranted = hasAccessibility && hasDeviceAdmin && hasOverlay

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(Color(0x33EF4444), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            tint = Color(0xFFEF4444),
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "StrictLock Anti-Cheat Blocker",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White
                        )
                        Text(
                            text = "Select apps, set your timer, and block all bypasses",
                            fontSize = 13.sp,
                            color = Color(0xFF8B949E)
                        )
                    }
                }
            }

            // Step 1: Permissions & Anti-Cheat Setup Card
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF161B22), RoundedCornerShape(16.dp))
                        .border(
                            width = 1.dp,
                            color = if (allRequiredGranted) Color(0xFF238636) else Color(0xFFEF4444),
                            shape = RoundedCornerShape(16.dp)
                        )
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "1. Anti-Tamper System Permissions",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = Color.White
                        )
                        Text(
                            text = if (allRequiredGranted) "READY" else "ACTION NEEDED",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (allRequiredGranted) Color(0xFF4ADE80) else Color(0xFFF87171)
                        )
                    }

                    PermissionRow(
                        title = "Accessibility Service (Required)",
                        subtitle = "Blocks restricted apps, Settings, and Power Off menu",
                        isGranted = hasAccessibility,
                        onClick = {
                            runCatching {
                                context.startActivity(PermissionHelper.createAccessibilitySettingsIntent())
                            }
                        }
                    )

                    PermissionRow(
                        title = "Device Admin Protection (Required)",
                        subtitle = "Prevents uninstalling StrictLock while locked",
                        isGranted = hasDeviceAdmin,
                        onClick = {
                            runCatching {
                                context.startActivity(PermissionHelper.createDeviceAdminIntent(context))
                            }
                        }
                    )

                    PermissionRow(
                        title = "Display Over Other Apps (Required)",
                        subtitle = "Displays instant full-screen Lock Shield over blocked apps",
                        isGranted = hasOverlay,
                        onClick = {
                            runCatching {
                                context.startActivity(PermissionHelper.createOverlayPermissionIntent(context))
                            }
                        }
                    )

                    PermissionRow(
                        title = "Unrestricted Battery (Recommended)",
                        subtitle = "Prevents phone battery saver from stopping the timer",
                        isGranted = hasBatteryExemption,
                        onClick = {
                            runCatching {
                                context.startActivity(PermissionHelper.createBatteryOptimizationIntent(context))
                            }
                        }
                    )
                }
            }

            // Step 2: Timer Duration & Anti-Cheat Rules
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF161B22), RoundedCornerShape(16.dp))
                        .border(1.dp, Color(0xFF30363D), RoundedCornerShape(16.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Timer,
                            contentDescription = null,
                            tint = Color(0xFFF87171),
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "2. Lock Timer Duration (Minutes)",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = Color.White
                        )
                    }

                    val presets = listOf(15, 30, 45, 60, 120, 240, 480)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        presets.forEach { preset ->
                            val label = if (preset >= 60) "${preset / 60}h" else "${preset}m"
                            FilterChip(
                                selected = durationMinutes == preset,
                                onClick = { onDurationChange(preset, preset.toString()) },
                                label = { Text(label) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Color(0xFFEF4444),
                                    selectedLabelColor = Color.White
                                )
                            )
                        }
                    }

                    OutlinedTextField(
                        value = customMinutesText,
                        onValueChange = { text ->
                            val digits = text.filter { it.isDigit() }.take(4)
                            val parsed = digits.toIntOrNull()?.coerceIn(1, 1440) ?: 0
                            onDurationChange(parsed, digits)
                        },
                        label = { Text("Custom Duration (Minutes, max 1440)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFFEF4444),
                            unfocusedBorderColor = Color(0xFF30363D),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )

                    // Anti-Cheat toggles
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Block Settings, Force Stop & Uninstall",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "Locks Android Settings, App Info, and Clock settings",
                                color = Color(0xFF8B949E),
                                fontSize = 12.sp
                            )
                        }
                        Switch(
                            checked = blockSettingsAndUninstall,
                            onCheckedChange = onBlockSettingsChange,
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFFEF4444))
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Intercept Power Off / Restart Menu",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "Dismisses Power Menu & resumes lock automatically on reboot",
                                color = Color(0xFF8B949E),
                                fontSize = 12.sp
                            )
                        }
                        Switch(
                            checked = blockPowerMenu,
                            onCheckedChange = onBlockPowerMenuChange,
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFFEF4444))
                        )
                    }
                }
            }

            // Step 3: App Picker Header + Search
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "3. Choose Apps to Block (${selectedPackages.size} selected)",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = Color.White
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = {
                                    onSelectAllVisible(filteredApps.map { it.packageName }.toSet())
                                }
                            ) {
                                Text("Select All", color = Color(0xFFF87171), fontSize = 12.sp)
                            }
                            if (selectedPackages.isNotEmpty()) {
                                TextButton(onClick = onClearAll) {
                                    Text("Clear", color = Color(0xFF8B949E), fontSize = 12.sp)
                                }
                            }
                        }
                    }

                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = onSearchQueryChange,
                        placeholder = { Text("Search installed apps...") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null,
                                tint = Color(0xFF8B949E)
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFFEF4444),
                            unfocusedBorderColor = Color(0xFF30363D),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )
                }
            }

            if (isLoadingApps) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = Color(0xFFEF4444))
                    }
                }
            } else {
                items(
                    items = filteredApps,
                    key = { it.packageName }
                ) { app ->
                    val isSelected = app.packageName in selectedPackages
                    AppSelectionRow(
                        app = app,
                        isSelected = isSelected,
                        onToggle = { onTogglePackage(app.packageName) }
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(12.dp))
            }
        }

        // Sticky Bottom Start Button
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF161B22))
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Button(
                onClick = onStartLockClick,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = Color.White
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Start Strict Lock (${selectedPackages.size} Apps • ${durationMinutes}m)",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    subtitle: String,
    isGranted: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0D1117), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = if (isGranted) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = null,
                tint = if (isGranted) Color(0xFF4ADE80) else Color(0xFFFBBF24),
                modifier = Modifier.size(22.dp)
            )
            Column {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = subtitle,
                    color = Color(0xFF8B949E),
                    fontSize = 11.sp
                )
            }
        }

        if (!isGranted) {
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(
                onClick = onClick,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Enable", color = Color(0xFFF87171), fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun AppSelectionRow(
    app: InstalledAppInfo,
    isSelected: Boolean,
    onToggle: () -> Unit
) {
    val iconBitmap = remember(app.packageName) {
        app.icon?.toImageBitmap()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) Color(0x22EF4444) else Color(0xFF161B22))
            .border(
                width = 1.dp,
                color = if (isSelected) Color(0xFFEF4444) else Color(0xFF21262D),
                shape = RoundedCornerShape(12.dp)
            )
            .clickable { onToggle() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (iconBitmap != null) {
            Image(
                bitmap = iconBitmap,
                contentDescription = app.appName,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
            )
        } else {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(Color(0xFF30363D), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = app.appName.take(1).uppercase(),
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.appName,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = app.packageName,
                color = Color(0xFF8B949E),
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Checkbox(
            checked = isSelected,
            onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(
                checkedColor = Color(0xFFEF4444),
                uncheckedColor = Color(0xFF8B949E)
            )
        )
    }
}

@Composable
private fun ActiveLockDashboard(
    lockState: LockSessionState,
    installedApps: List<InstalledAppInfo>
) {
    val progress = if (lockState.totalDurationMs > 0L) {
        (lockState.remainingMs.toFloat() / lockState.totalDurationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    val blockedAppModels = remember(installedApps, lockState.blockedPackages) {
        installedApps.filter { it.packageName in lockState.blockedPackages }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .background(Color(0x33EF4444), RoundedCornerShape(20.dp))
                    .border(1.dp, Color(0xFFEF4444), RoundedCornerShape(20.dp))
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "STRICT LOCK SESSION IN PROGRESS",
                    color = Color(0xFFFCA5A5),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF161B22), RoundedCornerShape(20.dp))
                    .border(1.dp, Color(0xFF30363D), RoundedCornerShape(20.dp))
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "TIME REMAINING",
                    color = Color(0xFF8B949E),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = OverlayShieldManager.formatDuration(lockState.remainingMs),
                    color = Color.White,
                    fontSize = 48.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.ExtraBold
                )
                Spacer(modifier = Modifier.height(16.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = Color(0xFFEF4444),
                    trackColor = Color(0xFF30363D)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "No cancel button is available. Protected by Hardware Monotonic Clock, Device Admin, and Power Menu Interception.",
                    color = Color(0xFF8B949E),
                    fontSize = 12.sp
                )
            }
        }

        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF161B22), RoundedCornerShape(16.dp))
                    .border(1.dp, Color(0xFF30363D), RoundedCornerShape(16.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "Anti-Cheat Security Log",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Text(
                    text = "Blocked Open / Tamper Attempts: ${lockState.tamperAttempts}",
                    color = Color(0xFFFBBF24),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                if (!lockState.lastBlockedReason.isNullOrBlank()) {
                    Text(
                        text = "Last intercepted: ${lockState.lastBlockedReason}",
                        color = Color(0xFFF87171),
                        fontSize = 12.sp
                    )
                }
            }
        }

        item {
            Text(
                text = "Locked Apps (${lockState.blockedPackages.size})",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                modifier = Modifier.fillMaxWidth()
            )
        }

        items(
            items = blockedAppModels,
            key = { it.packageName }
        ) { app ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF161B22), RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = app.appName,
                        color = Color.White,
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp
                    )
                    Text(
                        text = app.packageName,
                        color = Color(0xFF8B949E),
                        fontSize = 11.sp
                    )
                }
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = "Locked",
                    tint = Color(0xFFEF4444),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

private fun Drawable.toImageBitmap(): ImageBitmap? {
    return runCatching {
        if (this is BitmapDrawable && bitmap != null) {
            return bitmap.asImageBitmap()
        }
        val width = if (intrinsicWidth > 0) intrinsicWidth else 96
        val height = if (intrinsicHeight > 0) intrinsicHeight else 96
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        setBounds(0, 0, canvas.width, canvas.height)
        draw(canvas)
        bitmap.asImageBitmap()
    }.getOrNull()
}
