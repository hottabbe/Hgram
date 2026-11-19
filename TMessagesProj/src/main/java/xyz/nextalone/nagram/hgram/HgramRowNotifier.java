package xyz.nextalone.nagram.hgram;

import androidx.recyclerview.widget.RecyclerView;

/** RecyclerView forbids adapter notifications while it is laying out or scrolling. */
public final class HgramRowNotifier {

    private HgramRowNotifier() {
    }

    public interface Resolver {
        int indexOf(int rowId);
    }

    public interface Publisher {
        void publish(int index);
    }

    /** Production path: re-resolve the row id after layout before publishing. */
    public static void postOrNotify(RecyclerView list, int rowId, Resolver resolver, Publisher publisher) {
        if (list == null || resolver == null || publisher == null) return;
        if (list.isComputingLayout() || list.getScrollState() != RecyclerView.SCROLL_STATE_IDLE) {
            list.post(() -> {
                if (list.isComputingLayout()) return;
                final int index = resolver.indexOf(rowId);
                if (index >= 0) publisher.publish(index);
            });
            return;
        }
        final int index = resolver.indexOf(rowId);
        if (index >= 0) publisher.publish(index);
    }

    public static void notifyItemChanged(RecyclerView list, RecyclerView.Adapter<?> adapter, int index) {
        if (list == null || adapter == null || index < 0) return;
        if (list.isComputingLayout() || list.getScrollState() != RecyclerView.SCROLL_STATE_IDLE) {
            list.post(() -> {
                if (list.isComputingLayout()) return;
                if (index < adapter.getItemCount()) {
                    adapter.notifyItemChanged(index);
                }
            });
            return;
        }
        if (index < adapter.getItemCount()) {
            adapter.notifyItemChanged(index);
        }
    }
}
