package org.ntust.app.tigerduck.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import dagger.hilt.android.EntryPointAccessors
import org.ntust.app.tigerduck.AppLink
import org.ntust.app.tigerduck.R
import org.ntust.app.tigerduck.activityIntent

/**
 * 1x1 home-screen widget that deep-links straight to the library QR page.
 * The tap opens `tigerduck://library`, which MainActivity turns into the
 * [ROUTE_SENTINEL] route; MainNavigation resolves that at launch time so an
 * intervening feature-disable toggle always routes the user to Settings with
 * the enable-prompt instead of a dead Library screen.
 */
class LibraryShortcutWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val prefs = EntryPointAccessors
            .fromApplication(context.applicationContext, WidgetThemeEntryPoint::class.java)
            .appPreferences()
        val tapAction = actionStartActivity(tapIntent(context))
        provideContent {
            // Same pattern as BaseClassTableWidget: provideGlance runs once
            // per Glance session, so theme/accent must be derived inside
            // provideContent, keyed by the TickKey state WidgetUpdater bumps —
            // otherwise theme and accent changes are never reflected until
            // the widget is removed and re-added.
            @Suppress("UNUSED_VARIABLE")
            val tick = currentState(WidgetState.TickKey) ?: 0L
            val isDark = resolveWidgetIsDark(prefs, context)
            val accent = resolveWidgetAccentColor(prefs, isDark)
            LibraryShortcutContent(isDark = isDark, accent = accent, onTap = tapAction)
        }
    }

    companion object {
        /** The route `tigerduck://library` opens, which MainNavigation resolves on arrival. */
        const val ROUTE_SENTINEL = "libraryShortcut"

        /**
         * A tap opens `tigerduck://library`. The link goes as the intent's
         * data, which Glance leaves alone: an intent with none is given a
         * `glance-action:` URI of Glance's own.
         */
        internal fun tapIntent(context: Context): Intent =
            AppLink.Library.activityIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

class LibraryShortcutWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = LibraryShortcutWidget()
}

@Composable
private fun LibraryShortcutContent(
    isDark: Boolean,
    accent: Color,
    onTap: Action,
) {
    val background = if (isDark) Color(0xFF1C1C1E) else Color.White
    val onSurface = if (isDark) Color.White else Color(0xFF1C1C1E)
    val context = androidx.glance.LocalContext.current
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .padding(4.dp)
            .clickable(onTap),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(ColorProvider(background))
                .cornerRadius(18.dp)
                .padding(6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = GlanceModifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = GlanceModifier
                        .size(32.dp)
                        .background(ColorProvider(accent))
                        .cornerRadius(10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        provider = ImageProvider(R.drawable.widget_library_icon),
                        contentDescription = null,
                        modifier = GlanceModifier.size(20.dp),
                        colorFilter = ColorFilter.tint(ColorProvider(Color.White)),
                    )
                }
                Box(modifier = GlanceModifier.height(4.dp)) {}
                Text(
                    text = context.getString(R.string.widget_library_shortcut_title),
                    style = TextStyle(
                        color = ColorProvider(onSurface),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                    ),
                )
            }
        }
    }
}
