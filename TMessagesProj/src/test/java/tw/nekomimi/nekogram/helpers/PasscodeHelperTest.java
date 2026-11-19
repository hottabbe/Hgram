package tw.nekomimi.nekogram.helpers;

import android.app.Application;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.Utilities;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class PasscodeHelperTest {

    @Before
    public void setUp() {
        ApplicationLoader.applicationContext = (Application) ApplicationProvider.getApplicationContext();
        SharedConfig.passcodeHash = "";
        SharedConfig.passcodeSalt = new byte[0];
        PasscodeHelper.removePasscodeForAccount(PasscodeHelper.HGRAM_DECOY_ACCOUNT);
        PasscodeHelper.removePasscodeForAccount(Integer.MAX_VALUE);
    }

    @After
    public void tearDown() {
        SharedConfig.passcodeHash = "";
        SharedConfig.passcodeSalt = new byte[0];
        PasscodeHelper.removePasscodeForAccount(PasscodeHelper.HGRAM_DECOY_ACCOUNT);
        PasscodeHelper.removePasscodeForAccount(Integer.MAX_VALUE);
    }

    @Test
    public void detectsMainPasscodeConflict() {
        SharedConfig.passcodeSalt = new byte[0];
        SharedConfig.passcodeHash = Utilities.MD5("1234");
        assertTrue(PasscodeHelper.isDecoyPasscodeConflict("1234"));
        assertFalse(PasscodeHelper.isDecoyPasscodeConflict("9876"));
    }

    @Test
    public void detectsPanicPasscodeConflict() {
        PasscodeHelper.setPasscodeForAccount("9999", Integer.MAX_VALUE);
        assertTrue(PasscodeHelper.isDecoyPasscodeConflict("9999"));
        assertFalse(PasscodeHelper.isDecoyPasscodeConflict("0000"));
    }
}
