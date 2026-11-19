package tw.nekomimi.nekogram.settings;

import android.content.Context;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.widget.Toast;

import java.lang.ref.WeakReference;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.telegram.messenger.FlagSecureReason;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.PasscodeActivity;

import tw.nekomimi.nekogram.helpers.PasscodeHelper;
import xyz.nextalone.nagram.NaConfig;
import xyz.nextalone.nagram.hgram.HgramAntiPeek;
import xyz.nextalone.nagram.hgram.HgramConfig;
import xyz.nextalone.nagram.hgram.HgramDcAddressCache;
import xyz.nextalone.nagram.hgram.HgramNotificationDebouncer;
import xyz.nextalone.nagram.hgram.HgramOfflineTranscriber;
import xyz.nextalone.nagram.hgram.HgramGigaAmRecognizer;
import xyz.nextalone.nagram.hgram.HgramSherpaRecognizer;
import xyz.nextalone.nagram.hgram.HgramSummarizerModel;
import xyz.nextalone.nagram.hgram.HgramVoskModels;
import xyz.nextalone.nagram.hgram.HgramDownloadThreadsCell;
import xyz.nextalone.nagram.hgram.HgramHapticCapabilities;
import xyz.nextalone.nagram.hgram.HgramHapticProfile;
import xyz.nextalone.nagram.hgram.HgramHaptics;
import org.telegram.ui.Components.RecyclerListView;

/** hgram settings, grouped by topic. */
public class HgramSettingsActivity extends BaseNekoSettingsActivity {

    private static final int SEARCH_MENU_ITEM = 1002;
    static final int ROW_SEARCH_EMPTY = -101;
    private int[] visibleRows = ROWS;
    private String searchQuery = "";
    private ActionBarMenuItem searchItem;
    private boolean searching;
    private boolean suppressSearchCallback;
    private final java.util.HashMap<Integer, String> searchTextCache = new java.util.HashMap<>();
    private boolean antiPeekSkipNextDismiss;
    private Runnable antiPeekReopenRunnable;
    private AlertDialog antiPeekDialog;
    private int summarizerModelsRequestId;
    private boolean summarizerModelsLoading;
    private android.app.ProgressDialog summarizerModelsProgress;

    static final int ROW_HEADER_FEED = 0;
    private static final int ROW_SIMILAR_POST_DEDUPLICATION = 1;
    private static final int ROW_SIMILAR_POST_INFO = 2;
    private static final int ROW_CLEAN_FEED_FILTER = 3;
    private static final int ROW_CLEAN_FEED_INFO = 4;
    static final int ROW_COMPACT_ALBUMS = 5;
    private static final int ROW_COMPACT_ALBUMS_INFO = 6;
    private static final int ROW_HEADER_AUDIO = 7;
    private static final int ROW_AUTO_LOUDNESS = 8;
    private static final int ROW_AUTO_LOUDNESS_INFO = 9;
    private static final int ROW_OFFLINE_TRANSCRIPTION = 12;
    private static final int ROW_OFFLINE_TRANSCRIPTION_INFO = 13;
    static final int ROW_STT_ENGINE = 14;
    static final int ROW_VOSK_MODELS = 15;
    static final int ROW_SHERPA_MODEL = 16;
    static final int ROW_GIGAAM_MODEL = 43;
    static final int ROW_GIGAAM_INFO = 77;
    static final int ROW_SHERPA_DEVICE = 42;
    private static final int ROW_WEAK_NETWORK = 44;
    private static final int ROW_HEADER_PRIVACY = 17;
    static final int ROW_PIN_TO_SCREEN = 18;
    private static final int ROW_PIN_TO_SCREEN_INFO = 19;
    private static final int ROW_ANTI_PEEK = 20;
    private static final int ROW_ANTI_PEEK_INFO = 21;
    private static final int ROW_ALLOW_SCREENSHOTS = 22;
    private static final int ROW_ALLOW_SCREENSHOTS_INFO = 23;
    private static final int ROW_DECOY_PASSCODE = 24;
    private static final int ROW_DECOY_PASSCODE_INFO = 25;
    private static final int ROW_HEADER_OTHER = 26;
    private static final int ROW_NOTIFICATION_DEBOUNCE = 27;
    private static final int ROW_NOTIFICATION_INFO = 28;
    private static final int ROW_FRAME_THROTTLING = 29;
    private static final int ROW_FRAME_THROTTLING_INFO = 30;
    private static final int ROW_FAST_HANDOVER = 31;
    private static final int ROW_FAST_HANDOVER_INFO = 32;
    static final int ROW_DOWNLOAD_THREADS = 33;
    static final int ROW_UPLOAD_THREADS = 45;
    private static final int ROW_NOTIF_MEDIA_STYLE = 46;
    static final int ROW_RESET_HGRAM_SETTINGS = 47;
    static final int ROW_SUMMARIZER_ENABLE = 72;
    static final int ROW_SUMMARIZER_TRIGGER = 73;
    static final int ROW_SUMMARIZER_KEY = 74;
    static final int ROW_SUMMARIZER_MODEL = 75;
    static final int ROW_SUMMARIZER_PROMPT = 76;
    private static final int ROW_DOWNLOAD_THREADS_INFO = 34;
    private static final int ROW_SHORT_MERGE = 35;
    private static final int ROW_SHORT_MERGE_INFO = 36;
    private static final int ROW_HEADER_HAPTICS = 55;
    static final int ROW_HAPTIC_TOGGLE = 56;
    static final int ROW_HAPTIC_BASE = 57;
    static final int TYPE_DOWNLOAD_THREADS = 100;
    static final int TYPE_UPLOAD_THREADS = 101;
    static final int ROW_HEADER_CHAT = 37;
    private static final int ROW_HEADER_NETWORK = 38;
    private static final int ROW_HEADER_RECOGNITION = 39;
    private static final int ROW_FEED_KEYWORDS = 41;
    static final int[] ROWS = {
            ROW_HEADER_CHAT, ROW_SHORT_MERGE, ROW_PIN_TO_SCREEN,
            ROW_SUMMARIZER_ENABLE, ROW_SUMMARIZER_TRIGGER, ROW_SUMMARIZER_KEY, ROW_SUMMARIZER_MODEL, ROW_SUMMARIZER_PROMPT,
            ROW_HEADER_FEED, ROW_COMPACT_ALBUMS, ROW_SIMILAR_POST_DEDUPLICATION, ROW_CLEAN_FEED_FILTER, ROW_FEED_KEYWORDS,
            ROW_HEADER_AUDIO, ROW_AUTO_LOUDNESS,
            ROW_HEADER_RECOGNITION, ROW_OFFLINE_TRANSCRIPTION, ROW_STT_ENGINE, ROW_VOSK_MODELS, ROW_SHERPA_MODEL, ROW_SHERPA_DEVICE, ROW_GIGAAM_MODEL, ROW_GIGAAM_INFO,
            ROW_HEADER_PRIVACY, ROW_ANTI_PEEK, ROW_ALLOW_SCREENSHOTS, ROW_DECOY_PASSCODE,
            ROW_HEADER_NETWORK, ROW_WEAK_NETWORK, ROW_DOWNLOAD_THREADS, ROW_UPLOAD_THREADS, ROW_FAST_HANDOVER,
            ROW_HEADER_OTHER, ROW_NOTIF_MEDIA_STYLE, ROW_NOTIFICATION_DEBOUNCE, ROW_FRAME_THROTTLING,
            ROW_HEADER_HAPTICS, ROW_HAPTIC_TOGGLE,
            ROW_HAPTIC_BASE, ROW_HAPTIC_BASE + 1, ROW_HAPTIC_BASE + 2, ROW_HAPTIC_BASE + 3,
            ROW_HAPTIC_BASE + 4, ROW_HAPTIC_BASE + 5, ROW_HAPTIC_BASE + 6, ROW_HAPTIC_BASE + 7,
            ROW_HAPTIC_BASE + 8, ROW_HAPTIC_BASE + 9, ROW_HAPTIC_BASE + 10, ROW_HAPTIC_BASE + 11,
            ROW_HAPTIC_BASE + 12, ROW_HAPTIC_BASE + 13, ROW_HAPTIC_BASE + 14,
            ROW_RESET_HGRAM_SETTINGS
    };

    public void refreshAntiPeekRow() {
        notifyRow(ROW_ANTI_PEEK);
    }

    private void notifyRow(int id) {
        if (listAdapter == null || listView == null) return;
        xyz.nextalone.nagram.hgram.HgramRowNotifier.postOrNotify(listView, id,
                rowId -> {
                    for (int i = 0; i < visibleRows.length; i++) {
                        if (visibleRows[i] == rowId) return i;
                    }
                    return -1;
                },
                index -> {
                    if (listAdapter != null && index < listAdapter.getItemCount()) {
                        listAdapter.notifyItemChanged(index);
                    }
                });
    }

    private void notifyAllRows() {
        if (listAdapter == null || listView == null) return;
        if (listView.isComputingLayout() || listView.getScrollState() != RecyclerView.SCROLL_STATE_IDLE) {
            listView.post(() -> {
                if (listAdapter != null && !listView.isComputingLayout()) {
                    listAdapter.notifyDataSetChanged();
                }
            });
            return;
        }
        listAdapter.notifyDataSetChanged();
    }

    private static boolean isToggle(int id) {
        return id == ROW_SHORT_MERGE || id == ROW_COMPACT_ALBUMS || id == ROW_PIN_TO_SCREEN
                || id == ROW_SIMILAR_POST_DEDUPLICATION || id == ROW_CLEAN_FEED_FILTER || id == ROW_AUTO_LOUDNESS
                || id == ROW_OFFLINE_TRANSCRIPTION || id == ROW_ALLOW_SCREENSHOTS
                || id == ROW_FAST_HANDOVER || id == ROW_NOTIFICATION_DEBOUNCE || id == ROW_FRAME_THROTTLING
                || id == ROW_HAPTIC_TOGGLE || id == ROW_SUMMARIZER_ENABLE;
    }

    private void bindToggle(TextCheckCell cell, int id, boolean divider) {
        int title, description;
        boolean checked;
        switch (id) {
            case ROW_SHORT_MERGE: title = R.string.HgramShortMerge; description = R.string.HgramShortMergeInfo; checked = HgramConfig.isShortMessageMergeEnabled(); break;
            case ROW_COMPACT_ALBUMS: title = R.string.HgramRoadmapAlbums; description = R.string.HgramCompactAlbumsInfo; checked = HgramConfig.isCompactMediaAlbumsEnabled(); break;
            case ROW_PIN_TO_SCREEN: title = R.string.HgramRoadmapPinToScreen; description = R.string.HgramPinToScreenInfo; checked = HgramConfig.isPinToScreenEnabled(); break;
            case ROW_SIMILAR_POST_DEDUPLICATION: title = R.string.HgramRoadmapDedup; description = R.string.HgramDedupInfo; checked = HgramConfig.isSimilarPostDeduplicationEnabled(); break;
            case ROW_CLEAN_FEED_FILTER: title = R.string.HgramRoadmapFeedCleanup; description = R.string.HgramCleanFeedInfo; checked = HgramConfig.isCleanFeedFilterEnabled(); break;
            case ROW_AUTO_LOUDNESS: title = R.string.HgramRoadmapLoudness; description = R.string.HgramAutoLoudnessInfo; checked = HgramConfig.isAutoLoudnessEnabled(); break;
            case ROW_OFFLINE_TRANSCRIPTION: title = R.string.HgramRoadmapTranscription; description = R.string.HgramOfflineTranscriptionInfo; checked = HgramConfig.isOfflineTranscriptionEnabled(); break;
            case ROW_ALLOW_SCREENSHOTS: title = R.string.HgramAllowScreenshots; description = R.string.HgramAllowScreenshotsInfo; checked = HgramConfig.isAllowScreenshotsEnabled(); break;
            case ROW_FAST_HANDOVER: title = R.string.HgramFastNetworkHandover; description = R.string.HgramFastHandoverInfo; checked = HgramConfig.isFastNetworkHandoverEnabled(); break;
            case ROW_NOTIFICATION_DEBOUNCE: title = R.string.HgramNotificationDebounce; description = R.string.HgramNotificationDebounceInfo; checked = HgramConfig.isSmartNotificationDebounceEnabled(); break;
            case ROW_HAPTIC_TOGGLE: title = R.string.HgramHapticEnabled; description = R.string.HgramHapticEnabledInfo; checked = HgramConfig.isHapticEnabled(); break;
            case ROW_SUMMARIZER_ENABLE: title = R.string.HgramSummarizerEnable; description = R.string.HgramSummarizerEnableInfo; checked = HgramConfig.isSummarizerEnabled(); break;
            default: title = R.string.HgramRoadmapFrameThrottling; description = R.string.HgramFrameThrottlingInfo; checked = HgramConfig.isSmartFrameThrottlingEnabled(); break;
        }
        cell.setTextAndValueAndCheck(LocaleController.getString(title),
                toggleDescription(id, description), checked, true, divider);
    }

