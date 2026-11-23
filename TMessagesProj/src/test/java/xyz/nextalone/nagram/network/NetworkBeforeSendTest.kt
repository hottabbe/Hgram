package xyz.nextalone.nagram.network

import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.telegram.messenger.ApplicationLoader
import xyz.nextalone.nagram.hgram.HgramVoiceSummarizer

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class NetworkBeforeSendTest {

    @Before
    fun installContext() {
        ApplicationLoader.applicationContext = RuntimeEnvironment.getApplication()
    }

    @Test
    fun transportBlockedIsRethrownFromRequestBuilder() {
        val request = NetworkRequestBuilder.get("http://127.0.0.1/never") {}
        request.beforeSend { throw HgramVoiceSummarizer.TransportBlockedException() }
        try {
            request.execute()
            fail("TransportBlockedException must reach the LLM path")
        } catch (expected: HgramVoiceSummarizer.TransportBlockedException) {
            // Expected: admission failure is control flow, not a NetworkResponse.
        }
    }
}
