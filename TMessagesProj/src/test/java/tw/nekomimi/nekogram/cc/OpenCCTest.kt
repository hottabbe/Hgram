package tw.nekomimi.nekogram.cc

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.telegram.messenger.ApplicationLoader

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class OpenCCTest {
    @Before
    fun setup() {
        ApplicationLoader.applicationContext = RuntimeEnvironment.getApplication()
    }

    @Test
    fun convertsSimplifiedAndTraditionalTextUsingBundledDictionaries() {
        assertEquals("繁體中文", CCConverter.get(CCTarget.TC).convert("繁体中文"))
        assertEquals("简体中文", CCConverter.get(CCTarget.SC).convert("簡體中文"))
        assertEquals("Hello 123", CCConverter.get(CCTarget.TT).convert("Hello 123"))
    }
}