    private String toggleDescription(int id, int descriptionRes) {
        String description = LocaleController.getString(descriptionRes);
        if (id == ROW_NOTIFICATION_DEBOUNCE && !description.contains("30")) {
            description += " · " + (HgramNotificationDebouncer.DEBOUNCE_WINDOW_MS / 1000L) + " s";
        } else if (id == ROW_FRAME_THROTTLING && !description.contains("60")) {
            description += " · 60 fps";
        }
        return description;
    }

    @Override
    protected void updateRows() {
        super.updateRows();
        if (searching) {
            rebuildVisibleRows(true);
        } else {
            visibleRows = buildBaseVisibleRows(HgramConfig.getSttEngine(),
                    HgramConfig.isOfflineTranscriptionEnabled());
            rowCount = visibleRows.length;
        }
    }

    @Override
    protected void onItemClick(View view, int position, float x, float y) {
        position = rowAt(position);
        if (position == ROW_SEARCH_EMPTY) {
            return;
        }
        if (isToggle(position)) {
            HgramHaptics.play(view, HgramHapticProfile.PLACE_TOGGLES);
        } else if (position == ROW_RESET_HGRAM_SETTINGS) {
            HgramHaptics.play(view, HgramHapticProfile.PLACE_LIST_ITEMS);
        }
        if (position == ROW_SHORT_MERGE) {
            boolean enabled = !HgramConfig.isShortMessageMergeEnabled();
            HgramConfig.setShortMessageMergeEnabled(enabled);
            ((TextCheckCell) view).setChecked(enabled);
        } else if (position == ROW_NOTIFICATION_DEBOUNCE) {
            boolean enabled = !HgramConfig.isSmartNotificationDebounceEnabled();
            HgramConfig.setSmartNotificationDebounceEnabled(enabled);
            ((TextCheckCell) view).setChecked(enabled);
        } else if (position == ROW_SIMILAR_POST_DEDUPLICATION) {
            boolean enabled = !HgramConfig.isSimilarPostDeduplicationEnabled();
            HgramConfig.setSimilarPostDeduplicationEnabled(enabled);
            ((TextCheckCell) view).setChecked(enabled);
        } else if (position == ROW_CLEAN_FEED_FILTER) {
            boolean enabled = !HgramConfig.isCleanFeedFilterEnabled();
            HgramConfig.setCleanFeedFilterEnabled(enabled);
            ((TextCheckCell) view).setChecked(enabled);
        } else if (position == ROW_COMPACT_ALBUMS) {
            boolean enabled = !HgramConfig.isCompactMediaAlbumsEnabled();
            HgramConfig.setCompactMediaAlbumsEnabled(enabled);
            ((TextCheckCell) view).setChecked(enabled);
        } else if (position == ROW_AUTO_LOUDNESS) {
            boolean enabled = !HgramConfig.isAutoLoudnessEnabled();
            HgramConfig.setAutoLoudnessEnabled(enabled);
            ((TextCheckCell) view).setChecked(enabled);
        } else if (position == ROW_OFFLINE_TRANSCRIPTION) {
            boolean enabled = !HgramConfig.isOfflineTranscriptionEnabled();
            HgramConfig.setOfflineTranscriptionEnabled(enabled);
            ((TextCheckCell) view).setChecked(enabled);
            rebuildVisibleRowsAfterToggle();
            if (enabled && !hasAnyModelInstalled()) {
                Toast.makeText(getParentActivity(), LocaleController.getString(R.string.HgramOfflineTranscriptionUnavailable), Toast.LENGTH_SHORT).show();
            }
        } else if (position == ROW_PIN_TO_SCREEN) {
            boolean enabled = !HgramConfig.isPinToScreenEnabled();
            HgramConfig.setPinToScreenEnabled(enabled);
            ((TextCheckCell) view).setChecked(enabled);
            if (!enabled && LaunchActivity.instance != null) {
                LaunchActivity.instance.clearHgramPinnedMessage();
            }
        } else if (position == ROW_ANTI_PEEK) {
            showAntiPeekDialog();
        } else if (position == ROW_FRAME_THROTTLING) {
            boolean enabled = !HgramConfig.isSmartFrameThrottlingEnabled();
            HgramConfig.setSmartFrameThrottlingEnabled(enabled);
            ((TextCheckCell) view).setChecked(enabled);
        } else if (position == ROW_FAST_HANDOVER) {
            boolean enabled = !HgramConfig.isFastNetworkHandoverEnabled();
            HgramConfig.setFastNetworkHandoverEnabled(enabled);
            ((TextCheckCell) view).setChecked(enabled);
            if (enabled) {
                for (int account : SharedConfig.activeAccounts) {
                    HgramDcAddressCache.prepareAccount(account);
                    ConnectionsManager.getInstance(account).resumeNetworkMaybe();
                }
            }
        } else if (position == ROW_DECOY_PASSCODE) {
            showDecoyPasscodeMenu();
        } else if (position == ROW_ALLOW_SCREENSHOTS) {
            boolean enabled = !HgramConfig.isAllowScreenshotsEnabled();
            HgramConfig.setAllowScreenshotsEnabled(enabled);
            if (LaunchActivity.instance != null) {
                FlagSecureReason.refresh(LaunchActivity.instance.getWindow());
            }
            ((TextCheckCell) view).setChecked(enabled);
        } else if (position == ROW_HAPTIC_TOGGLE) {
            boolean enabled = !HgramConfig.isHapticEnabled();
            HgramConfig.setHapticEnabled(enabled);
            ((TextCheckCell) view).setChecked(enabled);
            notifyRow(ROW_HAPTIC_TOGGLE);
            for (int i = 0; i < 15; i++) notifyRow(ROW_HAPTIC_BASE + i);
        } else if (position >= ROW_HAPTIC_BASE && position < ROW_HAPTIC_BASE + 15) {
            showHapticDialog(HgramHapticProfile.PLACES[position - ROW_HAPTIC_BASE]);
        } else if (position == ROW_SUMMARIZER_ENABLE) {
            HgramConfig.setSummarizerEnabled(!HgramConfig.isSummarizerEnabled());
            ((TextCheckCell) view).setChecked(HgramConfig.isSummarizerEnabled());
            notifyRow(ROW_SUMMARIZER_ENABLE);
            for (int row = ROW_SUMMARIZER_TRIGGER; row <= ROW_SUMMARIZER_PROMPT; row++) notifyRow(row);
        } else if (position == ROW_SUMMARIZER_TRIGGER) {
            showSummarizerTriggerDialog();
        } else if (position == ROW_SUMMARIZER_KEY) {
            showSummarizerKeyDialog();
        } else if (position == ROW_SUMMARIZER_MODEL) {
            showSummarizerModelDialog();
        } else if (position == ROW_SUMMARIZER_PROMPT) {
            showSummarizerPromptDialog();
        } else if (position == ROW_STT_ENGINE) {
            showSttEngineDialog();
        } else if (position == ROW_VOSK_MODELS) {
            showVoskModelsDialog();
        } else if (position == ROW_SHERPA_MODEL) {
            toggleSherpaModel();
        } else if (position == ROW_GIGAAM_MODEL) {
            toggleGigaAmModel();
        } else if (position == ROW_SHERPA_DEVICE) {
            showSherpaDeviceDialog();
        } else if (position == ROW_WEAK_NETWORK) {
            showWeakNetworkDialog();
        } else if (position == ROW_NOTIF_MEDIA_STYLE) {
            showNotifMediaStyleDialog();
        } else if (position == ROW_FEED_KEYWORDS) {
            showFeedKeywordsDialog();
        } else if (position == ROW_RESET_HGRAM_SETTINGS) {
            showResetHgramSettingsDialog();
        }
        invalidateSearchTextCache();
    }

