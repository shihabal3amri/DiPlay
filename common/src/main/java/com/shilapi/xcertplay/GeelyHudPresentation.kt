package com.shilapi.xcertplay

import android.app.Presentation
import android.content.Context
import android.view.Display
import android.view.ViewGroup
import android.widget.FrameLayout
import com.shilapi.xcertplay.hud.ClusterTurnGuidance

/** Renders [GeelyHudGuidanceView] on a secondary display. */
internal class GeelyHudPresentation(
    context: Context,
    display: Display,
    scalePercent: Int,
) : Presentation(context, display) {
    private var guidanceView: GeelyHudGuidanceView? = null

    init {
        val rootView = FrameLayout(context)
        rootView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        val view = GeelyHudGuidanceView(context, scalePercent)
        view.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        rootView.addView(view)
        guidanceView = view
        setContentView(rootView)
    }

    fun setGuidance(guidance: ClusterTurnGuidance?) {
        guidanceView?.setGuidance(guidance)
    }

    fun setScalePercent(percent: Int) {
        guidanceView?.setScalePercent(percent)
    }
}
