package dev.saifmukhtar.antimatter.feature.terminal

import android.graphics.Color as AndroidColor
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import dev.saifmukhtar.antimatter.core.ui.glowBorder

// ─── Termux Authentic Color Palette ───────────────────────────────────────────
private val TermuxBlack      = Color(0xFF000000)   // terminal background
private val TermuxKeys       = Color(0xFF1C1C1C)   // extra keys bar background
private val TermuxKeysBorder = Color(0xFF2E2E2E)   // subtle separator / key border
private val TermuxGreen      = Color(0xFF73D216)   // Termux cursor / active accent
private val TermuxGreenDim   = Color(0x3373D216)   // active key highlight (semi-transparent green)
private val TermuxText       = Color(0xFFD4D4D4)   // normal key labels
private val TermuxTextActive = Color(0xFF73D216)   // active key label text
private val TermuxKeyShape   = RoundedCornerShape(4.dp)

@Composable
fun TerminalScreen(
    viewModel: TerminalViewModel = hiltViewModel()
) {
    val isConnected by viewModel.isConnected.collectAsState()

    @Suppress("UNUSED_VARIABLE")
    val context = LocalContext.current

    @Suppress("UNUSED_VARIABLE")
    val lifecycleOwner = LocalLifecycleOwner.current

    var terminalViewRef by remember { mutableStateOf<TerminalView?>(null) }

    LaunchedEffect(terminalViewRef) {
        if (terminalViewRef != null) {
            viewModel.redrawEvent.collect {
                terminalViewRef?.onScreenUpdated()
            }
        }
    }

    // Modifier key toggle states — consumed by TerminalViewClient callbacks
    val ctrlActive = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val altActive  = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    
    // For recomposing the UI based on state changes
    var ctrlUiState by remember { mutableStateOf(false) }
    var altUiState  by remember { mutableStateOf(false) }

    Scaffold(
        modifier       = Modifier.fillMaxSize(),
        containerColor = TermuxBlack
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(TermuxBlack)
                .imePadding()
        ) {
            // ── Terminal Emulator View ──────────────────────────────────────────
            AndroidView(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(TermuxBlack),
                factory = { ctx ->
                    val terminalView = TerminalView(ctx, null)
                    terminalViewRef = terminalView

                    terminalView.layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )

                    terminalView.setBackgroundColor(AndroidColor.BLACK)
                    terminalView.isFocusable            = true
                    terminalView.isFocusableInTouchMode = true

                    // 40sp — Maximum default zoom as requested by user
                    var currentSizeSp = 40f
                    terminalView.setTextSize(currentSizeSp.toInt())

                    terminalView.attachSession(viewModel.terminalSession)

                    terminalView.setTerminalViewClient(
                        AntimatterTerminalViewClient(
                            context = ctx,
                            terminalView = terminalView,
                            viewModel = viewModel,
                            ctrlActive = ctrlActive,
                            altActive = altActive
                        )
                    )

                    terminalView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                        val emulator = viewModel.terminalSession.emulator
                        if (emulator != null && emulator.mColumns > 0 && emulator.mRows > 0) {
                            viewModel.onResize(emulator.mColumns, emulator.mRows)
                        }
                    }
                    terminalView
                }
            )

            // ── Extra Keys Bar ─────────────────────────────────────────────────
            ExtraKeysBar(
                ctrlActive  = ctrlUiState,
                altActive   = altUiState,
                onCtrlClick = { 
                    ctrlUiState = !ctrlUiState
                    ctrlActive.set(ctrlUiState)
                },
                onAltClick  = { 
                    altUiState = !altUiState
                    altActive.set(altUiState)
                },
                onKey       = { keyCode ->
                    terminalViewRef?.onKeyDown(keyCode, android.view.KeyEvent(0, keyCode))
                    terminalViewRef?.onKeyUp(keyCode,   android.view.KeyEvent(1, keyCode))
                }
            )
        }
    }
}

// ─── Extra Keys Bar ────────────────────────────────────────────────────────────
@Composable
private fun ExtraKeysBar(
    ctrlActive:  Boolean,
    altActive:   Boolean,
    onCtrlClick: () -> Unit,
    onAltClick:  () -> Unit,
    onKey:       (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp) // Slightly taller for 3D keys
            .background(TermuxKeys)
            .border(width = 0.5.dp, color = TermuxKeysBorder)
            .padding(horizontal = 4.dp)
            .horizontalScroll(rememberScrollState()),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        TermuxKey(label = "ESC",  active = false,      onClick = { onKey(111) })
        TermuxKey(label = "/",    active = false,      onClick = { onKey(76)  })
        TermuxKey(label = "-",    active = false,      onClick = { onKey(69)  })
        TermuxKey(label = "HOME", active = false,      onClick = { onKey(122) })
        TermuxKey(label = "↑",    active = false,      onClick = { onKey(19)  })
        TermuxKey(label = "END",  active = false,      onClick = { onKey(123) })
        TermuxKey(label = "PGUP", active = false,      onClick = { onKey(92)  })
        TermuxKey(label = "CTRL", active = ctrlActive, onClick = onCtrlClick  )
        TermuxKey(label = "ALT",  active = altActive,  onClick = onAltClick   )
        TermuxKey(label = "TAB",  active = false,      onClick = { onKey(61)  })
        TermuxKey(label = "←",    active = false,      onClick = { onKey(21)  })
        TermuxKey(label = "↓",    active = false,      onClick = { onKey(20)  })
        TermuxKey(label = "→",    active = false,      onClick = { onKey(22)  })
        TermuxKey(label = "PGDN", active = false,      onClick = { onKey(93)  })
    }
}

// ─── Single Key Chip ───────────────────────────────────────────────────────────
@Composable
private fun TermuxKey(
    label:   String,
    active:  Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    // 3D effect: shift down when pressed
    val offsetY by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (isPressed) 2.dp else 0.dp,
        label = "key_press"
    )

    Box(
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .size(width = 44.dp, height = 36.dp)
            .offset(y = offsetY)
            .clip(TermuxKeyShape)
            .background(if (active) TermuxGreenDim else Color(0xFF252525))
            .border(
                width = if (active) 1.5.dp else 1.dp,
                color = if (active) TermuxGreen else TermuxKeysBorder,
                shape = TermuxKeyShape
            )
            .then(
                if (active) Modifier.glowBorder(TermuxGreen.copy(alpha = 0.5f), TermuxKeyShape, 4.dp)
                else Modifier
            )
            // Bottom shadow for 3D effect when not pressed
            .then(
                if (!isPressed) Modifier.border(width = 2.dp, color = Color(0xFF151515), shape = TermuxKeyShape)
                else Modifier
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text          = label,
            color         = if (active) TermuxTextActive else TermuxText,
            fontSize      = 12.sp,
            fontWeight    = if (active) FontWeight.Bold else FontWeight.Medium,
            fontFamily    = FontFamily.Monospace,
            letterSpacing = 0.5.sp
        )
    }
}
