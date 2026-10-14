package org.ntust.app.tigerduck.ui.screen.mail

import android.Manifest
import android.app.Application
import android.os.Build
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.ntust.app.tigerduck.mail.FakeDemoGate
import org.ntust.app.tigerduck.mail.FakeMailServer
import org.ntust.app.tigerduck.mail.InMemoryCredentialStore
import org.ntust.app.tigerduck.mail.InMemoryMailStateStore
import org.ntust.app.tigerduck.mail.MailAccount
import org.ntust.app.tigerduck.mail.MainDispatcherRule
import org.ntust.app.tigerduck.mail.RecordingNotifier
import org.ntust.app.tigerduck.mail.RecordingScheduler
import org.ntust.app.tigerduck.mail.schoolMailSite
import org.ntust.app.tigerduck.mail.store.MailCache
import org.ntust.app.tigerduck.mail.sync.ExactAlarmAccess
import org.ntust.app.tigerduck.mail.testApplicationScope
import org.ntust.app.tigerduck.notification.SystemPermissions
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The School Mail notifications page says when the system blocks notifications, and checks again on return. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [Build.VERSION_CODES.TIRAMISU])
class SchoolMailSettingsPermissionTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `a blocked notification permission shows, and clears once granted and the page is back`() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val state = InMemoryMailStateStore()
        val cache = MailCache(tmp.root)
        val account = MailAccount(
            InMemoryCredentialStore(), state, FakeMailServer().factory(), cache, FakeDemoGate(), schoolMailSite(),
            RecordingScheduler(), RecordingNotifier(), testApplicationScope(),
        )
        val vm = SchoolMailSettingsViewModel(
            state, RecordingScheduler(), ExactAlarmAccess { true }, cache, main.dispatcher, account, SystemPermissions(context),
        )
        assertTrue(vm.ui.value.notificationsDenied)

        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        vm.refresh()
        assertFalse(vm.ui.value.notificationsDenied)
    }
}
