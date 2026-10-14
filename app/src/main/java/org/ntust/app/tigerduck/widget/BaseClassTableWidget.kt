package org.ntust.app.tigerduck.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Box
import androidx.glance.layout.fillMaxSize
import androidx.glance.unit.ColorProvider
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import org.ntust.app.tigerduck.MainActivity
import org.ntust.app.tigerduck.data.preferences.AppLanguageManager
import org.ntust.app.tigerduck.data.preferences.AppPreferences
import org.ntust.app.tigerduck.widget.content.NextClassContent
import org.ntust.app.tigerduck.widget.content.TodayListContent
import org.ntust.app.tigerduck.widget.content.WeekGridContent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetThemeEntryPoint {
    fun appPreferences(): AppPreferences
}

abstract class BaseClassTableWidget(
    private val layout: WidgetLayout,
    sizeMode: SizeMode = SizeMode.Exact,
    /** Where the settings come from: Hilt's, except in a test, which has no Hilt. */
    private val preferencesOf: (Context) -> AppPreferences = ::widgetPreferences,
    /** What the widget draws: [WidgetDataLoader.load], except in a test. */
    private val loadState: suspend (Context) -> WidgetState = WidgetDataLoader::load,
) : GlanceAppWidget() {

    // SizeMode.Exact lets layouts read LocalSize.current (used by Week and
    // NextClass for responsive variants). Today doesn't read LocalSize and
    // switches to SizeMode.Single so RemoteViews fill match_parent — works
    // around launchers (e.g. Motorola g34 stock) that report a smaller
    // OPTION_APPWIDGET_MIN_HEIGHT than the actual host cell allocation.
    override val sizeMode: SizeMode = sizeMode

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val prefs = preferencesOf(context)
        val tapIntent = Intent(context, MainActivity::class.java)
            .putExtra("start_route", "classTable")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val tapAction = actionStartActivity(tapIntent)
        provideContent {
            // Reactive tick read from Glance preferences — bumping it from
            // WidgetUpdater forces this composition to re-run, which in turn
            // re-launches the loader below AND re-reads theme/accent prefs.
            // provideGlance itself only runs once per Glance session, so any
            // prefs-derived state has to live inside provideContent.
            val tick = currentState(WidgetState.TickKey) ?: 0L
            val isDark = resolveWidgetIsDark(prefs, context)
            val accent = resolveWidgetAccentColor(prefs, isDark)
            val colors = (if (isDark) WidgetTheme.Dark else WidgetTheme.Light)
                .copy(highlight = accent)

            var state by remember { mutableStateOf<WidgetState?>(null) }
            LaunchedEffect(tick) {
                state = loadState(context)
            }
            val s = state
            if (s == null) {
                Box(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .background(ColorProvider(colors.background)),
                ) {}
            } else {
                // Read per tick, like the theme, so a language switch
                // redraws in the new language.
                CompositionLocalProvider(LocalContext provides widgetContext(context, prefs.appLanguage)) {
                    when (layout) {
                        WidgetLayout.Week -> WeekGridContent(s, colors, tapAction)
                        WidgetLayout.Today -> TodayListContent(s, colors, tapAction)
                        WidgetLayout.NextClass -> NextClassContent(s, colors, tapAction)
                    }
                }
            }
        }
    }

}

/** The app's settings, as every widget reads them. */
internal fun widgetPreferences(context: Context): AppPreferences =
    EntryPointAccessors.fromApplication(
        context.applicationContext,
        WidgetThemeEntryPoint::class.java,
    ).appPreferences()

/**
 * [context] in the app's own language, for the strings a widget draws.
 *
 * Below API 33 an in-app language reaches Activities only, so the context a
 * widget is drawn from answers in the phone's language: a Chinese phone running
 * the app in English drew "下一堂課" on the Next class widget. On API 33+ that
 * context already carries the in-app language and this changes nothing. See
 * [AppLanguageManager.localizedContext].
 */
internal fun widgetContext(context: Context, appLanguage: String): Context =
    AppLanguageManager.localizedContext(context, appLanguage)

