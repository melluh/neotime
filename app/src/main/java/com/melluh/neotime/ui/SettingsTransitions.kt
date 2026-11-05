package com.melluh.neotime.ui

import android.graphics.Path
import android.view.animation.PathInterpolator
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigationevent.NavigationEvent
import kotlin.math.sign

/*
 * The transition between the app and its settings and course pages, and between those pages.
 *
 * Tapped navigation uses the values of the platform's own activity transition
 * (`res/anim/activity_{open,close}_{enter,exit}.xml`), so these pages move like a separate
 * activity in a first-party app would. Both pages shift by a short, fixed distance; only the
 * one on top fades, over a few frames, and the one below stays opaque the whole time.
 */

private val SETTINGS_ROUTES = setOf("settings", "privacy", "about", "licenses", "picker", "picker/add", "picker/course/{id}")

private const val DURATION_MS = 450
private const val ENTER_FADE_DELAY_MS = 50
private const val EXIT_FADE_DELAY_MS = 35
private const val FADE_MS = 83

/** How far a back swipe shrinks the page, about what the platform uses between activities. */
private const val PREDICTIVE_BACK_SCALE = 0.9f

val SettingsSlideDistance = 96.dp

/** `@interpolator/fast_out_extra_slow_in`: two joined curves, so no single cubic bezier fits. */
private val FastOutExtraSlowIn: Easing = PathInterpolator(
    Path().apply {
        moveTo(0f, 0f)
        cubicTo(0.05f, 0f, 0.133333f, 0.06f, 0.166666f, 0.4f)
        cubicTo(0.208333f, 0.82f, 0.25f, 1f, 1f, 1f)
    },
).let { interpolator -> Easing(interpolator::getInterpolation) }

private val slideSpec = tween<IntOffset>(DURATION_MS, easing = FastOutExtraSlowIn)

/** The system's back progress already follows the finger, so nothing is eased on top of it. */
private val gestureSlideSpec = tween<IntOffset>(DURATION_MS, easing = LinearEasing)

private val NavBackStackEntry.isSettings: Boolean
    get() = destination.route in SETTINGS_ROUTES

fun AnimatedContentTransitionScope<NavBackStackEntry>.settingsEnter(
    slidePx: Int,
    otherwise: () -> EnterTransition,
): EnterTransition = if (targetState.isSettings) {
    slideIntoContainer(SlideDirection.Start, slideSpec) { it.sign * slidePx } +
        fadeIn(tween(FADE_MS, ENTER_FADE_DELAY_MS, LinearEasing))
} else {
    otherwise()
}

fun AnimatedContentTransitionScope<NavBackStackEntry>.settingsExit(
    slidePx: Int,
    otherwise: () -> ExitTransition,
): ExitTransition = if (targetState.isSettings) {
    slideOutOfContainer(SlideDirection.Start, slideSpec) { it.sign * slidePx }
} else {
    otherwise()
}

fun AnimatedContentTransitionScope<NavBackStackEntry>.settingsPopEnter(
    slidePx: Int,
    otherwise: () -> EnterTransition,
): EnterTransition = if (initialState.isSettings) {
    slideIntoContainer(SlideDirection.End, slideSpec) { it.sign * slidePx }
} else {
    otherwise()
}

fun AnimatedContentTransitionScope<NavBackStackEntry>.settingsPopExit(
    slidePx: Int,
    otherwise: () -> ExitTransition,
): ExitTransition = if (initialState.isSettings) {
    slideOutOfContainer(SlideDirection.End, slideSpec) { it.sign * slidePx } +
        fadeOut(tween(FADE_MS, EXIT_FADE_DELAY_MS, LinearEasing))
} else {
    otherwise()
}

fun AnimatedContentTransitionScope<NavBackStackEntry>.settingsPredictivePopEnter(
    slidePx: Int,
    otherwise: () -> EnterTransition,
): EnterTransition = if (initialState.isSettings) {
    slideIntoContainer(SlideDirection.End, gestureSlideSpec) { it.sign * slidePx }
} else {
    otherwise()
}

/** Shrinks towards the side away from the swipe, and only disappears at the end of the gesture. */
fun AnimatedContentTransitionScope<NavBackStackEntry>.settingsPredictivePopExit(
    swipeEdge: Int,
    otherwise: () -> ExitTransition,
): ExitTransition = if (initialState.isSettings) {
    val pivotX = if (swipeEdge == NavigationEvent.EDGE_RIGHT) 0f else 1f
    scaleOut(
        tween(DURATION_MS, easing = LinearEasing),
        targetScale = PREDICTIVE_BACK_SCALE,
        transformOrigin = TransformOrigin(pivotX, 0.5f),
    ) + fadeOut(tween(FADE_MS, DURATION_MS - FADE_MS, LinearEasing))
} else {
    otherwise()
}
