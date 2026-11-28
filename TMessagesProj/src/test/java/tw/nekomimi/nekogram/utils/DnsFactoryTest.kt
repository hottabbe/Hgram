package tw.nekomimi.nekogram.utils

import android.app.Application
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import org.telegram.messenger.FileLog
import org.telegram.messenger.ApplicationLoader
import tw.nekomimi.nekogram.NekoConfig

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, shadows = [DnsFactoryTest.ShadowFileLog::class])
class DnsFactoryTest {
    @Implements(FileLog::class)
    class ShadowFileLog {
        companion object {
            @JvmStatic
            @Implementation
            fun d(message: String) { }
        }
    }

    @Before
    fun setup() {
        ApplicationLoader.applicationContext = RuntimeEnvironment.getApplication()
    }

    @Test
    fun systemDnsResolvesLocalhostWithoutNetworkAccess() {
        val previous = NekoConfig.useSystemDNS.Bool()
        try {
            NekoConfig.useSystemDNS.setConfigBool(true)
            val result = DnsFactory.lookup("localhost")
            assertTrue("localhost must resolve to a loopback address", result.any { it.isLoopbackAddress })
        } finally {
            NekoConfig.useSystemDNS.setConfigBool(previous)
        }
    }
}