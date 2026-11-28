package xyz.nextalone.nagram.hgram;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class HgramSecretSpaceTest {

    private Application application;

    @Before
    public void setUp() {
        application = ApplicationProvider.getApplicationContext();
        ApplicationLoader.applicationContext = application;
        SharedConfig.appLocked = false;
        UserConfig.selectedAccount = 0;
        HgramSecretSpace.resetForTests();
        HgramSecretSpace.clearAll();
        HgramSecretSpace.setActive(false);
    }

    @After
    public void tearDown() {
        SharedConfig.appLocked = false;
        HgramSecretSpace.resetForTests();
        HgramSecretSpace.clearAll();
        HgramSecretSpace.setActive(false);
    }


    @Test
    public void isActiveDoesNotWriteWhenLocked() {
        SharedConfig.appLocked = true;
        final SharedPreferences prefs = application.getSharedPreferences(
                "hgram_secret_space", Context.MODE_PRIVATE);
        final Map<String, ?> before = new HashMap<>(prefs.getAll());
        assertFalse(HgramSecretSpace.isActive());
        assertEquals(before, prefs.getAll());
        SharedConfig.appLocked = false;
    }

    @Test
    public void emptyLoadWithoutContextIsNotCached() {
        HgramSecretSpace.resetForTests();
        HgramSecretSpace.clearAll();
        ApplicationLoader.applicationContext = null;
        assertEquals(0, HgramSecretSpace.hiddenCount(0));

        ApplicationLoader.applicationContext = application;
        application.getSharedPreferences("hgram_secret_space", Context.MODE_PRIVATE)
                .edit().putStringSet("hidden_0", new HashSet<>(Arrays.asList("100")))
                .commit();
        assertTrue(HgramSecretSpace.isHidden(0, 100));
    }

    @Test
    public void hideAndUnhideAreIdempotent() {
        assertFalse(HgramSecretSpace.isHidden(0, 100));
        assertTrue(HgramSecretSpace.toggle(0, 100));
        assertTrue(HgramSecretSpace.isHidden(0, 100));
        assertEquals(1, HgramSecretSpace.hiddenCount(0));

        HgramSecretSpace.setHidden(0, 100, true);
        assertEquals(1, HgramSecretSpace.hiddenCount(0));

        HgramSecretSpace.setHidden(0, 100, false);
        assertFalse(HgramSecretSpace.isHidden(0, 100));
        assertEquals(0, HgramSecretSpace.hiddenCount(0));

        HgramSecretSpace.setHidden(0, 100, false);
        assertEquals(0, HgramSecretSpace.hiddenCount(0));
    }

    @Test
    public void hiddenIdsAreSeparatedByAccount() {
        HgramSecretSpace.setHidden(0, 100, true);
        assertTrue(HgramSecretSpace.isHidden(0, 100));
        assertFalse(HgramSecretSpace.isHidden(1, 100));
        assertEquals(1, HgramSecretSpace.hiddenCount(0));
        assertEquals(0, HgramSecretSpace.hiddenCount(1));
    }

    @Test
    public void visibilityDependsOnActiveState() {
        HgramSecretSpace.setHidden(0, 100, true);
        HgramSecretSpace.setActive(false);
        assertFalse(HgramSecretSpace.isVisible(0, 100));
        assertFalse(HgramSecretSpace.canOpenDialog(0, 100));
        assertTrue(HgramSecretSpace.canOpenDialog(0, 0));

        HgramSecretSpace.setActive(true);
        assertTrue(HgramSecretSpace.isActive());
        assertTrue(HgramSecretSpace.isVisible(0, 100));
        assertTrue(HgramSecretSpace.canOpenDialog(0, 100));
    }

    @Test
    public void lateLinkGuardChecksOriginLockAndVisibility() {
        HgramSecretSpace.setHidden(0, 100, true);
        assertFalse(HgramLinkAccess.canOpen(0, 0, false, 100));
        assertTrue(HgramLinkAccess.canOpen(0, 0, false, 200));
        assertFalse(HgramLinkAccess.canOpen(1, 0, false, 200));
        assertFalse(HgramLinkAccess.canOpen(0, 0, true, 200));

        HgramSecretSpace.setActive(true);
        assertTrue(HgramLinkAccess.canOpen(0, 0, false, 100));
    }

    @Test
    public void discussionGateUsesSignedDialogId() {
        HgramSecretSpace.setHidden(0, -100, true);
        assertFalse(HgramSecretSpace.canOpenDialog(0, -100));
        assertTrue(HgramSecretSpace.canOpenDialog(0, 100));
    }

    @Test
    public void migratesLegacyHiddenDialogs() {
        UserConfig.selectedAccount = 2;
        HgramSecretSpace.resetForTests();
        HgramSecretSpace.clearAll();
        final SharedPreferences legacy = application.getSharedPreferences("hgram_decoy_space", Application.MODE_PRIVATE);
        legacy.edit().putStringSet("hidden_dialogs", new HashSet<>(Arrays.asList("100", "200"))).commit();
        HgramSecretSpace.resetForTests();

        assertTrue(HgramSecretSpace.isHidden(2, 100));
        assertTrue(HgramSecretSpace.isHidden(2, 200));
        assertFalse(legacy.contains("hidden_dialogs"));
    }

    @Test
    public void activeIsRejectedWhileAppLocked() {
        SharedConfig.appLocked = true;
        HgramSecretSpace.setActive(true);
        assertFalse(HgramSecretSpace.isActive());
        assertFalse(application.getSharedPreferences("hgram_secret_space", Application.MODE_PRIVATE)
                .getBoolean("active", true));
    }

    @Test
    public void activeIsNotRestoredFromPreferencesAfterCacheReset() {
        application.getSharedPreferences("hgram_secret_space", Application.MODE_PRIVATE)
                .edit().putBoolean("active", true).commit();
        HgramSecretSpace.resetForTests();
        assertFalse(HgramSecretSpace.isActive());
    }

    @Test
    public void appLockedSelfHealsStoredActiveState() {
        application.getSharedPreferences("hgram_secret_space", Application.MODE_PRIVATE)
                .edit().putBoolean("active", true).commit();
        HgramSecretSpace.resetForTests();
        SharedConfig.appLocked = true;
        assertFalse(HgramSecretSpace.isActive());
        assertFalse(HgramSecretSpace.isActive());
        assertTrue(application.getSharedPreferences("hgram_secret_space", Application.MODE_PRIVATE)
                .getBoolean("active", false));
        HgramSecretSpace.onAppLocked();
        assertFalse(application.getSharedPreferences("hgram_secret_space", Application.MODE_PRIVATE)
                .getBoolean("active", true));
    }

    @Test
    public void clearResetsCacheAndPreferences() {
        HgramSecretSpace.setHidden(0, 100, true);
        assertTrue(HgramSecretSpace.isHidden(0, 100));
        HgramSecretSpace.clear(0);
        assertEquals(0, HgramSecretSpace.hiddenCount(0));
        assertFalse(HgramSecretSpace.isHidden(0, 100));
        assertFalse(application.getSharedPreferences("hgram_secret_space", Application.MODE_PRIVATE)
                .contains("hidden_0"));
    }

    @Test
    public void migrationDoesNotOverwriteExistingAccountSet() {
        UserConfig.selectedAccount = 2;
        HgramSecretSpace.resetForTests();
        HgramSecretSpace.clearAll();
        final SharedPreferences prefs = application.getSharedPreferences("hgram_secret_space", Application.MODE_PRIVATE);
        prefs.edit().putStringSet("hidden_2", new HashSet<>(Arrays.asList("300"))).commit();
        final SharedPreferences legacy = application.getSharedPreferences("hgram_decoy_space", Application.MODE_PRIVATE);
        legacy.edit().putStringSet("hidden_dialogs", new HashSet<>(Arrays.asList("100"))).commit();
        HgramSecretSpace.resetForTests();

        assertTrue(HgramSecretSpace.isHidden(2, 300));
        assertFalse(HgramSecretSpace.isHidden(2, 100));
        assertFalse(legacy.contains("hidden_dialogs"));
        assertEquals(1, HgramSecretSpace.hiddenCount(2));
    }

    @Test
    public void clearAllRemovesLegacyKey() {
        final SharedPreferences legacy = application.getSharedPreferences("hgram_decoy_space", Application.MODE_PRIVATE);
        legacy.edit().putStringSet("hidden_dialogs", new HashSet<>(Arrays.asList("100"))).commit();
        HgramSecretSpace.clearAll();
        assertFalse(legacy.contains("hidden_dialogs"));
    }

    @Test
    public void normalPinNeverRestoresHiddenFragmentAfterDecoyLock() {
        HgramSecretSpace.setHidden(0, -100, true);
        HgramSecretSpace.setActive(true);
        assertTrue(HgramSecretSpace.canOpenDialog(0, -100));

        SharedConfig.appLocked = true;
        HgramSecretSpace.onAppLocked();
        assertFalse(HgramSecretSpace.isActive());
        assertFalse(HgramSecretSpace.canOpenDialog(0, -100));

        SharedConfig.appLocked = false;
        HgramSecretSpace.setActive(false);
        assertFalse(HgramSecretSpace.canOpenDialog(0, -100));
    }

    @Test
    public void decoyPinUpdatesWorkspaceBeforeDrainingIntent() {
        HgramSecretSpace.setHidden(0, 200, true);
        HgramSecretSpace.setActive(false);
        assertFalse(HgramSecretSpace.canOpenDialog(0, 200));

        HgramSecretSpace.setActive(true);
        assertTrue(HgramSecretSpace.canOpenDialog(0, 200));
    }

    @Test
    public void hiddenStoryDeepLinksAreRejectedAtPresentation() {
        HgramSecretSpace.setHidden(0, -300, true);
        assertFalse(HgramSecretSpace.canOpenDialog(0, -300));

        HgramSecretSpace.setActive(true);
        assertTrue(HgramSecretSpace.canOpenDialog(0, -300));

        SharedConfig.appLocked = true;
        HgramSecretSpace.onAppLocked();
        SharedConfig.appLocked = false;
        assertFalse(HgramSecretSpace.canOpenDialog(0, -300));
    }

    @Test
    public void hiddenEncryptedPushUsesCanonicalDialogId() {
        final long canonical = org.telegram.messenger.DialogObject.makeEncryptedDialogId(321);
        HgramSecretSpace.setHidden(0, canonical, true);
        assertTrue(HgramSecretSpace.isHidden(0, canonical));
        assertFalse(HgramSecretSpace.isHidden(0, -321));
        assertFalse(HgramSecretSpace.canOpenDialog(0, canonical));
    }

    @Test
    public void hiddenPeersNeverAppearInFreshPublicPostOrEncryptedSearch() {
        final long encrypted = org.telegram.messenger.DialogObject.makeEncryptedDialogId(777);
        final long publicChannel = -401;
        HgramSecretSpace.setHidden(0, encrypted, true);
        HgramSecretSpace.setHidden(0, publicChannel, true);
        assertFalse(HgramSecretSpace.isVisible(0, encrypted));
        assertFalse(HgramSecretSpace.isVisible(0, publicChannel));

        HgramSecretSpace.setActive(true);
        assertTrue(HgramSecretSpace.isVisible(0, encrypted));
        assertTrue(HgramSecretSpace.isVisible(0, publicChannel));
    }

    @Test
    public void hideRemovesExistingChatsWidgetPreview() {
        HgramSecretSpace.setHidden(0, 500, true);
        assertFalse(HgramSecretSpace.isVisible(0, 500));

        HgramSecretSpace.setActive(true);
        assertTrue(HgramSecretSpace.isVisible(0, 500));
    }

    @Test
    public void tabletNormalPinDoesNotExposeLastHiddenLayer() {
        HgramSecretSpace.setHidden(0, -700, true);
        HgramSecretSpace.setActive(true);
        assertTrue(HgramSecretSpace.canOpenDialog(0, -700));

        SharedConfig.appLocked = true;
        HgramSecretSpace.onAppLocked();
        SharedConfig.appLocked = false;
        HgramSecretSpace.setActive(false);

        assertFalse(HgramSecretSpace.canOpenDialog(0, -700));
        assertFalse(HgramSecretSpace.isActive());
    }

    @Test
    public void storyLinkDoesNotOpenAfterOriginAccountChanges() {
        HgramSecretSpace.setHidden(0, 710, true);
        HgramSecretSpace.setActive(true);
        assertTrue(HgramSecretSpace.canOpenDialog(0, 710));
        assertFalse(HgramStoryAccess.canOpen(0, 1, false, 710));
        assertTrue(HgramStoryAccess.canOpen(0, 0, false, 710));
    }

    @Test
    public void storyPushCallbackRechecksAfterRelock() {
        HgramSecretSpace.setHidden(0, 720, true);
        HgramSecretSpace.setActive(true);
        SharedConfig.appLocked = true;
        HgramSecretSpace.onAppLocked();
        SharedConfig.appLocked = false;
        assertFalse(HgramStoryAccess.canOpen(0, 0, true, 720));
        assertFalse(HgramSecretSpace.canOpenDialog(0, 720));
    }

    @Test
    public void visiblePushStoryDoesNotAppendHiddenUnreadPeer() {
        HgramSecretSpace.setHidden(0, 731, true);
        final List<Long> peers = new ArrayList<>(Arrays.asList(730L, 731L));
        HgramSecretSpace.retainVisible(0, peers);
        assertEquals(Arrays.asList(730L), peers);

        HgramSecretSpace.setActive(true);
        final List<Long> decoyPeers = new ArrayList<>(Arrays.asList(730L, 731L));
        HgramSecretSpace.retainVisible(0, decoyPeers);
        assertEquals(Arrays.asList(730L, 731L), decoyPeers);
    }

    @Test
    public void hiddenProfileAndForumLinksAreRejected() {
        HgramSecretSpace.setHidden(0, 740, true);
        HgramSecretSpace.setHidden(0, -741, true);
        assertFalse(HgramSecretSpace.canOpenDialog(0, 740));
        assertFalse(HgramSecretSpace.canOpenDialog(0, -741));
    }

    @Test
    public void launcherWidgetsNeverExposeHiddenPeerInDecoyOrAfterLock() {
        HgramSecretSpace.setHidden(0, 750, true);
        HgramSecretSpace.setActive(true);
        assertTrue(HgramSecretSpace.isHidden(0, 750));
        SharedConfig.appLocked = true;
        HgramSecretSpace.onAppLocked();
        SharedConfig.appLocked = false;
        assertTrue(HgramSecretSpace.isHidden(0, 750));
    }

    @Test
    public void expandedPublicPostsFiltersLatePagesAndExistingRows() {
        final long encrypted = org.telegram.messenger.DialogObject.makeEncryptedDialogId(760);
        HgramSecretSpace.setHidden(0, encrypted, true);
        HgramSecretSpace.setHidden(0, -761, true);
        final List<Long> page = new ArrayList<>(Arrays.asList(encrypted, -761L, -762L));
        HgramSecretSpace.retainVisible(0, page);
        assertEquals(Arrays.asList(-762L), page);
    }

    @Test
    public void hiddenDirectProfileLinkChecksOriginalPeerNotLinkedForum() {
        HgramSecretSpace.setHidden(0, -810, true);
        assertFalse(HgramLinkAccess.canOpen(0, 0, false, -810));
        assertTrue(HgramLinkAccess.canOpen(0, 0, false, -811));
    }

    @Test
    public void lateForumLinkRejectsAfterAccountOrLockChange() {
        HgramSecretSpace.setHidden(0, -820, true);
        assertFalse(HgramLinkAccess.canOpen(0, 0, false, -820));
        assertFalse(HgramLinkAccess.canOpen(0, 1, false, -820));
        assertFalse(HgramLinkAccess.canOpen(0, 0, true, -820));
    }

    @Test
    public void widgetBindGateDoesNotUseDecoyVisibility() {
        HgramSecretSpace.setHidden(0, 830, true);
        HgramSecretSpace.setActive(true);
        assertTrue(HgramSecretSpace.isHidden(0, 830));
        assertTrue(HgramSecretSpace.isVisible(0, 830));
    }
}
