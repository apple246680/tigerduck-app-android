package org.ntust.app.tigerduck.ui.screen.emptyclassroom

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.academic.Holiday
import org.ntust.app.tigerduck.emptyclassroom.CampusUsage
import org.ntust.app.tigerduck.emptyclassroom.ClassStop
import org.ntust.app.tigerduck.emptyclassroom.ClassroomUsageError
import org.ntust.app.tigerduck.emptyclassroom.ClassroomUsageService
import org.ntust.app.tigerduck.emptyclassroom.DayOff
import org.ntust.app.tigerduck.emptyclassroom.EmptyClassroomSchedule
import org.ntust.app.tigerduck.emptyclassroom.KeptUsage
import org.ntust.app.tigerduck.shared.Course
import java.time.LocalDate
import java.time.LocalDateTime
import javax.inject.Inject

/** What the finder shows under its day and period pickers. */
sealed interface FinderContent {
    data object Loading : FinderContent

    data class Rooms(val usage: CampusUsage, val fetchedAtMs: Long) : FinderContent

    /** A weekend, a day outside the term, or a weekday the site answers with no rooms. */
    data object NoData : FinderContent

    /** The site shows the weekly timetable on a holiday, so its rooms are not shown. */
    data class HolidayOff(val holiday: Holiday) : FinderContent

    data class Failed(val offline: Boolean) : FinderContent
}

data class FinderState(
    val now: LocalDateTime,
    val selectedDay: LocalDate,
    val selectedPeriod: Int = 0,
    val content: FinderContent = FinderContent.Loading,
    /** The user's classes on [selectedDay]. */
    val classes: List<ClassStop> = emptyList(),
    val pickedBuilding: String? = null,
    /** A pull's spinner. */
    val isRefreshing: Boolean = false,
    /** A day on screen is being read again, without a pull. */
    val isReloading: Boolean = false,
    val nextTeachingDay: LocalDate? = null,
) {
    val today: LocalDate get() = now.toLocalDate()

    /** Today and the 13 days after it. */
    val days: List<LocalDate>
        get() = (0 until EmptyClassroomSchedule.DAY_COUNT).map { today.plusDays(it.toLong()) }

    val isShowingToday: Boolean get() = selectedDay == today

    /** Periods already over on the day shown: none on a later day. */
    val endedPeriods: Int get() = if (isShowingToday) EmptyClassroomSchedule.endedPeriods(now) else 0

    /** The period in progress, or the next in a break, today only. */
    val currentPeriod: Int? get() = if (isShowingToday) EmptyClassroomSchedule.upcomingPeriod(now) else null

    val usage: CampusUsage? get() = (content as? FinderContent.Rooms)?.usage
}

/**
 * The finder's state: the chosen day and period, that day's rooms, the user's classes that day,
 * and the building the user picked. Everything else on the screen derives from these.
 */