    @Override
    public ActionBar createActionBar(Context context) {
        final ActionBar actionBar = super.createActionBar(context);
        final ActionBarMenu menu = actionBar.createMenu();
        searchItem = menu.addItem(SEARCH_MENU_ITEM, R.drawable.outline_header_search)
                .setIsSearchField(true)
                .setActionBarMenuItemSearchListener(new ActionBarMenuItem.ActionBarMenuItemSearchListener() {
                    @Override
                    public void onSearchExpand() {
                        searching = true;
                        searchQuery = "";
                        rebuildVisibleRows();
                    }

                    @Override
                    public void onSearchCollapse() {
                        searching = false;
                        searchQuery = "";
                        rebuildVisibleRows();
                    }

                    @Override
                    public void onTextChanged(EditText editText) {
                        if (suppressSearchCallback) {
                            return;
                        }
                        searchQuery = editText.getText().toString();
                        rebuildVisibleRows();
                    }
                });
        searchItem.setSearchFieldHint(LocaleController.getString(R.string.HgramSearchHint));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    if (searchItem != null && searchItem.isSearchFieldVisible()) {
                        searchItem.toggleSearch(false);
                    } else {
                        finishFragment();
                    }
                } else if (id == SEARCH_MENU_ITEM && searchItem != null) {
                    searchItem.openSearch(true);
                }
            }
        });
        return actionBar;
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        if (searchItem != null && searchItem.isSearchFieldVisible()) {
            searchItem.toggleSearch(false);
            return true;
        }
        return super.onBackPressed(invoked);
    }

    private int rowAt(int position) {
        return position >= 0 && position < visibleRows.length ? visibleRows[position] : ROW_SEARCH_EMPTY;
    }

    private void rebuildVisibleRows() {
        rebuildVisibleRows(false);
    }

    private void rebuildVisibleRows(boolean preserveScroll) {
        final int firstVisible = preserveScroll && layoutManager != null
                ? layoutManager.findFirstVisibleItemPosition() : -1;
        final String query = searchQuery == null ? "" : searchQuery.trim();
        final String engine = HgramConfig.getSttEngine();
        visibleRows = computeVisibleRows(engine, query);
        rowCount = visibleRows.length;
        if (listAdapter != null) {
            notifyAllRows();
        }
        if (listView != null) {
            if (preserveScroll && firstVisible >= 0) {
                listView.scrollToPosition(firstVisible);
            } else {
                listView.scrollToPosition(0);
            }
        }
    }

    private int[] computeVisibleRows(String engine, String query) {
        if (query == null || query.trim().isEmpty()) {
            return buildBaseVisibleRows(engine, HgramConfig.isOfflineTranscriptionEnabled());
        }
        final boolean offlineEnabled = HgramConfig.isOfflineTranscriptionEnabled();
        final boolean[] keep = new boolean[ROWS.length];
        for (int i = 0; i < ROWS.length; i++) {
            final int row = ROWS[i];
            if (isHeaderRow(row)
                    || row == ROW_RESET_HGRAM_SETTINGS
                    || !HgramSettingsSearch.isRowVisible(row, engine, offlineEnabled)) {
                continue;
            }
            keep[i] = HgramSettingsSearch.matches(row, query, searchTextForRow(row));
        }
        return buildSearchVisibleRows(keep);
    }

    /**
     * Recomputes the visible rows after the offline transcription toggle without touching the
     * adapter from inside the click callback. Runs on the next list message so the RecyclerView
     * is not computing layout, and keeps the first visible item in place.
     */
    private void rebuildVisibleRowsAfterToggle() {
        if (listView == null) return;
        listView.post(() -> {
            if (listAdapter == null || isFinished) return;
            if (listView.isComputingLayout()) {
                rebuildVisibleRowsAfterToggle();
                return;
            }
            final int firstVisible = layoutManager != null
                    ? layoutManager.findFirstVisibleItemPosition() : -1;
            final String query = searchQuery == null ? "" : searchQuery.trim();
            final String engine = HgramConfig.getSttEngine();
            final int[] oldRows = visibleRows;
            final int[] newRows = computeVisibleRows(engine, query);
            if (Arrays.equals(oldRows, newRows)) return;
            final int anchorRow = firstVisible >= 0 && firstVisible < oldRows.length
                    ? oldRows[firstVisible] : -1;
            final View anchorView = layoutManager != null
                    ? layoutManager.findViewByPosition(firstVisible) : null;
            final int anchorOffset = anchorView != null
                    ? layoutManager.getDecoratedTop(anchorView) - listView.getPaddingTop() : 0;
            int targetIndex = findRowIndex(newRows, anchorRow);
            final boolean targetIndexWasFallback = targetIndex < 0;
            if (targetIndexWasFallback) {
                targetIndex = nearestSurvivingRow(oldRows, newRows, firstVisible);
            }
            final int singleDiff = singleInsertOrRemoveIndex(oldRows, newRows);
            final boolean singleDiffApplied = singleDiff >= 0;
            visibleRows = newRows;
            rowCount = newRows.length;
            if (singleDiffApplied) {
                final boolean inserted = newRows.length > oldRows.length;
                if (inserted) {
                    listAdapter.notifyItemInserted(singleDiff);
                } else {
                    listAdapter.notifyItemRemoved(singleDiff);
                }
            } else {
                listAdapter.notifyDataSetChanged();
            }
            if (targetIndex >= 0
                    && (targetIndex != firstVisible || targetIndexWasFallback || !singleDiffApplied)) {
                layoutManager.scrollToPositionWithOffset(targetIndex,
                        targetIndexWasFallback ? 0 : anchorOffset);
            }
        });
    }

    static int findRowIndex(int[] rows, int row) {
        if (rows == null || row < 0) return -1;
        for (int i = 0; i < rows.length; i++) {
            if (rows[i] == row) return i;
        }
        return -1;
    }

    static int nearestSurvivingRow(int[] oldRows, int[] newRows, int oldIndex) {
        if (oldRows == null || newRows == null || oldIndex < 0 || oldIndex >= oldRows.length) {
            return -1;
        }
        for (int distance = 0; distance < oldRows.length; distance++) {
            final int left = oldIndex - distance;
            if (left >= 0 && findRowIndex(newRows, oldRows[left]) >= 0) {
                return oldRows[left];
            }
            final int right = oldIndex + distance;
            if (distance > 0 && right < oldRows.length
                    && findRowIndex(newRows, oldRows[right]) >= 0) {
                return oldRows[right];
            }
        }
        return -1;
    }

    private static int singleInsertOrRemoveIndex(int[] oldRows, int[] newRows) {
        final int delta = newRows.length - oldRows.length;
        if (delta != 1 && delta != -1) return -1;
        final int minLength = Math.min(oldRows.length, newRows.length);
        int prefix = 0;
        while (prefix < minLength && oldRows[prefix] == newRows[prefix]) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < minLength - prefix
                && oldRows[oldRows.length - 1 - suffix] == newRows[newRows.length - 1 - suffix]) {
            suffix++;
        }
        return prefix + suffix == minLength ? prefix : -1;
    }

    static int[] buildSearchVisibleRows(boolean[] keep) {
        final ArrayList<Integer> selected = new ArrayList<>();
        for (int i = 0; i < ROWS.length; i++) {
            final int row = ROWS[i];
            if (isHeaderRow(row)) {
                boolean hasNext = false;
                for (int j = i + 1; j < ROWS.length && !isHeaderRow(ROWS[j]); j++) {
                    if (keep[j]) {
                        hasNext = true;
                        break;
                    }
                }
                if (hasNext) {
                    selected.add(row);
                }
            } else if (keep[i]) {
                selected.add(row);
            }
        }
        if (selected.isEmpty()) {
            selected.add(ROW_SEARCH_EMPTY);
        }
        final int[] result = new int[selected.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = selected.get(i);
        }
        return result;
    }

    static int rowViewType(int row) {
        if (row == ROW_SEARCH_EMPTY || row == ROW_GIGAAM_INFO) return TYPE_INFO_PRIVACY;
        if (row == ROW_HEADER_CHAT || row == ROW_HEADER_NETWORK || row == ROW_HEADER_RECOGNITION
                || row == ROW_HEADER_FEED || row == ROW_HEADER_AUDIO || row == ROW_HEADER_PRIVACY
                || row == ROW_HEADER_OTHER || row == ROW_HEADER_HAPTICS) {
            return TYPE_HEADER;
        }
        if (row == ROW_DOWNLOAD_THREADS) return TYPE_DOWNLOAD_THREADS;
        if (row == ROW_UPLOAD_THREADS) return TYPE_UPLOAD_THREADS;
        if (isToggle(row)) return TYPE_CHECK;
        return TYPE_SETTINGS;
    }

    static int[] buildBaseVisibleRows(String engine) {
        return buildBaseVisibleRows(engine, true);
    }

    static int[] buildBaseVisibleRows(String engine, boolean offlineEnabled) {
        final ArrayList<Integer> selected = new ArrayList<>();
        for (int row : ROWS) {
            if (HgramSettingsSearch.isRowVisible(row, engine, offlineEnabled)) {
                selected.add(row);
            }
        }
        final int[] result = new int[selected.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = selected.get(i);
        }
        return result;
    }

    static boolean isHeaderRow(int row) {
        return row == ROW_HEADER_CHAT || row == ROW_HEADER_FEED || row == ROW_HEADER_AUDIO
                || row == ROW_HEADER_RECOGNITION || row == ROW_HEADER_PRIVACY
                || row == ROW_HEADER_NETWORK || row == ROW_HEADER_OTHER
                || row == ROW_HEADER_HAPTICS;
    }

    private void invalidateSearchTextCache() {
        searchTextCache.clear();
    }

    private String searchTextForRow(int row) {
        final String cached = searchTextCache.get(row);
        if (cached != null) {
            return cached;
        }
        final String title = rowTitle(row);
        final String value = rowValue(row);
        final String text = title + " " + value + " " + HgramSettingsSearch.keywords(row);
        searchTextCache.put(row, text);
        return text;
    }

    private String rowTitle(int row) {
        switch (row) {
            case ROW_SHORT_MERGE: return LocaleController.getString(R.string.HgramShortMerge);
            case ROW_COMPACT_ALBUMS: return LocaleController.getString(R.string.HgramRoadmapAlbums);
            case ROW_PIN_TO_SCREEN: return LocaleController.getString(R.string.HgramRoadmapPinToScreen);
            case ROW_SIMILAR_POST_DEDUPLICATION: return LocaleController.getString(R.string.HgramRoadmapDedup);
            case ROW_CLEAN_FEED_FILTER: return LocaleController.getString(R.string.HgramRoadmapFeedCleanup);
            case ROW_AUTO_LOUDNESS: return LocaleController.getString(R.string.HgramRoadmapLoudness);
            case ROW_OFFLINE_TRANSCRIPTION: return LocaleController.getString(R.string.HgramRoadmapTranscription);
            case ROW_STT_ENGINE: return LocaleController.getString(R.string.HgramSttEngine);
            case ROW_VOSK_MODELS: return LocaleController.getString(R.string.HgramVoskModels);
            case ROW_SHERPA_MODEL: return LocaleController.getString(R.string.HgramSherpaModel);
            case ROW_GIGAAM_MODEL: return LocaleController.getString(R.string.HgramGigaAmModel);
            case ROW_GIGAAM_INFO: return gigaAmInfoText();
            case ROW_SHERPA_DEVICE: return LocaleController.getString(R.string.HgramSherpaDevice);
            case ROW_ANTI_PEEK: return LocaleController.getString(R.string.HgramRoadmapAntiPeek);
            case ROW_ALLOW_SCREENSHOTS: return LocaleController.getString(R.string.HgramAllowScreenshots);
            case ROW_DECOY_PASSCODE: return LocaleController.getString(R.string.HgramDecoyPasscode);
            case ROW_WEAK_NETWORK: return LocaleController.getString(R.string.HgramWeakNetwork);
            case ROW_NOTIF_MEDIA_STYLE: return LocaleController.getString(R.string.HgramNotifMediaStyle);
            case ROW_NOTIFICATION_DEBOUNCE: return LocaleController.getString(R.string.HgramNotificationDebounce);
            case ROW_FRAME_THROTTLING: return LocaleController.getString(R.string.HgramRoadmapFrameThrottling);
            case ROW_FAST_HANDOVER: return LocaleController.getString(R.string.HgramFastNetworkHandover);
            case ROW_DOWNLOAD_THREADS:
                return LocaleController.getString(R.string.HgramDownloadThreads);
            case ROW_UPLOAD_THREADS:
                return LocaleController.getString(R.string.HgramUploadThreads);
            case ROW_FEED_KEYWORDS: return LocaleController.getString(R.string.HgramFeedKeywords);
            case ROW_SUMMARIZER_ENABLE: return LocaleController.getString(R.string.HgramSummarizerEnable);
            case ROW_SUMMARIZER_TRIGGER: return LocaleController.getString(R.string.HgramSummarizerTrigger);
            case ROW_SUMMARIZER_KEY: return LocaleController.getString(R.string.HgramSummarizerKey);
            case ROW_SUMMARIZER_MODEL: return LocaleController.getString(R.string.HgramSummarizerModel);
            case ROW_SUMMARIZER_PROMPT: return LocaleController.getString(R.string.HgramSummarizerPrompt);
            case ROW_RESET_HGRAM_SETTINGS: return LocaleController.getString(R.string.HgramResetSettings);
            case ROW_HEADER_HAPTICS: return LocaleController.getString(R.string.HgramHeaderHaptics);
            case ROW_HAPTIC_TOGGLE: return LocaleController.getString(R.string.HgramHapticEnabled);
            default:
                if (row >= ROW_HAPTIC_BASE && row < ROW_HAPTIC_BASE + 15) {
                    return LocaleController.getString(hapticPlaceTitle(row - ROW_HAPTIC_BASE));
                }
                return "";
        }
    }

    private int toggleDescriptionRes(int row) {
        switch (row) {
            case ROW_SHORT_MERGE: return R.string.HgramShortMergeInfo;
            case ROW_COMPACT_ALBUMS: return R.string.HgramCompactAlbumsInfo;
            case ROW_PIN_TO_SCREEN: return R.string.HgramPinToScreenInfo;
            case ROW_SIMILAR_POST_DEDUPLICATION: return R.string.HgramDedupInfo;
            case ROW_CLEAN_FEED_FILTER: return R.string.HgramCleanFeedInfo;
            case ROW_AUTO_LOUDNESS: return R.string.HgramAutoLoudnessInfo;
            case ROW_OFFLINE_TRANSCRIPTION: return R.string.HgramOfflineTranscriptionInfo;
            case ROW_ALLOW_SCREENSHOTS: return R.string.HgramAllowScreenshotsInfo;
            case ROW_FAST_HANDOVER: return R.string.HgramFastHandoverInfo;
            case ROW_NOTIFICATION_DEBOUNCE: return R.string.HgramNotificationDebounceInfo;
            case ROW_FRAME_THROTTLING: return R.string.HgramFrameThrottlingInfo;
            case ROW_HAPTIC_TOGGLE: return R.string.HgramHapticEnabledInfo;
            default: return 0;
        }
    }

    private String rowValue(int row) {
        if (isToggle(row)) {
            return toggleDescription(row, toggleDescriptionRes(row));
        }
        switch (row) {
            case ROW_STT_ENGINE: return sttEngineStatus();
            case ROW_VOSK_MODELS: return voskStatusText();
            case ROW_SHERPA_MODEL: return sherpaStatusText();
            case ROW_GIGAAM_MODEL: return gigaAmStatusText();
            case ROW_SHERPA_DEVICE: return sherpaDeviceName();
            case ROW_ANTI_PEEK: return antiPeekStatusText();
            case ROW_WEAK_NETWORK: return weakNetworkName();
            case ROW_NOTIF_MEDIA_STYLE: return notifMediaStyleName();
            case ROW_DECOY_PASSCODE: return LocaleController.getString(PasscodeHelper.isHgramDecoyConfigured() ? R.string.HgramConfigured : R.string.HgramNotConfigured);
            case ROW_FEED_KEYWORDS: return HgramConfig.getCustomFeedKeywords();
            case ROW_SUMMARIZER_TRIGGER: return LocaleController.formatString(R.string.HgramSummarizerWordsValue, HgramConfig.getSummarizerMinWords());
            case ROW_SUMMARIZER_KEY: {
                String key = HgramConfig.getSummarizerKey();
                if (key.isEmpty()) return LocaleController.getString(R.string.HgramSummarizerNotSet);
                return maskedSummarizerKey(key);
            }
            case ROW_SUMMARIZER_MODEL: {
                String model = HgramConfig.getSummarizerModel();
                return model.isEmpty() ? LocaleController.getString(R.string.HgramSummarizerModelNotSelected) : model;
            }
            case ROW_SUMMARIZER_PROMPT: {
                String prompt = HgramConfig.getSummarizerSystemPrompt();
                return LocaleController.getString(prompt.equals(xyz.nextalone.nagram.hgram.HgramSummarizer.DEFAULT_PROMPT)
                        ? R.string.HgramSummarizerPromptDefault : R.string.HgramSummarizerPromptChanged);
            }
            case ROW_DOWNLOAD_THREADS: return String.valueOf(HgramConfig.getDownloadThreads());
            case ROW_UPLOAD_THREADS: return String.valueOf(HgramConfig.getUploadThreads());
            case ROW_HAPTIC_TOGGLE: return HgramConfig.isHapticEnabled() ? "" : LocaleController.getString(R.string.HgramHapticDisabled);
            default:
                if (row >= ROW_HAPTIC_BASE && row < ROW_HAPTIC_BASE + 15) {
                    return HgramHapticProfile.of(HgramHapticProfile.PLACES[row - ROW_HAPTIC_BASE]).summary();
                }
                return "";
        }
    }


    private String sttEngineStatus() {
        final String engine = HgramConfig.getSttEngine();
        final int engineRes = HgramConfig.STT_ENGINE_GIGAAM.equals(engine)
                ? R.string.HgramSttEngineGigaAm
                : HgramConfig.STT_ENGINE_SHERPA.equals(engine)
                ? R.string.HgramSttEngineSherpa : R.string.HgramSttEngineVosk;
        final boolean installed = HgramConfig.STT_ENGINE_GIGAAM.equals(engine)
                ? HgramGigaAmRecognizer.isInstalled()
                : HgramConfig.STT_ENGINE_SHERPA.equals(engine)
                ? HgramSherpaRecognizer.isInstalled()
                : HgramVoskModels.isInstalled(HgramVoskModels.LANG_RU)
                || HgramVoskModels.isInstalled(HgramVoskModels.LANG_EN);
        return LocaleController.getString(engineRes) + " · "
                + LocaleController.getString(installed ? R.string.HgramModelInstalled : R.string.HgramModelNotInstalled);
    }

    @Override
    protected boolean onItemLongClick(View view, int position, float x, float y) {
        final int row = rowAt(position);
        if (row == ROW_SEARCH_EMPTY || isHeaderRow(row)) {
            return false;
        }
        final int hint = HgramSettingsHints.hintRes(row);
        final String title = rowTitle(row);
        if (hint == 0 || title.isEmpty() || getParentActivity() == null) {
            return false;
        }
        HgramHaptics.play(view, HgramHapticProfile.PLACE_LONG_PRESS);
        new AlertDialog.Builder(getParentActivity())
                .setTitle(title)
                .setMessage(LocaleController.getString(hint))
                .setPositiveButton(LocaleController.getString(R.string.OK), null)
                .show();
        return true;
    }

    private static final int[] HAPTIC_TITLES = {
            R.string.HgramHapticToggles, R.string.HgramHapticListItems,
            R.string.HgramHapticLongPress, R.string.HgramHapticMessageActions,
            R.string.HgramHapticVoiceRecord, R.string.HgramHapticEmojiStickers,
            R.string.HgramHapticAlbumsSwipe, R.string.HgramHapticStories,
            R.string.HgramHapticCalls, R.string.HgramHapticCameraPaint,
            R.string.HgramHapticPasscodeLock, R.string.HgramHapticAntiPeek,
            R.string.HgramHapticSecretSpace, R.string.HgramHapticTextCursor,
            R.string.HgramHapticMiniApps
    };

    private int hapticPlaceTitle(int index) {
        return index >= 0 && index < HAPTIC_TITLES.length ? HAPTIC_TITLES[index] : R.string.HgramHapticEnabled;
    }

    private void showSummarizerTriggerDialog() {
        if (getParentActivity() == null) return;
        final HgramSubmenuDialog.Builder builder =
                new HgramSubmenuDialog.Builder(getParentActivity(), resourcesProvider)
                        .title(LocaleController.getString(R.string.HgramSummarizerTrigger));
        final HgramSubmenuDialog.SliderRow slider = builder.slider(
                LocaleController.getString(R.string.HgramSummarizerTrigger), null,
                10, 500, 5, HgramConfig.getSummarizerMinWords(),
                value -> LocaleController.formatString(R.string.HgramSummarizerWordsValue, value));
        builder.footerButton(LocaleController.getString(R.string.Cancel), null, true);
        builder.footerButton(LocaleController.getString(R.string.Save), () -> {
            HgramConfig.setSummarizerMinWords(slider.getValue());
            notifyRow(ROW_SUMMARIZER_TRIGGER);
        }, true);
        builder.show();
    }

    private void styleDialogInput(EditText input) {
        input.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        input.setHintTextColor(Theme.getColor(Theme.key_dialogTextGray, resourcesProvider));
        input.setBackground(Theme.createEditTextDrawable(getParentActivity(), true));
        int pad = AndroidUtilities.dp(10);
        input.setPadding(pad, pad, pad, pad);
    }

    private static final String SUMMARIZER_KEY_HINT = "sk-or-...";

    private static String maskedSummarizerKey(String key) {
        if (key == null || key.isEmpty() || key.length() <= 10) return "sk-or-…";
        return key.substring(0, 6) + "…" + key.substring(key.length() - 4);
    }

    private void showSummarizerKeyDialog() {
        if (getParentActivity() == null) return;
        final String stored = HgramConfig.getSummarizerKey();
        final String storedHint = stored.isEmpty() ? SUMMARIZER_KEY_HINT : maskedSummarizerKey(stored);

        final EditText input = new EditText(getParentActivity());
        input.setHint(storedHint);
        input.setSingleLine(true);
        styleDialogInput(input);

        final TextView toggle = new TextView(getParentActivity());
        toggle.setText(LocaleController.getString(R.string.Show));
        toggle.setTextColor(Theme.getColor(Theme.key_dialogTextBlue, resourcesProvider));
        final int togglePad = AndroidUtilities.dp(8);
        toggle.setPadding(togglePad, togglePad, togglePad, togglePad);
        toggle.setOnClickListener(v -> {
            if (toggle.getText().toString().equals(LocaleController.getString(R.string.Hide))) {
                input.setText("");
                input.setHint(storedHint);
                toggle.setText(LocaleController.getString(R.string.Show));
            } else {
                input.setText(stored);
                input.setSelection(input.getText().length());
                toggle.setText(LocaleController.getString(R.string.Hide));
            }
        });

        final LinearLayout container = new LinearLayout(getParentActivity());
        container.setOrientation(LinearLayout.VERTICAL);
        container.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        container.addView(toggle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        AlertDialog dialog = new AlertDialog.Builder(getParentActivity())
                .setTitle(LocaleController.getString(R.string.HgramSummarizerKey))
                .setView(container)
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .setNeutralButton(LocaleController.getString(R.string.HgramSummarizerDeleteKey), (d, w) -> {
                    HgramConfig.setSummarizerKey("");
                    invalidateSummarizerModelsRequest();
                    notifyRow(ROW_SUMMARIZER_KEY);
                })
                .setPositiveButton(LocaleController.getString(R.string.Save), (d, w) -> {
                    final String value = input.getText().toString().trim();
                    if (!value.isEmpty()) {
                        HgramConfig.setSummarizerKey(value);
                        notifyRow(ROW_SUMMARIZER_KEY);
                    }
                }).create();
        dialog.show();
    }

    private void showSummarizerPromptDialog() {
        if (getParentActivity() == null) return;
        final EditText input = new EditText(getParentActivity());
        input.setMinLines(6);
        input.setMaxLines(14);
        input.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        input.setFilters(new android.text.InputFilter[]{
                new android.text.InputFilter.LengthFilter(
                        xyz.nextalone.nagram.hgram.HgramSummarizer.MAX_SYSTEM_PROMPT_CHARS)});
        input.setText(HgramConfig.getSummarizerSystemPrompt());
        styleDialogInput(input);
        new AlertDialog.Builder(getParentActivity())
                .setTitle(LocaleController.getString(R.string.HgramSummarizerPrompt))
                .setView(input)
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .setNeutralButton(LocaleController.getString(R.string.HgramSummarizerResetPrompt), (d, w) -> {
                    HgramConfig.setSummarizerSystemPrompt(xyz.nextalone.nagram.hgram.HgramSummarizer.DEFAULT_PROMPT);
                    notifyRow(ROW_SUMMARIZER_PROMPT);
                })
                .setPositiveButton(LocaleController.getString(R.string.Save), (d, w) -> {
                    HgramConfig.setSummarizerSystemPrompt(input.getText().toString());
                    notifyRow(ROW_SUMMARIZER_PROMPT);
                }).show();
    }

    private boolean isHgramActivityAlive() {
        final android.app.Activity parent = getParentActivity();
        return parent != null && !parent.isFinishing() && !parent.isDestroyed();
    }

    private void invalidateSummarizerModelsRequest() {
        summarizerModelsRequestId++;
        summarizerModelsLoading = false;
        if (summarizerModelsProgress != null) {
            summarizerModelsProgress.dismiss();
            summarizerModelsProgress = null;
        }
    }

    private void showSummarizerModelDialog() {
        if (getParentActivity() == null) return;
        if (summarizerModelsLoading) return;
        summarizerModelsLoading = true;
        final int requestId = ++summarizerModelsRequestId;
        final android.app.ProgressDialog progress = new android.app.ProgressDialog(getParentActivity());
        summarizerModelsProgress = progress;
        progress.setMessage(LocaleController.getString(R.string.HgramSummarizerLoadingModels));
        progress.setOnCancelListener(d -> invalidateSummarizerModelsRequest());
        progress.show();
        new Thread(() -> {
            final List<HgramSummarizerModel> models = tw.nekomimi.nekogram.transtale.source.LLMTranslator.INSTANCE
                    .fetchOpenRouterModelsBlocking();
            AndroidUtilities.runOnUIThread(() -> {
                if (requestId == summarizerModelsRequestId && isHgramActivityAlive()) {
                    if (progress == summarizerModelsProgress) {
                        summarizerModelsProgress = null;
                    }
                    summarizerModelsLoading = false;
                    progress.dismiss();
                    if (models == null) {
                        Toast.makeText(getParentActivity(), R.string.HgramSummarizerModelsError, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (models.isEmpty()) {
                        Toast.makeText(getParentActivity(), R.string.HgramSummarizerModelsEmpty, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    showSummarizerModelPicker(models);
                    return;
                }
                progress.dismiss();
                if (progress == summarizerModelsProgress) {
                    summarizerModelsProgress = null;
                    summarizerModelsLoading = false;
                }
            });
        }, "HgramSummarizerModels").start();
    }

    private void showSummarizerModelPicker(List<HgramSummarizerModel> models) {
        if (getParentActivity() == null) return;
        java.util.Locale locale = LocaleController.getInstance().getCurrentLocale();
        if (locale == null) {
            locale = java.util.Locale.getDefault();
        }
        final int estimated = xyz.nextalone.nagram.hgram.HgramSummarizer.estimateSummariesPerMillion(
                HgramConfig.getSummarizerMinWords(), HgramConfig.getSummarizerSystemPrompt(), locale);
        HgramModelPickerDialog.show(getParentActivity(), models, HgramConfig.getSummarizerModel(),
                HgramConfig.isSummarizerShowPaid(), estimated, new HgramModelPickerDialog.Listener() {
                    @Override
                    public void onModelSelected(String modelId) {
                        HgramConfig.setSummarizerModel(modelId);
                        notifyRow(ROW_SUMMARIZER_MODEL);
                    }

                    @Override
                    public void onShowPaidChanged(boolean showPaid) {
                        HgramConfig.setSummarizerShowPaid(showPaid);
                    }
                });
    }

    private void showHapticDialog(String place) {
        if (getParentActivity() == null) return;
        final HgramHapticCapabilities capabilities = HgramHapticCapabilities.detect(getParentActivity());
        if (!capabilities.hasVibrator) {
            Toast.makeText(getParentActivity(), R.string.HgramHapticUnavailable, Toast.LENGTH_SHORT).show();
            return;
        }
        final HgramHapticProfile initial = HgramHapticProfile.of(place);
        final int index = java.util.Arrays.asList(HgramHapticProfile.PLACES).indexOf(place);
        final HgramSubmenuDialog.Builder builder =
                new HgramSubmenuDialog.Builder(getParentActivity(), resourcesProvider)
                        .title(LocaleController.getString(hapticPlaceTitle(index)));
        if (!capabilities.systemHapticsEnabled) {
            builder.message(LocaleController.getString(R.string.HgramHapticSystemOff));
        }
        final HgramSubmenuDialog.SliderRow intensity = builder.slider(
                LocaleController.getString(R.string.HgramHapticStrength),
                capabilities.showIntensity() ? capabilities.intensityHint() : null,
                1, 255, 1, initial.intensity,
                value -> Math.round(value / 255f * 100f) + "%");
        intensity.setVisible(capabilities.showIntensity());
        final HgramSubmenuDialog.SliderRow duration = builder.slider(
                LocaleController.getString(R.string.HgramHapticDuration), null,
                10, 300, 5, initial.durationMs, value -> value + " ms");
        final HgramSubmenuDialog.SliderRow pattern = builder.slider(
                LocaleController.getString(R.string.HgramHapticRepeats), null,
                0, 2, 1, initial.pattern, value -> String.valueOf(value + 1));
        pattern.setVisible(capabilities.showPattern());
        final HgramSubmenuDialog.SliderRow gap = builder.slider(
                LocaleController.getString(R.string.HgramHapticGap), null,
                20, 200, 10, initial.gapMs, value -> value + " ms");
        gap.setVisible(capabilities.showPattern());
        pattern.setOnValueChanged(value -> gap.setEnabled(value > 0));
        gap.setEnabled(initial.pattern > 0);

        builder.footerButton(LocaleController.getString(R.string.HgramHapticPreview), () -> {
            HgramHapticProfile temp = new HgramHapticProfile(
                    intensity.getValue(), duration.getValue(), pattern.getValue(), gap.getValue());
            HgramHaptics.preview(getParentActivity(), temp);
            Toast.makeText(getParentActivity(), HgramHaptics.lastPath(), Toast.LENGTH_SHORT).show();
        });
        builder.footerButton(LocaleController.getString(R.string.HgramHapticReset), () -> {
            HgramHapticProfile defaults = HgramHapticProfile.defaults(place);
            intensity.setValue(defaults.intensity, false);
            duration.setValue(defaults.durationMs, false);
            pattern.setValue(defaults.pattern, false);
            gap.setValue(defaults.gapMs, false);
            gap.setEnabled(defaults.pattern > 0);
        });
        builder.footerButton(LocaleController.getString(R.string.Close), null, true);
        builder.footerButton(LocaleController.getString(R.string.HgramHapticSave), () -> {
            new HgramHapticProfile(intensity.getValue(), duration.getValue(),
                    pattern.getValue(), gap.getValue()).save(place);
            if (index >= 0) notifyRow(ROW_HAPTIC_BASE + index);
        }, true);
        builder.show();
    }

    @Override
    protected BaseListAdapter createAdapter(Context context) {
        return new ListAdapter(context);
    }

    @Override
    protected String getActionBarTitle() {
        return LocaleController.getString(R.string.HgramSettingsTitle);
    }

    private boolean hasAnyModelInstalled() {
        return HgramOfflineTranscriber.hasAnyModelInstalled() || HgramSherpaRecognizer.isInstalled() || HgramGigaAmRecognizer.isInstalled();
    }

    private void showFeedKeywordsDialog() {
        EditText input = new EditText(getParentActivity());
        input.setHint(LocaleController.getString(R.string.HgramFeedKeywordsHint));
        input.setMinLines(3);
        input.setMaxLines(8);
        input.setTextSize(16);
        input.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        input.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(14), AndroidUtilities.dp(16), AndroidUtilities.dp(14));
        input.setBackground(new GradientDrawable() {{
            setColor(0x0f808080);
            setCornerRadius(AndroidUtilities.dp(12));
            setStroke(AndroidUtilities.dp(1), 0x22808080);
        }});
        input.setText(HgramConfig.getCustomFeedKeywords());
        AlertDialog dialog = new AlertDialog.Builder(getParentActivity())
                .setTitle(LocaleController.getString(R.string.HgramFeedKeywords))
                .setMessage(LocaleController.getString(R.string.HgramFeedKeywordsHint))
                .setView(input)
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .setNeutralButton(LocaleController.getString(R.string.Clear), (d, which) -> {
                    HgramConfig.setCustomFeedKeywords("");
                    notifyAllRows();
                })
                .setPositiveButton(LocaleController.getString(R.string.Save), (d, which) -> {
                    HgramConfig.setCustomFeedKeywords(input.getText().toString());
                    notifyAllRows();
                }).create();
        dialog.setOnShowListener(d -> input.requestFocus());
        dialog.show();
    }

    private class ListAdapter extends BaseListAdapter {
        ListAdapter(Context context) {
            super(context);
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull android.view.ViewGroup parent, int viewType) {
            if (viewType == TYPE_DOWNLOAD_THREADS) {
                return new RecyclerListView.Holder(new HgramDownloadThreadsCell(parent.getContext(), resourcesProvider, false));
            }
            if (viewType == TYPE_UPLOAD_THREADS) {
                return new RecyclerListView.Holder(new HgramDownloadThreadsCell(parent.getContext(), resourcesProvider, true));
            }
            RecyclerView.ViewHolder holder = super.onCreateViewHolder(parent, viewType);
            if (holder.itemView instanceof HeaderCell) {
                // Headers are labels, not rounded section cards. Keeping them out of
                // setSections() grouping lets the rows below form the section.
                holder.itemView.setTag(RecyclerListView.TAG_NOT_SECTION);
                HeaderCell header = (HeaderCell) holder.itemView;
                header.setTextSize(16);
                header.setTextColor(Theme.multAlpha(
                        Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider), 0.55f));
                header.setTopMargin(12);
                header.setBottomMargin(2);
                header.setHeight(42);
            }
            if (holder.itemView instanceof TextSettingsCell) {
                // The debug build lets the value overlap the title, keep the value inside its own half.
                ((TextSettingsCell) holder.itemView).setBetterLayout(false);
            }
            return holder;
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position, boolean partial, boolean divider) {
            position = rowAt(position);
            if (isToggle(position)) {
                bindToggle((TextCheckCell) holder.itemView, position, divider);
                return;
            }
            if (position >= ROW_HAPTIC_BASE && position < ROW_HAPTIC_BASE + 15) {
                final String place = HgramHapticProfile.PLACES[position - ROW_HAPTIC_BASE];
                ((TextSettingsCell) holder.itemView).setTextAndValue(
                        LocaleController.getString(hapticPlaceTitle(position - ROW_HAPTIC_BASE)),
                        HgramHaptics.isEnabled()
                                ? HgramHapticProfile.of(place).summary()
                                : LocaleController.getString(R.string.HgramHapticDisabled),
                        divider);
                return;
            }
            switch (position) {
                case ROW_HEADER_CHAT:
                case ROW_HEADER_NETWORK:
                case ROW_HEADER_RECOGNITION:
                    int header = position == ROW_HEADER_CHAT ? R.string.HgramHeaderChats
                            : position == ROW_HEADER_NETWORK ? R.string.HgramHeaderNetwork : R.string.HgramHeaderRecognition;
                    ((HeaderCell) holder.itemView).setText(LocaleController.getString(header));
                    break;
                case ROW_HEADER_FEED:
                    ((HeaderCell) holder.itemView).setText(LocaleController.getString(R.string.HgramHeaderFeed));
                    break;
                case ROW_FEED_KEYWORDS:
                    String keywords = HgramConfig.getCustomFeedKeywords();
                    ((TextSettingsCell) holder.itemView).setTextAndValue(LocaleController.getString(R.string.HgramFeedKeywords), keywords.isEmpty() ? LocaleController.getString(R.string.HgramFeedKeywordsEmpty) : keywords, divider);
                    break;
                case ROW_SUMMARIZER_TRIGGER:
                case ROW_SUMMARIZER_KEY:
                case ROW_SUMMARIZER_MODEL:
                case ROW_SUMMARIZER_PROMPT:
                    ((TextSettingsCell) holder.itemView).setTextAndValue(rowTitle(position), rowValue(position), divider);
                    break;
                case ROW_HEADER_AUDIO:
                    ((HeaderCell) holder.itemView).setText(LocaleController.getString(R.string.HgramHeaderAudio));
                    break;
                case ROW_HEADER_PRIVACY:
                    ((HeaderCell) holder.itemView).setText(LocaleController.getString(R.string.HgramHeaderPrivacy));
                    break;
                case ROW_ANTI_PEEK:
                    ((TextSettingsCell) holder.itemView).setTextAndValue(
                            LocaleController.getString(R.string.HgramRoadmapAntiPeek),
                            antiPeekStatusText(), divider);
                    break;
                case ROW_HEADER_OTHER:
                    ((HeaderCell) holder.itemView).setText(LocaleController.getString(R.string.HgramHeaderOther));
                    break;
                case ROW_HEADER_HAPTICS:
                    ((HeaderCell) holder.itemView).setText(LocaleController.getString(R.string.HgramHeaderHaptics));
                    break;
                case ROW_DOWNLOAD_THREADS:
                case ROW_UPLOAD_THREADS:
                    ((HgramDownloadThreadsCell) holder.itemView).bind();
                    break;
                case ROW_DECOY_PASSCODE:
                    ((TextSettingsCell) holder.itemView).setTextAndValue(LocaleController.getString(R.string.HgramDecoyPasscode), LocaleController.getString(PasscodeHelper.isHgramDecoyConfigured() ? R.string.HgramConfigured : R.string.HgramNotConfigured), divider);
                    break;
                case ROW_STT_ENGINE:
                    String engine = HgramConfig.getSttEngine();
                    int engineRes = HgramConfig.STT_ENGINE_GIGAAM.equals(engine)
                            ? R.string.HgramSttEngineGigaAm
                            : HgramConfig.STT_ENGINE_SHERPA.equals(engine)
                            ? R.string.HgramSttEngineSherpa
                            : R.string.HgramSttEngineVosk;
                    final boolean installed = HgramConfig.STT_ENGINE_GIGAAM.equals(engine)
                            ? HgramGigaAmRecognizer.isInstalled()
                            : HgramConfig.STT_ENGINE_SHERPA.equals(engine)
                            ? HgramSherpaRecognizer.isInstalled()
                            : HgramVoskModels.isInstalled(HgramVoskModels.LANG_RU)
                            || HgramVoskModels.isInstalled(HgramVoskModels.LANG_EN);
                    ((TextSettingsCell) holder.itemView).setTextAndValue(
                            LocaleController.getString(R.string.HgramSttEngine),
                            LocaleController.getString(engineRes) + " · "
                                    + LocaleController.getString(installed
                                    ? R.string.HgramModelInstalled : R.string.HgramModelNotInstalled),
                            divider);
                    break;
                case ROW_VOSK_MODELS:
                    ((TextSettingsCell) holder.itemView).setTextAndValue(LocaleController.getString(R.string.HgramVoskModels), voskStatusText(), divider);
                    break;
                case ROW_SHERPA_MODEL:
                    ((TextSettingsCell) holder.itemView).setTextAndValue(LocaleController.getString(R.string.HgramSherpaModel), sherpaStatusText(), divider);
                    break;
                case ROW_GIGAAM_MODEL:
                    ((TextSettingsCell) holder.itemView).setTextAndValue(LocaleController.getString(R.string.HgramGigaAmModel), gigaAmStatusText(), divider);
                    break;
                case ROW_SHERPA_DEVICE:
                    ((TextSettingsCell) holder.itemView).setTextAndValue(LocaleController.getString(R.string.HgramSherpaDevice), sherpaDeviceName(), divider);
                    break;
                case ROW_WEAK_NETWORK:
                    ((TextSettingsCell) holder.itemView).setTextAndValue(LocaleController.getString(R.string.HgramWeakNetwork), weakNetworkName(), divider);
                    break;
                case ROW_NOTIF_MEDIA_STYLE:
                    ((TextSettingsCell) holder.itemView).setTextAndValue(LocaleController.getString(R.string.HgramNotifMediaStyle), notifMediaStyleName(), divider);
                    break;
                case ROW_RESET_HGRAM_SETTINGS:
                    ((TextSettingsCell) holder.itemView).setTextAndValue(
                            LocaleController.getString(R.string.HgramResetSettings), "", divider);
                    break;
                case ROW_SEARCH_EMPTY:
                    ((TextInfoPrivacyCell) holder.itemView).setText(
                            LocaleController.getString(R.string.HgramSearchNoResults));
                    break;
                case ROW_GIGAAM_INFO:
                    ((TextInfoPrivacyCell) holder.itemView).setText(
                            gigaAmInfoText());
                    break;
                default:
                    break;
            }
        }

        @Override
        public int getItemViewType(int position) {
            return rowViewType(rowAt(position));
        }
    }

    private void showDecoyPasscodeMenu() {
        if (SharedConfig.passcodeHash.isEmpty()) {
            Toast.makeText(getParentActivity(), LocaleController.getString(R.string.PasscodeNeeded), Toast.LENGTH_SHORT).show();
            return;
        }
        if (!PasscodeHelper.isHgramDecoyConfigured()) {
            presentFragment(new PasscodeActivity(PasscodeActivity.TYPE_SETUP_CODE, PasscodeHelper.HGRAM_DECOY_ACCOUNT));
            return;
        }
        HgramSubmenuDialog.Builder builder = new HgramSubmenuDialog.Builder(getParentActivity(), resourcesProvider)
                .title(LocaleController.getString(R.string.HgramDecoyPasscode));
        builder.option(LocaleController.getString(R.string.HgramDecoyPasscodeChange), false,
                () -> presentFragment(new PasscodeActivity(PasscodeActivity.TYPE_SETUP_CODE, PasscodeHelper.HGRAM_DECOY_ACCOUNT)));
        builder.option(LocaleController.getString(R.string.HgramDecoyPasscodeRemove), false, () -> {
            PasscodeHelper.removeHgramDecoyPasscode();
            notifyRow(ROW_DECOY_PASSCODE);
        });
        builder.footerButton(LocaleController.getString(R.string.Cancel), null, true);
        builder.show();
    }

    private int lastSherpaProgress = -1;
    private int lastGigaAmProgress = -1;
    private String cachedVoskInstalledStatus;
    private String cachedSherpaInstalledStatus;
    private String cachedGigaAmInstalledStatus;
    private final int[] lastVoskProgress = {-1, -1};
    private final Runnable refreshModelProgress = new Runnable() {
        @Override
        public void run() {
            if (isPaused() || isFinished) return;
            boolean downloading = false;
            String[] languages = {HgramVoskModels.LANG_RU, HgramVoskModels.LANG_EN};
            for (int i = 0; i < languages.length; i++) {
                int progress = HgramVoskModels.downloadProgress(languages[i]);
                if (progress != lastVoskProgress[i]) {
                    notifyRow(ROW_VOSK_MODELS);
                    if (lastVoskProgress[i] >= 0 && progress < 0) {
                        cachedVoskInstalledStatus = null;
                        showModelDownloadResult(HgramVoskModels.isInstalled(languages[i]));
                    }
                    lastVoskProgress[i] = progress;
                }
                downloading |= progress >= 0;
            }
            int sherpaProgress = HgramSherpaRecognizer.downloadProgress();
            if (sherpaProgress != lastSherpaProgress) {
                notifyRow(ROW_SHERPA_MODEL);
                if (lastSherpaProgress >= 0 && sherpaProgress < 0) {
                    cachedSherpaInstalledStatus = null;
                    showModelDownloadResult(HgramSherpaRecognizer.isInstalled());
                }
                lastSherpaProgress = sherpaProgress;
            }
            downloading |= sherpaProgress >= 0;
            int gigaAmProgress = HgramGigaAmRecognizer.downloadProgress();
            if (gigaAmProgress != lastGigaAmProgress) {
                notifyRow(ROW_GIGAAM_MODEL);
                if (lastGigaAmProgress >= 0 && gigaAmProgress < 0) {
                    cachedGigaAmInstalledStatus = null;
                    showGigaAmDownloadResult();
                }
                lastGigaAmProgress = gigaAmProgress;
            }
            downloading |= gigaAmProgress >= 0;
            if (downloading) AndroidUtilities.runOnUIThread(this, 1000);
        }
    };

    @Override
    public void onResume() {
        super.onResume();
        final boolean staleSearch = searching || (searchQuery != null && !searchQuery.isEmpty());
        final boolean resetSearch = staleSearch && (searchItem == null || !searchItem.isSearchFieldVisible());
        if (resetSearch) {
            searching = false;
            searchQuery = "";
            rebuildVisibleRows(true);
        }
        invalidateSearchTextCache();
        cachedVoskInstalledStatus = null;
        cachedSherpaInstalledStatus = null;
        cachedGigaAmInstalledStatus = null;
        lastVoskProgress[0] = HgramVoskModels.downloadProgress(HgramVoskModels.LANG_RU);
        lastVoskProgress[1] = HgramVoskModels.downloadProgress(HgramVoskModels.LANG_EN);
        lastSherpaProgress = HgramSherpaRecognizer.downloadProgress();
        lastGigaAmProgress = HgramGigaAmRecognizer.downloadProgress();
        if (listAdapter != null && !resetSearch) {
            notifyAllRows();
        }
        scheduleModelProgressRefresh();
    }

    @Override
    public void onPause() {
        cancelAntiPeekReopen();
        HgramAntiPeek.cancelTemporaryReveal(true);
        if (antiPeekDialog != null && antiPeekDialog.isShowing()) {
            antiPeekDialog.dismiss();
        }
        invalidateSummarizerModelsRequest();
        AndroidUtilities.cancelRunOnUIThread(refreshModelProgress);
        super.onPause();
    }

    @Override
    public void onFragmentDestroy() {
        cancelAntiPeekReopen();
        HgramAntiPeek.cancelTemporaryReveal(true);
        antiPeekSkipNextDismiss = false;
        antiPeekDialog = null;
        invalidateSummarizerModelsRequest();
        super.onFragmentDestroy();
    }

    private void scheduleModelProgressRefresh() {
        AndroidUtilities.cancelRunOnUIThread(refreshModelProgress);
        refreshModelProgress.run();
    }

    private void showModelDownloadResult(boolean success) {
        if (getParentActivity() != null) {
            Toast.makeText(getParentActivity(), LocaleController.getString(success ? R.string.HgramModelInstalled : R.string.HgramModelDownloadFailed), Toast.LENGTH_SHORT).show();
        }
    }

    private String voskLanguageName(String lang) {
        return HgramVoskModels.LANG_RU.equals(lang) ? LocaleController.getString(R.string.HgramLangRu) : LocaleController.getString(R.string.HgramLangEn);
    }

    private String voskModelStatus(String lang) {
        int progress = HgramVoskModels.downloadProgress(lang);
        if (progress >= 0) {
            return LocaleController.formatString(R.string.HgramModelDownloading, progress);
        }
        return LocaleController.getString(HgramVoskModels.isInstalled(lang) ? R.string.HgramModelInstalled : R.string.HgramModelNotInstalled);
    }

    private String voskStatusText() {
        for (String lang : new String[]{HgramVoskModels.LANG_RU, HgramVoskModels.LANG_EN}) {
            int progress = HgramVoskModels.downloadProgress(lang);
            if (progress >= 0) {
                return LocaleController.formatString(R.string.HgramModelDownloading, progress);
            }
        }
        if (cachedVoskInstalledStatus != null) {
            return cachedVoskInstalledStatus;
        }
        int installed = 0;
        for (String lang : new String[]{HgramVoskModels.LANG_RU, HgramVoskModels.LANG_EN}) {
            if (HgramVoskModels.isInstalled(lang)) {
                installed++;
            }
        }
        if (installed == 0) {
            cachedVoskInstalledStatus = LocaleController.getString(R.string.HgramModelNone);
        } else {
            cachedVoskInstalledStatus = LocaleController.formatString(R.string.HgramModelCount, installed, 2);
        }
        return cachedVoskInstalledStatus;
    }

    private String sherpaStatusText() {
        int progress = HgramSherpaRecognizer.downloadProgress();
        if (progress >= 0) {
            return LocaleController.formatString(R.string.HgramModelDownloading, progress);
        }
        if (cachedSherpaInstalledStatus == null) {
            cachedSherpaInstalledStatus = LocaleController.getString(HgramSherpaRecognizer.isInstalled() ? R.string.HgramModelInstalled : R.string.HgramModelNotInstalled);
        }
        return cachedSherpaInstalledStatus;
    }

    private static String gigaAmInfoText() {
        return LocaleController.getString(R.string.HgramGigaAmInfo);
    }

    private String gigaAmStatusText() {
        int progress = HgramGigaAmRecognizer.downloadProgress();
        if (progress >= 0) {
            return LocaleController.formatString(R.string.HgramModelDownloading, progress);
        }
        if (cachedGigaAmInstalledStatus == null) {
            String state = LocaleController.getString(HgramGigaAmRecognizer.isInstalled() ? R.string.HgramModelInstalled : R.string.HgramModelNotInstalled);
            cachedGigaAmInstalledStatus = state + " · " + LocaleController.formatString(R.string.HgramGigaAmModelSize, (int) (HgramGigaAmRecognizer.TOTAL_SIZE_BYTES / (1024 * 1024)));
        }
        return cachedGigaAmInstalledStatus;
    }

    private void showGigaAmDownloadResult() {
        if (getParentActivity() == null) {
            return;
        }
        int res;
        if (HgramGigaAmRecognizer.isInstalled()) {
            res = R.string.HgramModelInstalled;
        } else {
            switch (HgramGigaAmRecognizer.lastError()) {
                case HgramGigaAmRecognizer.ERROR_NO_SPACE:
                    res = R.string.HgramGigaAmErrorSpace;
                    break;
                case HgramGigaAmRecognizer.ERROR_HASH:
                    res = R.string.HgramGigaAmErrorHash;
                    break;
                case HgramGigaAmRecognizer.ERROR_CANCELLED:
                    res = R.string.HgramGigaAmDownloadCancelled;
                    break;
                case HgramGigaAmRecognizer.ERROR_NETWORK:
                    res = R.string.HgramGigaAmErrorNetwork;
                    break;
                default:
                    res = R.string.HgramModelDownloadFailed;
                    break;
            }
        }
        Toast.makeText(getParentActivity(), LocaleController.getString(res), Toast.LENGTH_SHORT).show();
    }

    private String antiPeekStatusText() {
        final String state = LocaleController.getString(HgramAntiPeek.isActive()
                ? R.string.HgramAntiPeekStatusOn : R.string.HgramAntiPeekStatusOff);
        return state + " · " + LocaleController.formatString(R.string.HgramAntiPeekHoldValue,
                HgramConfig.getAntiPeekHoldMinMs(), HgramConfig.getAntiPeekHoldMaxMs());
    }

    private String antiPeekSensitivityLabel(int sensitivity) {
        if (sensitivity == HgramConfig.ANTI_PEEK_SENSITIVITY_LOW) {
            return LocaleController.getString(R.string.HgramAntiPeekSensitivityLow);
        }
        if (sensitivity == HgramConfig.ANTI_PEEK_SENSITIVITY_HIGH) {
            return LocaleController.getString(R.string.HgramAntiPeekSensitivityHigh);
        }
        return LocaleController.getString(R.string.HgramAntiPeekSensitivityMedium);
    }

    private String antiPeekOnOff(boolean enabled) {
        return LocaleController.getString(enabled ? R.string.HgramAntiPeekStatusOn : R.string.HgramAntiPeekStatusOff);
    }

    private void showAntiPeekDialog() {
        final boolean gesture = HgramConfig.isAntiPeekGestureEnabled();
        final boolean toast = HgramConfig.isAntiPeekShowToast();
        final boolean haptic = HgramConfig.isAntiPeekHapticEnabled();
        final boolean maskActive = HgramAntiPeek.isActive();
        final boolean temporary = HgramAntiPeek.isTemporaryRevealActive();
        final HgramSubmenuDialog.Builder builder =
                new HgramSubmenuDialog.Builder(getParentActivity(), resourcesProvider)
                        .title(LocaleController.getString(R.string.HgramRoadmapAntiPeek));
        builder.option(LocaleController.getString(R.string.HgramAntiPeekGesture) + ": "
                + antiPeekOnOff(gesture), gesture, () -> {
            final boolean enabled = !HgramConfig.isAntiPeekGestureEnabled();
            HgramConfig.setAntiPeekGestureEnabled(enabled);
            if (enabled) {
                HgramAntiPeek.start(getParentActivity());
            } else {
                HgramAntiPeek.stop();
                HgramAntiPeek.setActive(false);
            }
            HgramAntiPeek.applySettingChange();
            refreshAntiPeekRow();
        });
        builder.option(LocaleController.getString(R.string.HgramAntiPeekSensitivity) + ": "
                + antiPeekSensitivityLabel(HgramConfig.getAntiPeekSensitivity()), false,
                this::showAntiPeekSensitivityDialog);
        builder.option(LocaleController.getString(R.string.HgramAntiPeekHoldWindow) + ": "
                + LocaleController.formatString(R.string.HgramAntiPeekHoldValue,
                HgramConfig.getAntiPeekHoldMinMs(), HgramConfig.getAntiPeekHoldMaxMs()), false,
                this::showAntiPeekHoldDialog);
        builder.option(LocaleController.getString(R.string.HgramAntiPeekToastSetting) + ": "
                + antiPeekOnOff(toast), toast, () -> {
            HgramConfig.setAntiPeekShowToast(!HgramConfig.isAntiPeekShowToast());
            HgramAntiPeek.applySettingChange();
            refreshAntiPeekRow();
        });
        builder.option(LocaleController.getString(R.string.HgramAntiPeekHaptic) + ": "
                + antiPeekOnOff(haptic), haptic, () -> {
            HgramConfig.setAntiPeekHapticEnabled(!HgramConfig.isAntiPeekHapticEnabled());
            refreshAntiPeekRow();
        });
        if (maskActive) {
            builder.option(LocaleController.getString(R.string.HgramAntiPeekShow5s), false, () -> {
                antiPeekSkipNextDismiss = true;
                final WeakReference<HgramSettingsActivity> self = new WeakReference<>(this);
                HgramAntiPeek.showTemporarily(5000L, () -> {
                    final HgramSettingsActivity activity = self.get();
                    if (activity != null) {
                        activity.onAntiPeekTemporaryRestored();
                    }
                });
                refreshAntiPeekRow();
            });
        } else if (temporary) {
            builder.option(LocaleController.getString(R.string.HgramAntiPeekHideNow), false, () -> {
                HgramAntiPeek.cancelTemporaryReveal(true);
                refreshAntiPeekRow();
            });
        }
        builder.footerButton(LocaleController.getString(R.string.Cancel), null, true);
        builder.onDismiss(() -> {
            antiPeekDialog = null;
            if (antiPeekSkipNextDismiss) {
                antiPeekSkipNextDismiss = false;
                scheduleAntiPeekReopen();
                return;
            }
            HgramAntiPeek.cancelTemporaryReveal(true);
            refreshAntiPeekRow();
        });
        antiPeekDialog = builder.show();
    }

    private void scheduleAntiPeekReopen() {
        cancelAntiPeekReopen();
        final WeakReference<HgramSettingsActivity> self = new WeakReference<>(this);
        antiPeekReopenRunnable = () -> {
            antiPeekReopenRunnable = null;
            final HgramSettingsActivity activity = self.get();
            if (activity == null || !activity.canReopenAntiPeekDialog()) {
                return;
            }
            activity.showAntiPeekDialog();
        };
        AndroidUtilities.runOnUIThread(antiPeekReopenRunnable, 50L);
    }

    private void cancelAntiPeekReopen() {
        if (antiPeekReopenRunnable != null) {
            AndroidUtilities.cancelRunOnUIThread(antiPeekReopenRunnable);
            antiPeekReopenRunnable = null;
        }
    }

    private boolean canReopenAntiPeekDialog() {
        if (getFragmentView() == null || getParentActivity() == null) {
            return false;
        }
        return !getParentActivity().isFinishing() && !getParentActivity().isDestroyed();
    }

    private void onAntiPeekTemporaryRestored() {
        if (!canReopenAntiPeekDialog()) {
            refreshAntiPeekRow();
            return;
        }
        refreshAntiPeekRow();
        if (HgramConfig.isAntiPeekShowToast()) {
            Toast.makeText(getParentActivity(),
                    LocaleController.getString(R.string.HgramAntiPeekRestored),
                    Toast.LENGTH_SHORT).show();
        }
        if (antiPeekDialog != null && antiPeekDialog.isShowing()) {
            antiPeekSkipNextDismiss = true;
            antiPeekDialog.dismiss();
        }
    }

    private void showAntiPeekHoldDialog() {
        final int[][] values = new int[][]{
                {200, 800},
                {200, 1200},
                {400, 1600}
        };
        final int[] labels = new int[]{
                R.string.HgramAntiPeekHoldShort,
                R.string.HgramAntiPeekHoldStandard,
                R.string.HgramAntiPeekHoldLong
        };
        final int currentMin = HgramConfig.getAntiPeekHoldMinMs();
        final int currentMax = HgramConfig.getAntiPeekHoldMaxMs();
        final HgramSubmenuDialog.Builder builder =
                new HgramSubmenuDialog.Builder(getParentActivity(), resourcesProvider)
                        .title(LocaleController.getString(R.string.HgramAntiPeekHoldWindow));
        for (int i = 0; i < values.length; i++) {
            final int index = i;
            final boolean checked = values[i][0] == currentMin && values[i][1] == currentMax;
            builder.option(LocaleController.getString(labels[i]), checked, () -> {
                HgramConfig.setAntiPeekHoldWindow(values[index][0], values[index][1]);
                HgramAntiPeek.applySettingChange();
                refreshAntiPeekRow();
            });
        }
        builder.footerButton(LocaleController.getString(R.string.Cancel), null, true);
        builder.show();
    }

    private void showAntiPeekSensitivityDialog() {
        final int[] values = new int[]{
                HgramConfig.ANTI_PEEK_SENSITIVITY_LOW,
                HgramConfig.ANTI_PEEK_SENSITIVITY_MEDIUM,
                HgramConfig.ANTI_PEEK_SENSITIVITY_HIGH
        };
        final int[] labels = new int[]{
                R.string.HgramAntiPeekSensitivityLow,
                R.string.HgramAntiPeekSensitivityMedium,
                R.string.HgramAntiPeekSensitivityHigh
        };
        final int current = HgramConfig.getAntiPeekSensitivity();
        final HgramSubmenuDialog.Builder builder =
                new HgramSubmenuDialog.Builder(getParentActivity(), resourcesProvider)
                        .title(LocaleController.getString(R.string.HgramAntiPeekSensitivity));
        for (int i = 0; i < values.length; i++) {
            final int index = i;
            builder.option(LocaleController.getString(labels[i]), values[i] == current, () -> {
                HgramConfig.setAntiPeekSensitivity(values[index]);
                HgramAntiPeek.applySettingChange();
                refreshAntiPeekRow();
            });
        }
        builder.footerButton(LocaleController.getString(R.string.Cancel), null, true);
        builder.show();
    }

    private void showSttEngineDialog() {
        final String current = HgramConfig.getSttEngine();
        HgramSubmenuDialog.Builder builder = new HgramSubmenuDialog.Builder(getParentActivity(), resourcesProvider)
                .title(LocaleController.getString(R.string.HgramSttEngine));
        builder.option(LocaleController.getString(R.string.HgramSttEngineVosk),
                HgramConfig.STT_ENGINE_VOSK.equals(current), () -> {
                    HgramConfig.setSttEngine(HgramConfig.STT_ENGINE_VOSK);
                    invalidateSearchTextCache();
                    rebuildVisibleRows(true);
                });
        builder.option(LocaleController.getString(R.string.HgramSttEngineSherpa),
                HgramConfig.STT_ENGINE_SHERPA.equals(current), () -> {
                    HgramConfig.setSttEngine(HgramConfig.STT_ENGINE_SHERPA);
                    invalidateSearchTextCache();
                    rebuildVisibleRows(true);
                });
        builder.option(LocaleController.getString(R.string.HgramSttEngineGigaAm),
                HgramConfig.STT_ENGINE_GIGAAM.equals(current), () -> {
                    HgramConfig.setSttEngine(HgramConfig.STT_ENGINE_GIGAAM);
                    invalidateSearchTextCache();
                    rebuildVisibleRows(true);
                });
        builder.footerButton(LocaleController.getString(R.string.Cancel), null, true);
        builder.show();
    }

    private String sherpaDeviceName() {
        String provider = HgramConfig.getSherpaProvider();
        if (HgramConfig.SHERPA_PROVIDER_NNAPI.equals(provider)) {
            return LocaleController.getString(R.string.HgramSherpaDeviceNnapi);
        }
        if (HgramConfig.SHERPA_PROVIDER_XNNPACK.equals(provider)) {
            return LocaleController.getString(R.string.HgramSherpaDeviceXnnpack);
        }
        return LocaleController.getString(R.string.HgramSherpaDeviceCpu);
    }

    private void showSherpaDeviceDialog() {
        final String current = HgramConfig.getSherpaProvider();
        HgramSubmenuDialog.Builder builder = new HgramSubmenuDialog.Builder(getParentActivity(), resourcesProvider)
                .title(LocaleController.getString(R.string.HgramSherpaDevice))
                .message(LocaleController.getString(R.string.HgramSherpaDeviceInfo));
        builder.option(LocaleController.getString(R.string.HgramSherpaDeviceCpu),
                HgramConfig.SHERPA_PROVIDER_CPU.equals(current), () -> {
                    HgramConfig.setSherpaProvider(HgramConfig.SHERPA_PROVIDER_CPU);
                    HgramSherpaRecognizer.releaseRecognizer();
                    notifyRow(ROW_SHERPA_DEVICE);
                });
        builder.option(LocaleController.getString(R.string.HgramSherpaDeviceXnnpack),
                HgramConfig.SHERPA_PROVIDER_XNNPACK.equals(current), () -> {
                    HgramConfig.setSherpaProvider(HgramConfig.SHERPA_PROVIDER_XNNPACK);
                    HgramSherpaRecognizer.releaseRecognizer();
                    notifyRow(ROW_SHERPA_DEVICE);
                });
        if (android.os.Build.VERSION.SDK_INT >= 27) {
            builder.option(LocaleController.getString(R.string.HgramSherpaDeviceNnapi),
                    HgramConfig.SHERPA_PROVIDER_NNAPI.equals(current), () -> {
                        HgramConfig.setSherpaProvider(HgramConfig.SHERPA_PROVIDER_NNAPI);
                        HgramSherpaRecognizer.releaseRecognizer();
                        notifyRow(ROW_SHERPA_DEVICE);
                    });
        }
        builder.footerButton(LocaleController.getString(R.string.Cancel), null, true);
        builder.show();
    }

    private String weakNetworkName() {
        switch (HgramConfig.getWeakNetworkMode()) {
            case HgramConfig.WEAK_NETWORK_ALWAYS:
                return LocaleController.getString(R.string.HgramWeakNetworkAlways);
            case HgramConfig.WEAK_NETWORK_OFF:
                return LocaleController.getString(R.string.HgramWeakNetworkOff);
            default:
                return LocaleController.getString(R.string.HgramWeakNetworkAuto);
        }
    }

    private void resetHgramSettingsAndRebuild() {
        invalidateSummarizerModelsRequest();
        HgramSettingsReset.run(new HgramSettingsReset.Host() {
            @Override
            public void resetConfig() {
                HgramConfig.reset();
            }

            @Override
            public void applyAntiPeek() {
                HgramAntiPeek.applySettingChange();
            }

            @Override
            public String currentEngine() {
                return HgramConfig.getSttEngine();
            }

            @Override
            public void clearSearchQuery() {
                searchQuery = "";
            }

            @Override
            public void setSearchCallbackSuppressed(boolean suppressed) {
                suppressSearchCallback = suppressed;
            }

            @Override
            public void clearSearchField() {
                if (searchItem != null && searchItem.isSearchFieldVisible()) {
                    searchItem.getSearchField().setText("");
                }
            }

            @Override
            public void applyVisibleRows(int[] rows) {
                visibleRows = rows;
                rowCount = rows.length;
            }

            @Override
            public void notifyAdapterOnce() {
                notifyAllRows();
                if (listView != null) {
                    listView.scrollToPosition(0);
                }
            }
        });
    }

    private void showResetHgramSettingsDialog() {
        new AlertDialog.Builder(getParentActivity())
                .setTitle(LocaleController.getString(R.string.HgramResetSettingsTitle))
                .setMessage(LocaleController.getString(R.string.HgramResetSettingsMessage))
                .setPositiveButton(LocaleController.getString(R.string.HgramResetSettings), (dialog, which) -> {
                    resetHgramSettingsAndRebuild();
                    if (getParentActivity() != null) {
                        Toast.makeText(getParentActivity(),
                                LocaleController.getString(R.string.HgramResetSettingsDone),
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .show();
    }

    private void showWeakNetworkDialog() {
        final int current = HgramConfig.getWeakNetworkMode();
        HgramSubmenuDialog.Builder builder = new HgramSubmenuDialog.Builder(getParentActivity(), resourcesProvider)
                .title(LocaleController.getString(R.string.HgramWeakNetwork))
                .message(LocaleController.getString(R.string.HgramWeakNetworkInfo));
        builder.option(LocaleController.getString(R.string.HgramWeakNetworkAuto),
                current == HgramConfig.WEAK_NETWORK_AUTO,
                () -> setWeakNetworkMode(HgramConfig.WEAK_NETWORK_AUTO));
        builder.option(LocaleController.getString(R.string.HgramWeakNetworkAlways),
                current == HgramConfig.WEAK_NETWORK_ALWAYS,
                () -> setWeakNetworkMode(HgramConfig.WEAK_NETWORK_ALWAYS));
        builder.option(LocaleController.getString(R.string.HgramWeakNetworkOff),
                current == HgramConfig.WEAK_NETWORK_OFF,
                () -> setWeakNetworkMode(HgramConfig.WEAK_NETWORK_OFF));
        builder.footerButton(LocaleController.getString(R.string.Cancel), null, true);
        builder.show();
    }

    private void setWeakNetworkMode(int mode) {
        HgramConfig.setWeakNetworkMode(mode);
        ApplicationLoader.notifyConnectionQualityChanged();
        notifyRow(ROW_WEAK_NETWORK);
    }

    private String notifMediaStyleName() {
        switch (HgramConfig.getNotifMediaStyle()) {
            case HgramConfig.NOTIF_MEDIA_STYLE_SYSTEM:
                return LocaleController.getString(R.string.HgramNotifMediaStyleSystem);
            case HgramConfig.NOTIF_MEDIA_STYLE_CUSTOM:
                return LocaleController.getString(R.string.HgramNotifMediaStyleCustom);
            default:
                return LocaleController.getString(R.string.HgramNotifMediaStyleAuto);
        }
    }

    private void showNotifMediaStyleDialog() {
        final int current = HgramConfig.getNotifMediaStyle();
        HgramSubmenuDialog.Builder builder = new HgramSubmenuDialog.Builder(getParentActivity(), resourcesProvider)
                .title(LocaleController.getString(R.string.HgramNotifMediaStyle));
        builder.option(LocaleController.getString(R.string.HgramNotifMediaStyleAuto),
                current == HgramConfig.NOTIF_MEDIA_STYLE_AUTO,
                () -> setNotifMediaStyle(HgramConfig.NOTIF_MEDIA_STYLE_AUTO));
        builder.option(LocaleController.getString(R.string.HgramNotifMediaStyleSystem),
                current == HgramConfig.NOTIF_MEDIA_STYLE_SYSTEM,
                () -> setNotifMediaStyle(HgramConfig.NOTIF_MEDIA_STYLE_SYSTEM));
        builder.option(LocaleController.getString(R.string.HgramNotifMediaStyleCustom),
                current == HgramConfig.NOTIF_MEDIA_STYLE_CUSTOM,
                () -> setNotifMediaStyle(HgramConfig.NOTIF_MEDIA_STYLE_CUSTOM));
        builder.footerButton(LocaleController.getString(R.string.Cancel), null, true);
        builder.show();
    }

    private void setNotifMediaStyle(int style) {
        HgramConfig.setNotifMediaStyle(style);
        notifyRow(ROW_NOTIF_MEDIA_STYLE);
    }

    private void showVoskModelsDialog() {
        CharSequence[] items = new CharSequence[]{
                voskLanguageName(HgramVoskModels.LANG_RU) + " (" + HgramVoskModels.sizeMb(HgramVoskModels.LANG_RU) + " MB): " + voskModelStatus(HgramVoskModels.LANG_RU),
                voskLanguageName(HgramVoskModels.LANG_EN) + " (" + HgramVoskModels.sizeMb(HgramVoskModels.LANG_EN) + " MB): " + voskModelStatus(HgramVoskModels.LANG_EN)
        };
        new AlertDialog.Builder(getParentActivity())
                .setTitle(LocaleController.getString(R.string.HgramVoskModels))
                .setItems(items, (dialog, which) -> toggleVoskModel(which == 0 ? HgramVoskModels.LANG_RU : HgramVoskModels.LANG_EN))
                .show();
    }

    private void toggleVoskModel(String lang) {
        if (HgramVoskModels.downloadProgress(lang) >= 0) return;
        if (HgramVoskModels.isInstalled(lang)) {
            new AlertDialog.Builder(getParentActivity())
                    .setTitle(voskLanguageName(lang))
                    .setMessage(LocaleController.getString(R.string.HgramModelDeleteConfirm))
                    .setPositiveButton(LocaleController.getString(R.string.HgramModelDelete), (dialog, which) -> {
                        cachedVoskInstalledStatus = null;
                        HgramVoskModels.delete(lang);
                        notifyRow(ROW_VOSK_MODELS);
                    })
                    .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                    .show();
            return;
        }
        new AlertDialog.Builder(getParentActivity())
                .setTitle(voskLanguageName(lang))
                .setMessage(LocaleController.formatString(R.string.HgramModelDownloadConfirm, HgramVoskModels.sizeMb(lang)))
                .setPositiveButton(LocaleController.getString(R.string.HgramModelDownload), (dialog, which) -> startVoskDownload(lang))
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .show();
    }

    private void startVoskDownload(String lang) {
        int index = HgramVoskModels.LANG_RU.equals(lang) ? 0 : 1;
        lastVoskProgress[index] = 0;
        notifyRow(ROW_VOSK_MODELS);
        HgramVoskModels.download(lang, null);
        scheduleModelProgressRefresh();
    }

    private void toggleSherpaModel() {
        if (HgramSherpaRecognizer.downloadProgress() >= 0) return;
        if (HgramSherpaRecognizer.isInstalled()) {
            new AlertDialog.Builder(getParentActivity())
                    .setTitle(LocaleController.getString(R.string.HgramSherpaModel))
                    .setMessage(LocaleController.getString(R.string.HgramModelDeleteConfirm))
                    .setPositiveButton(LocaleController.getString(R.string.HgramModelDelete), (dialog, which) -> {
                        cachedSherpaInstalledStatus = null;
                        HgramSherpaRecognizer.INSTANCE.delete();
                        notifyRow(ROW_SHERPA_MODEL);
                    })
                    .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                    .show();
            return;
        }
        new AlertDialog.Builder(getParentActivity())
                .setTitle(LocaleController.getString(R.string.HgramSherpaModel))
                .setMessage(LocaleController.formatString(R.string.HgramModelDownloadConfirm, 300))
                .setPositiveButton(LocaleController.getString(R.string.HgramModelDownload), (dialog, which) -> startSherpaDownload())
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .show();
    }

    private void startSherpaDownload() {
        lastSherpaProgress = 0;
        notifyRow(ROW_SHERPA_MODEL);
        HgramSherpaRecognizer.INSTANCE.download(null, null);
        scheduleModelProgressRefresh();
    }

    private void toggleGigaAmModel() {
        if (HgramGigaAmRecognizer.downloadProgress() >= 0) {
            new AlertDialog.Builder(getParentActivity())
                    .setTitle(LocaleController.getString(R.string.HgramGigaAmModel))
                    .setMessage(LocaleController.getString(R.string.HgramGigaAmCancelConfirm))
                    .setPositiveButton(LocaleController.getString(R.string.HgramGigaAmCancelAction), (dialog, which) -> HgramGigaAmRecognizer.cancelDownload())
                    .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                    .show();
            return;
        }
        if (HgramGigaAmRecognizer.isInstalled()) {
            new AlertDialog.Builder(getParentActivity())
                    .setTitle(LocaleController.getString(R.string.HgramGigaAmModel))
                    .setMessage(LocaleController.getString(R.string.HgramModelDeleteConfirm))
                    .setPositiveButton(LocaleController.getString(R.string.HgramModelDelete), (dialog, which) -> {
                        cachedGigaAmInstalledStatus = null;
                        HgramGigaAmRecognizer.delete();
                        notifyRow(ROW_GIGAAM_MODEL);
                    })
                    .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                    .show();
            return;
        }
        new AlertDialog.Builder(getParentActivity())
                .setTitle(LocaleController.getString(R.string.HgramGigaAmModel))
                .setMessage(LocaleController.formatString(R.string.HgramGigaAmDownloadConfirm, (int) (HgramGigaAmRecognizer.TOTAL_SIZE_BYTES / (1024 * 1024))))
                .setPositiveButton(LocaleController.getString(R.string.HgramModelDownload), (dialog, which) -> startGigaAmDownload())
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .show();
    }

    private void startGigaAmDownload() {
        lastGigaAmProgress = 0;
        notifyRow(ROW_GIGAAM_MODEL);
        HgramGigaAmRecognizer.download(null, success -> {
            if (!success && HgramGigaAmRecognizer.lastError() == HgramGigaAmRecognizer.ERROR_ALREADY_DOWNLOADING) {
                Toast.makeText(getParentActivity(), LocaleController.getString(R.string.HgramGigaAmAlreadyDownloading), Toast.LENGTH_SHORT).show();
            }
            return kotlin.Unit.INSTANCE;
        });
        scheduleModelProgressRefresh();
    }
}
