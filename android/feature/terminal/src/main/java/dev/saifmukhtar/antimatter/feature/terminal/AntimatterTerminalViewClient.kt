package dev.saifmukhtar.antimatter.feature.terminal

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.util.concurrent.atomic.AtomicBoolean

class AntimatterTerminalViewClient(
    private val context: Context,
    private val terminalView: TerminalView,
    private val viewModel: TerminalViewModel,
    private val ctrlActive: AtomicBoolean,
    private val altActive: AtomicBoolean
) : TerminalViewClient {

    private var currentSizeSp = 20f

    override fun onScale(scale: Float): Float {
        if (scale < 0.9f || scale > 1.1f) {
            val increase = scale > 1.0f
            currentSizeSp = (if (increase) currentSizeSp + 2f else currentSizeSp - 2f).coerceIn(8f, 36f)
            terminalView.setTextSize(currentSizeSp.toInt())
            return 1.0f
        }
        return scale
    }

    override fun onSingleTapUp(e: MotionEvent?) {
        terminalView.post {
            terminalView.requestFocus()
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false

    override fun shouldEnforceCharBasedInput(): Boolean = true

    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false

    override fun isTerminalViewSelected(): Boolean = true

    override fun copyModeChanged(copyMode: Boolean) = Unit

    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean {
        if (e != null && session != null && e.isCtrlPressed && e.isAltPressed) {
            val unicodeChar = e.getUnicodeChar(0)
            if (unicodeChar == '+'.code || e.getUnicodeChar(KeyEvent.META_SHIFT_ON) == '+'.code) {
                onScale(1.2f)
                return true
            } else if (unicodeChar == '-'.code) {
                onScale(0.8f)
                return true
            } else if (unicodeChar == 'v'.code) {
                viewModel.onPasteTextFromClipboard(session)
                return true
            } else if (unicodeChar == 'm'.code) {
                terminalView.showContextMenu()
                return true
            }
        }
        return false
    }

    override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false

    override fun readControlKey(): Boolean {
        return ctrlActive.getAndSet(false)
    }

    override fun readAltKey(): Boolean {
        return altActive.getAndSet(false)
    }

    override fun readShiftKey(): Boolean = false
    override fun readFnKey(): Boolean = false

    override fun onLongPress(event: MotionEvent?): Boolean {
        if (event != null) {
            terminalView.showContextMenu()
            return true
        }
        return false
    }

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean {
        if (session == null) return false
        
        if (ctrlDown) {
            // Let the TerminalEmulator handle the ctrl modifier itself if we return false!
            // But we can intercept special shortcuts here if needed.
            return false
        }
        return false
    }

    override fun onEmulatorSet() {
        val emulator = viewModel.terminalSession.emulator
        if (emulator != null) {
            viewModel.onResize(emulator.mColumns, emulator.mRows)
            terminalView.setTerminalCursorBlinkerState(true, true)
        }
    }

    override fun logError(tag: String?, message: String?) {}
    override fun logWarn(tag: String?, message: String?) {}
    override fun logInfo(tag: String?, message: String?) {}
    override fun logDebug(tag: String?, message: String?) {}
    override fun logVerbose(tag: String?, message: String?) {}
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
    override fun logStackTrace(tag: String?, e: Exception?) {}
}
