package com.portoperatingsystem.tv.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.portoperatingsystem.tv.AppGraph
import com.portoperatingsystem.tv.PortOsApplication
import com.portoperatingsystem.tv.R
import com.portoperatingsystem.tv.auth.PairingState
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The only screen: shows the pairing code until the TV is connected, then its status and setup diagnostics. */
internal class MainActivity : Activity() {
    private val scope: CoroutineScope = MainScope()
    private val graph: AppGraph get() = (application as PortOsApplication).graph

    private lateinit var title: TextView
    private lateinit var detail: TextView
    private lateinit var code: TextView
    private lateinit var qr: ImageView
    private lateinit var action: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(layout())
    }

    override fun onResume() {
        super.onResume()
        when {
            !graph.session.isPaired -> startPairing()
            graph.session.needsPairing.value -> showPairAgain()
            else -> showPaired()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startPairing() {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { graph.session.pair { state -> scope.launch { render(state) } } }
            } catch (error: IOException) {
                render(PairingState.Failed(error.message ?: error.javaClass.simpleName))
            } catch (error: IllegalStateException) {
                render(PairingState.Failed(error.message ?: error.javaClass.simpleName))
            }
        }
    }

    private fun render(state: PairingState) {
        when (state) {
            PairingState.Starting -> show(getString(R.string.pairing_title), getString(R.string.pairing_waiting))
            is PairingState.AwaitingApproval -> {
                val auth = state.authorization
                show(getString(R.string.pairing_title), getString(R.string.pairing_instructions, auth.verificationUri))
                code.text = auth.userCode
                code.visibility = View.VISIBLE
                qr.setImageBitmap(qrCode(auth.verificationUriComplete ?: auth.verificationUri))
                qr.visibility = View.VISIBLE
            }
            PairingState.Paired -> {
                graph.scheduleHeartbeat()
                graph.requestReport()
                graph.connectGateway()
                showPaired()
            }
            is PairingState.Failed -> {
                Log.w(TAG, "pairing failed: ${state.reason}")
                show(getString(R.string.pairing_title), getString(R.string.error_generic, state.reason))
                action.text = getString(R.string.retry)
                action.setOnClickListener { startPairing() }
                action.visibility = View.VISIBLE
                action.requestFocus()
            }
        }
    }

    private fun showPairAgain() {
        show(getString(R.string.pair_again_title), getString(R.string.pair_again_detail))
        action.text = getString(R.string.pair_again)
        action.setOnClickListener { startPairing() }
        action.visibility = View.VISIBLE
        action.requestFocus()
    }

    private fun showPaired() {
        val snapshot = graph.reporter.snapshot()
        val status = when {
            !snapshot.backgroundLaunchAllowed -> getString(R.string.status_missing_launch)
            !snapshot.mediaAccessAllowed -> getString(R.string.status_missing_listener)
            !snapshot.accessibilityEnabled -> getString(R.string.status_missing_accessibility)
            else -> getString(R.string.status_ok)
        }
        show(getString(R.string.paired_title), getString(R.string.paired_device, graph.deviceName) + "\n" + status)
    }

    private fun show(heading: String, body: String) {
        title.text = heading
        detail.text = body
        code.visibility = View.GONE
        qr.visibility = View.GONE
        action.visibility = View.GONE
    }

    private fun layout(): View {
        title = text(TITLE_SP)
        detail = text(BODY_SP)
        code = text(CODE_SP).apply { letterSpacing = CODE_LETTER_SPACING }
        qr = ImageView(this)
        action = Button(this)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(getColor(R.color.background))
            listOf(title, detail, code, qr, action).forEach { child ->
                addView(child, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            }
        }
    }

    private fun text(sizeSp: Float) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(getColor(R.color.text))
        gravity = Gravity.CENTER
        setPadding(0, PADDING_PX, 0, PADDING_PX)
    }

    private fun qrCode(content: String): Bitmap {
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, QR_PX, QR_PX)
        val pixels = IntArray(QR_PX * QR_PX) { index ->
            if (matrix[index % QR_PX, index / QR_PX]) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(pixels, QR_PX, QR_PX, Bitmap.Config.ARGB_8888)
    }

    private companion object {
        const val TAG = "PortOsPairing"
        const val TITLE_SP = 32f
        const val BODY_SP = 20f
        const val CODE_SP = 48f
        const val CODE_LETTER_SPACING = 0.2f
        const val PADDING_PX = 12
        const val QR_PX = 320
    }
}
