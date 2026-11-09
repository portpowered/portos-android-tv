package com.portoperatingsystem.tv.surface

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.portoperatingsystem.tv.PortOsApplication
import com.portoperatingsystem.tv.surface.render.LocalImageSource
import com.portoperatingsystem.tv.surface.render.PortosTheme
import com.portoperatingsystem.tv.surface.render.SurfaceScreen

/**
 * The screen an agent draws on. It renders the controller's surface, takes no focus (there is nothing to select) and
 * is started by the app through the background-launch permission. Back and Home send it behind the TV's launcher but
 * leave the surface held, so the next `show-surface` or `get-surface` still finds it, with `visible` false.
 */
internal class SurfaceActivity : ComponentActivity() {
    private val graph get() = (application as PortOsApplication).graph

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    moveTaskToBack(true)
                }
            }
        )
        val controller = graph.surface
        setContent {
            val state by controller.state.collectAsState()
            LaunchedEffect(state == null) { if (state == null) finish() }
            PortosTheme(panelWidthPx = resources.displayMetrics.widthPixels) {
                CompositionLocalProvider(LocalImageSource provides graph.images) {
                    SurfaceScreen(state?.model)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        graph.surface.setVisible(true)
    }

    override fun onStop() {
        graph.surface.setVisible(false)
        super.onStop()
    }

    companion object {
        /** The intent that brings the surface to the foreground from the background. */
        fun intent(context: Context): Intent = Intent(context, SurfaceActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
