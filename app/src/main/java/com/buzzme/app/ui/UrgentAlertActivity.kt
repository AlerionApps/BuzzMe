package com.buzzme.app.ui

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.buzzme.app.service.AlertEngine
import com.buzzme.app.ui.theme.BuzzMeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class UrgentAlertActivity : ComponentActivity() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Ensure activity shows over lock screen and turns the screen on
        setupLockScreenBypass()

        val senderKey = intent.getStringExtra("sender_key") ?: ""
        val senderLabel = intent.getStringExtra("sender_label") ?: "Family Member"
        val buzzMessage = intent.getStringExtra("buzz_message") ?: "Buzz!"
        val urgencyLevel = intent.getIntExtra("urgency_level", 2)

        val app = application as? com.buzzme.app.BuzzMeApp
        val repo = app?.deviceRepository

        setContent {
            val themeMode by (repo?.themeMode ?: kotlinx.coroutines.flow.MutableStateFlow(com.buzzme.app.ui.theme.ThemeMode.DARK)).collectAsState()
            BuzzMeTheme(themeMode = themeMode) {
                UrgentAlertContent(
                    senderLabel = senderLabel,
                    buzzMessage = buzzMessage,
                    urgencyLevel = urgencyLevel,
                    onAcknowledge = {
                        handleAcknowledge(senderKey, senderLabel)
                    },
                    onSilence = {
                        handleSilence()
                    }
                )
            }
        }
    }

    private fun setupLockScreenBypass() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            keyguardManager?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        // Acquire a temporary wake lock to ensure the screen lights up from deep sleep
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = powerManager?.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "buzzme:urgent_alert_screen_on"
            )?.apply {
                acquire(10_000L)
            }
        } catch (e: Exception) {
            android.util.Log.w("BuzzMe", "Could not acquire wake lock", e)
        }
    }

    private fun handleSilence() {
        AlertEngine.stopVibration(this)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(AlertEngine.NOTIFICATION_ID_ALERT)
        finish()
    }

    private fun handleAcknowledge(senderKey: String, senderLabel: String) {
        AlertEngine.stopVibration(this)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(AlertEngine.NOTIFICATION_ID_ALERT)

        lifecycleScope.launch {
            val sent = withContext(Dispatchers.IO) {
                AlertEngine.sendAcknowledgementBuzz(this@UrgentAlertActivity, senderKey, senderLabel)
            }
            if (sent) {
                Toast.makeText(this@UrgentAlertActivity, "Sent 'I'm on my way!' to $senderLabel", Toast.LENGTH_SHORT).show()
            }
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            // Ignore
        }
    }
}

@Composable
fun UrgentAlertContent(
    senderLabel: String,
    buzzMessage: String,
    urgencyLevel: Int,
    onAcknowledge: () -> Unit,
    onSilence: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val (badgeText, badgeColor, glowColor) = when (urgencyLevel) {
        1 -> Triple("NUDGE", Color(0xFF38BDF8), Color(0xFF0284C7))
        2 -> Triple("ALERT", Color(0xFFF59E0B), Color(0xFFD97706))
        else -> Triple("🚨 EMERGENCY BUZZ", Color(0xFFEF4444), Color(0xFFDC2626))
    }

    val hasCustomNote = buzzMessage.isNotBlank() &&
            buzzMessage != "Buzz!" &&
            !buzzMessage.startsWith("Buzz [")

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF0F172A),
                        Color(0xFF0B0F19),
                        Color(0xFF020617)
                    )
                )
            )
            .safeDrawingPadding()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Urgency Pill
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = badgeColor.copy(alpha = 0.2f),
                border = androidx.compose.foundation.BorderStroke(1.dp, badgeColor.copy(alpha = 0.6f)),
                modifier = Modifier.padding(bottom = 24.dp)
            ) {
                Text(
                    text = badgeText,
                    color = badgeColor,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    letterSpacing = 1.5.sp
                )
            }

            // Pulsing Alarm Icon
            Box(
                modifier = Modifier
                    .size(130.dp)
                    .scale(pulseScale)
                    .clip(CircleShape)
                    .background(glowColor.copy(alpha = 0.25f))
                    .border(2.dp, glowColor.copy(alpha = 0.8f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(90.dp)
                        .clip(CircleShape)
                        .background(glowColor),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.NotificationsActive,
                        contentDescription = "Urgent Notification",
                        tint = Color.White,
                        modifier = Modifier.size(52.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // Sender Name
            Text(
                text = senderLabel,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center
            )

            Text(
                text = "is buzzing you urgently!",
                style = MaterialTheme.typography.bodyLarge,
                color = Color(0xFF94A3B8),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp)
            )

            // Optional Note / Message Card
            if (hasCustomNote) {
                Spacer(modifier = Modifier.height(20.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(0.92f),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "\"$buzzMessage\"",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFF1F5F9),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(40.dp))

            // Primary Action: "I'm on my way!"
            Button(
                onClick = onAcknowledge,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = Color.White
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "I'm on my way!",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Secondary Action: "Silence"
            OutlinedButton(
                onClick = onSilence,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF94A3B8)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155))
            ) {
                Icon(
                    imageVector = Icons.Default.VolumeOff,
                    contentDescription = null,
                    tint = Color(0xFF94A3B8)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Silence",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