@HiltViewModel
class EmptyClassroomViewModel internal constructor(
    private val service: ClassroomUsageService,
    private val env: FinderEnvironment,
) : ViewModel() {
    @Inject
    constructor(service: ClassroomUsageService, env: AppFinderEnvironment) : this(service, env as FinderEnvironment)

    private val _state = MutableStateFlow(
        env.now().let { FinderState(now = it, selectedDay = it.toLocalDate(), pickedBuilding = env.pickedBuilding) },
    )
    val state: StateFlow<FinderState> = _state.asStateFlow()

    val signedIn: StateFlow<Boolean> get() = env.signedIn

    private var courses: List<Course> = emptyList()
    private var hasOpened = false

    /** Between [appear] and [disappear]: only then does the finder read anything. */
    private var isStarted = false
    private var loadJob: Job? = null
    private var tickerJob: Job? = null

    /** The day the load in flight reads, and which load it is. */
    private var running: Pair<LocalDate, Int>? = null
    private var loads = 0

    init {
        // A sign-out, or a sign-in on another account, while the screen exists: what it holds
        // belonged to the account before.
        viewModelScope.launch { env.signedIn.drop(1).collect { accountChanged() } }
    }

    /**
     * Opens on the current period, or the next teaching day's first, the first time and after the
     * day shown has passed; then loads the day.
     */
    fun appear() {
        isStarted = true
        tick()
        val state = _state.value
        if (!hasOpened || state.selectedDay < state.today) {
            val (day, period) = EmptyClassroomSchedule.opening(
                state.now, EmptyClassroomSchedule.DAY_COUNT - 1, ::isTeachingDay,
            )
            _state.update { it.copy(selectedDay = day, selectedPeriod = period) }
            hasOpened = true
        }
        startTicker()
        reloadCourses()
        load(force = false)
    }

    /** The screen went away: a read it started would otherwise go on, and queue later reads behind it. */
    fun disappear() {
        isStarted = false
        stopLoading()
        tickerJob?.cancel()
        tickerJob = null
        _state.update { it.copy(isRefreshing = false, isReloading = false) }
    }

    /**
     * The signed-in account changed: what the screen holds belonged to the one before. Off screen
     * that is only forgotten; the next [appear] reads for the new account.
     */
    fun accountChanged() {
        stopLoading()
        _state.update {
            it.copy(content = FinderContent.Loading, pickedBuilding = env.pickedBuilding, isRefreshing = false)
        }
        if (!isStarted) return
        reloadCourses()
        load(force = false)
    }

    /** The user's classes come from the class table, which a sync can change while the screen is open. */
    fun reloadCourses() {
        viewModelScope.launch {
            courses = runCatching { env.courses() }.getOrElse { if (it is CancellationException) throw it else emptyList() }
            refreshClasses()
        }
    }

    fun select(day: LocalDate) {
        if (day == _state.value.selectedDay) return
        _state.update { it.copy(selectedDay = day) }
        refreshClasses()
        load(force = false)
    }

    fun select(period: Int) {
        if (period !in EmptyClassroomSchedule.periodIds.indices) return
        _state.update { it.copy(selectedPeriod = period) }
    }

    fun pick(building: String) {
        env.pickedBuilding = building
        _state.update { it.copy(pickedBuilding = building) }
    }

    /** A pull: reads the day again even when it was read moments ago. */
    fun refresh() {
        _state.update { it.copy(isRefreshing = true) }
        load(force = true)
    }

    fun retry() {
        _state.update { it.copy(content = FinderContent.Loading) }
        load(force = true)
    }

    private fun isTeachingDay(day: LocalDate): Boolean = EmptyClassroomSchedule.isTeachingDay(day, env.calendar())

    private fun refreshClasses() {
        val state = _state.value
        val classes = ClassStop.stops(courses, state.selectedDay.dayOfWeek.value)
        val remaining = java.time.temporal.ChronoUnit.DAYS.between(state.selectedDay, state.days.last()).toInt()
        _state.update {
            it.copy(
                classes = classes,
                nextTeachingDay = EmptyClassroomSchedule.nextTeachingDay(state.selectedDay, remaining, ::isTeachingDay),
            )
        }
    }

    private fun stopLoading() {
        loadJob?.cancel()
        loadJob = null
        running = null
    }

    private fun load(force: Boolean) {
        val day = _state.value.selectedDay
        // A repeated appear() while this day loads keeps that load. Leaving the screen already
        // stopped it, so coming back after a disappear() always starts a new read.
        if (!force && running?.first == day) return
        stopLoading()
        refreshClasses()
        when (val off = EmptyClassroomSchedule.dayOff(day, env.calendar())) {
            is DayOff.HolidayOff -> return show(FinderContent.HolidayOff(off.holiday))
            DayOff.Weekend, DayOff.OutOfTerm -> return show(FinderContent.NoData)
            null -> Unit
        }
        val credentials = env.credentials() ?: return show(FinderContent.Loading)
        val kept = service.keptUsage(day)
        _state.update {
            it.copy(
                content = kept?.let(::contentOf) ?: FinderContent.Loading,
                isReloading = kept != null && !it.isRefreshing,
            )
        }
        val isToday = day == _state.value.today
        val id = ++loads
        running = day to id
        loadJob = viewModelScope.launch {
            val outcome = try {
                Result.success(service.usage(day, isToday, force, credentials))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            if (running?.second == id) running = null
            if (!isActive || _state.value.selectedDay != day || !env.isCurrent(credentials)) return@launch
            outcome.onSuccess { fresh ->
                _state.update { it.copy(content = contentOf(fresh), isRefreshing = false, isReloading = false) }
            }.onFailure { error ->
                if (error is ClassroomUsageError.SignedOut) return@launch
                val offline = env.isOffline(error)
                _state.update {
                    // A day already on screen stays when reading it again fails; the service
                    // stops offering a day as old as half a day to stand in while it reloads.
                    val content = if (it.content == FinderContent.Loading) FinderContent.Failed(offline) else it.content
                    it.copy(content = content, isRefreshing = false, isReloading = false)
                }
            }
        }
    }

    private fun show(content: FinderContent) {
        _state.update { it.copy(content = content, isRefreshing = false, isReloading = false) }
    }

    private fun contentOf(kept: KeptUsage): FinderContent =
        if (kept.usage.rooms.isEmpty()) FinderContent.NoData else FinderContent.Rooms(kept.usage, kept.fetchedAtMs)

    /** The app clock, read again as each minute passes; a day that has passed reopens the finder. */
    private fun startTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = viewModelScope.launch {
            while (isActive) {
                val now = env.now()
                delay(((60 - now.second) * 1000L - now.nano / 1_000_000).coerceAtLeast(1_000L))
                val before = _state.value.today
                tick()
                if (_state.value.today != before && _state.value.selectedDay < _state.value.today) {
                    hasOpened = false
                    appear()
                }
            }
        }
    }

    private fun tick() {
        _state.update { it.copy(now = env.now()) }
    }

    override fun onCleared() {
        stopLoading()
        super.onCleared()
    }
}
