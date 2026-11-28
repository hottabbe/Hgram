package org.telegram.ui;

import android.app.Application;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DialogObject;
import xyz.nextalone.nagram.hgram.HgramSecretSpace;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class ChatActivityHgramDialogIdTest {

    @Before
    public void setUp() {
        ApplicationLoader.applicationContext = ApplicationProvider.getApplicationContext();
        HgramSecretSpace.clearAll();
    }

    @Test
    public void encryptedChatIdMatchesHiddenSecretSpaceKey() {
        final int encId = 42;
        final long dialogId = DialogObject.makeEncryptedDialogId(encId);
        assertNotEquals(-encId, dialogId);
        HgramSecretSpace.setHidden(0, dialogId, true);
        assertFalse(HgramSecretSpace.canOpenDialog(0, dialogId));
        assertTrue(HgramSecretSpace.canOpenDialog(0, DialogObject.makeEncryptedDialogId(43)));
    }
}
