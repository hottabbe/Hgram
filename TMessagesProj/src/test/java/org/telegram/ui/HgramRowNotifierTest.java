package org.telegram.ui;

import android.app.Application;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import xyz.nextalone.nagram.hgram.HgramRowNotifier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 34)
public class HgramRowNotifierTest {

    @Test
    public void defersWhileComputingLayoutAndAppliesAfterwards() {
        Fixture f = new Fixture();
        f.list.computing = true;
        HgramRowNotifier.notifyItemChanged(f.list, f.adapter, 2);
        assertEquals(0, f.changes);
        f.list.computing = false;
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks();
        assertEquals(1, f.changes);
        assertEquals(2, f.lastPosition);
    }

    @Test
    public void defersWhileScrollingAndNeverThrows() {
        Fixture f = new Fixture();
        f.list.scrollState = RecyclerView.SCROLL_STATE_SETTLING;
        HgramRowNotifier.notifyItemChanged(f.list, f.adapter, 1);
        assertEquals(0, f.changes);
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks();
        assertEquals(1, f.changes);
    }

    @Test
    public void notifiesImmediatelyWhenIdle() {
        Fixture f = new Fixture();
        HgramRowNotifier.notifyItemChanged(f.list, f.adapter, 3);
        assertEquals(1, f.changes);
        assertEquals(3, f.lastPosition);
    }

    @Test
    public void productionPathReResolvesRowIdAfterLayout() {
        Fixture f = new Fixture();
        f.list.computing = true;
        final int[] resolverIndex = {2};
        final int[] published = {-1};
        HgramRowNotifier.postOrNotify(f.list, 72,
                rowId -> resolverIndex[0],
                index -> published[0] = index);
        assertEquals(-1, published[0]);
        f.list.computing = false;
        resolverIndex[0] = 3;
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks();
        assertEquals(3, published[0]);
    }

    @Test
    public void productionPathSkipsHiddenRow() {
        Fixture f = new Fixture();
        f.list.computing = true;
        final int[] published = {-1};
        HgramRowNotifier.postOrNotify(f.list, 72, rowId -> -1, index -> published[0] = index);
        f.list.computing = false;
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks();
        assertEquals(-1, published[0]);
    }

    @Test
    public void summarizerToggleSurvivesBusyLayout() {
        Fixture f = new Fixture();
        xyz.nextalone.nagram.hgram.HgramConfig.setSummarizerEnabled(false);
        f.list.computing = true;
        final boolean enabled = !xyz.nextalone.nagram.hgram.HgramConfig.isSummarizerEnabled();
        xyz.nextalone.nagram.hgram.HgramConfig.setSummarizerEnabled(enabled);
        HgramRowNotifier.postOrNotify(f.list, 72, rowId -> 4, index -> { });
        assertTrue(enabled);
        f.list.computing = false;
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks();
    }

    @Test
    public void nullListIsIgnored() {
        Fixture f = new Fixture();
        HgramRowNotifier.notifyItemChanged(null, f.adapter, 0);
        HgramRowNotifier.notifyItemChanged(f.list, null, 0);
    }

    private static final class Fixture {
        final FakeRecyclerView list;
        final CountingAdapter adapter;
        int changes;
        int lastPosition = -1;

        Fixture() {
            Context context = ApplicationProvider.getApplicationContext();
            org.telegram.messenger.ApplicationLoader.applicationContext = context;
            list = new FakeRecyclerView(context);
            android.app.Activity host = org.robolectric.Robolectric.buildActivity(android.app.Activity.class)
                    .setup().get();
            host.setContentView(list);
            adapter = new CountingAdapter(context);
            adapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
                @Override public void onItemRangeChanged(int positionStart, int itemCount) {
                    changes++;
                    lastPosition = positionStart;
                }
            });
        }
    }

    private static final class FakeRecyclerView extends RecyclerView {
        boolean computing;
        int scrollState = SCROLL_STATE_IDLE;

        FakeRecyclerView(Context context) {
            super(context);
        }

        @Override public boolean isComputingLayout() {
            return computing;
        }

        @Override public int getScrollState() {
            return scrollState;
        }
    }

    private static final class CountingAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final Context context;

        CountingAdapter(Context context) {
            this.context = context;
        }

        @NonNull @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new RecyclerView.ViewHolder(new View(context)) { };
        }

        @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) { }
        @Override public int getItemCount() { return 5; }
    }
}
