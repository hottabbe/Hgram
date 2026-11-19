package tw.nekomimi.nekogram.settings;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.Switch;

import java.util.ArrayList;
import java.util.List;

import xyz.nextalone.nagram.hgram.HgramSummarizerModel;

/**
 * Themed two-column picker for OpenRouter summarizer models.
 * Free models are shown by default; the switch also enables paid ones.
 */
public final class HgramModelPickerDialog {

    public interface Listener {
        void onModelSelected(String modelId);

        void onShowPaidChanged(boolean showPaid);
    }

    private static final int MAX_VISIBLE_ROWS = 6;
    private static final int ROW_HEIGHT_DP = 58;
    private static final int CHECK_WIDTH_DP = 24;

    private HgramModelPickerDialog() {
    }

    public static AlertDialog show(Context context, java.util.List<HgramSummarizerModel> models,
                                   String selectedId, boolean showPaid, int estimatedSummaries,
                                   Listener listener) {
        final boolean rtl = LocaleController.isRTL;

        final LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(18), dp(20), 0);
        root.setBackgroundColor(Theme.getColor(Theme.key_dialogBackground));
        root.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);

        final TextView title = new TextView(context);
        title.setText(LocaleController.getString(R.string.HgramSummarizerModelsTitle));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        title.setSingleLine(true);
        title.setGravity((rtl ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
        root.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final TextView hint = new TextView(context);
        hint.setText(LocaleController.formatString(R.string.HgramSummarizerModelsHint,
                String.valueOf(estimatedSummaries)));
        hint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        hint.setTextColor(Theme.getColor(Theme.key_dialogTextGray));
        hint.setGravity((rtl ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
        root.addView(hint, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT, 0, 6, 0, 10));

        final LinearLayout switchRow = new LinearLayout(context);
        switchRow.setOrientation(LinearLayout.HORIZONTAL);
        switchRow.setGravity(Gravity.CENTER_VERTICAL);
        switchRow.setMinimumHeight(dp(48));

        final TextView switchLabel = new TextView(context);
        switchLabel.setText(LocaleController.getString(R.string.HgramSummarizerShowPaid));
        switchLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        switchLabel.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        switchLabel.setGravity((rtl ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
        switchRow.addView(switchLabel, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        final Switch switchView = new Switch(context, null);
        switchView.setColors(
                Theme.key_switchTrack,
                Theme.key_switchTrackChecked,
                Theme.key_windowBackgroundWhite,
                Theme.key_windowBackgroundWhite);
        switchView.setChecked(showPaid, false);
        switchRow.addView(switchView, LayoutHelper.createLinear(37, 20, Gravity.CENTER_VERTICAL));
        root.addView(switchRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT));

        final ArrayList<HgramSummarizerModel> allModels = new ArrayList<>();
        if (models != null) {
            allModels.addAll(models);
        }
        HgramSummarizerModel.sort(allModels);

        final ArrayList<HgramSummarizerModel> visibleModels = new ArrayList<>();
        final ModelAdapter adapter = new ModelAdapter(context, visibleModels, selectedId);

        final FrameLayout listContainer = new FrameLayout(context);
        final ListView listView = new ListView(context);
        listView.setDivider(null);
        listView.setDividerHeight(0);
        listView.setVerticalScrollBarEnabled(false);
        listView.setAdapter(adapter);
        listContainer.addView(listView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        final TextView emptyView = new TextView(context);
        emptyView.setText(LocaleController.getString(R.string.HgramSummarizerModelsEmpty));
        emptyView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        emptyView.setTextColor(Theme.getColor(Theme.key_dialogTextGray));
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setVisibility(View.GONE);
        listContainer.addView(emptyView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        root.addView(listContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,
                listHeight(MAX_VISIBLE_ROWS), 0, 2, 0, 12));

        final AlertDialog[] holder = new AlertDialog[1];
        final boolean[] paidState = {showPaid};

        applyFilter(allModels, visibleModels, paidState[0], listContainer, listView, emptyView, adapter);

        switchView.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked == paidState[0]) {
                return;
            }
            paidState[0] = isChecked;
            applyFilter(allModels, visibleModels, isChecked, listContainer, listView,
                    emptyView, adapter);
            if (listener != null) {
                listener.onShowPaidChanged(isChecked);
            }
        });
        switchRow.setOnClickListener(v -> switchView.setChecked(!switchView.isChecked(), true));

        listView.setOnItemClickListener((parent, view, position, id) -> {
            final HgramSummarizerModel model = adapter.getItem(position);
            if (listener != null && model != null) {
                listener.onModelSelected(model.id);
            }
            if (holder[0] != null) {
                holder[0].dismiss();
            }
        });

        final AlertDialog dialog = new AlertDialog.Builder(context)
                .setView(root)
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .create();
        holder[0] = dialog;
        dialog.show();
        return dialog;
    }

    private static void applyFilter(java.util.List<HgramSummarizerModel> allModels,
                                    java.util.List<HgramSummarizerModel> visibleModels,
                                    boolean showPaid, FrameLayout listContainer, ListView listView,
                                    TextView emptyView, BaseAdapter adapter) {
        visibleModels.clear();
        for (int i = 0; i < allModels.size(); i++) {
            final HgramSummarizerModel model = allModels.get(i);
            if (model == null) {
                continue;
            }
            if (showPaid || HgramSummarizerModel.isFreeModel(model.id, model.promptPricePer1M, model.completionPricePer1M)) {
                visibleModels.add(model);
            }
        }
        android.util.Log.d("HGRAM_SUM", "picker visible=" + visibleModels.size()
                + " total=" + allModels.size() + " showPaid=" + showPaid);
        final boolean empty = visibleModels.isEmpty();
        listView.setVisibility(empty ? View.GONE : View.VISIBLE);
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        final ViewGroup.LayoutParams params = listContainer.getLayoutParams();
        if (params != null) {
            params.height = listHeight(visibleModels.size());
            listContainer.setLayoutParams(params);
        }
        adapter.notifyDataSetChanged();
    }

    private static int listHeight(int rows) {
        final int visibleRows = Math.max(1, Math.min(rows, MAX_VISIBLE_ROWS));
        final int byRows = dp(ROW_HEIGHT_DP * visibleRows);
        final int byScreen = (int) (AndroidUtilities.displaySize.y * 0.55f);
        return Math.max(dp(ROW_HEIGHT_DP), Math.min(byRows, byScreen));
    }

    private static int dp(int value) {
        return AndroidUtilities.dp(value);
    }

    private static final class ViewHolder {
        final View row;
        final TextView name;
        final TextView id;
        final TextView price;
        final TextView check;

        ViewHolder(View row, TextView name, TextView id, TextView price, TextView check) {
            this.row = row;
            this.name = name;
            this.id = id;
            this.price = price;
            this.check = check;
        }
    }

    private static final class ModelAdapter extends BaseAdapter {
        private final Context context;
        private final List<HgramSummarizerModel> models;
        private final String selectedId;
        private final int accentColor;

        ModelAdapter(Context context, List<HgramSummarizerModel> models, String selectedId) {
            this.context = context;
            this.models = models;
            this.selectedId = selectedId == null ? "" : selectedId;
            this.accentColor = Theme.getColor(Theme.key_windowBackgroundWhiteGreenText);
        }

        @Override
        public int getCount() {
            return models.size();
        }

        @Override
        public HgramSummarizerModel getItem(int position) {
            return models.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final ViewHolder holder;
            if (convertView == null) {
                holder = createRow(context);
                convertView = holder.row;
                convertView.setTag(holder);
            } else {
                holder = (ViewHolder) convertView.getTag();
            }
            final HgramSummarizerModel model = models.get(position);
            final String id = model.id == null ? "" : model.id;
            final String name = TextUtils.isEmpty(model.name) ? id : model.name;
            holder.name.setText(name);
            if (!id.isEmpty() && !id.equals(name)) {
                holder.id.setText(id);
                holder.id.setVisibility(View.VISIBLE);
            } else {
                holder.id.setVisibility(View.GONE);
            }
            if (HgramSummarizerModel.isFreeModel(model.id, model.promptPricePer1M, model.completionPricePer1M)) {
                holder.price.setText(LocaleController.getString(R.string.HgramSummarizerFree));
                holder.price.setTextColor(accentColor);
            } else {
                holder.price.setText(HgramSummarizerModel.formatPrice(
                        model.promptPricePer1M, model.completionPricePer1M));
                holder.price.setTextColor(Theme.getColor(Theme.key_dialogTextGray));
            }
            holder.check.setText(id.equals(selectedId) ? "✓" : "");
            holder.check.setTextColor(accentColor);
            return convertView;
        }

        private static ViewHolder createRow(Context context) {
            final boolean rtl = LocaleController.isRTL;

            final LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dp(56));
            row.setPadding(0, dp(6), 0, dp(6));
            row.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
            row.setBackground(Theme.createRadSelectorDrawable(0x00000000,
                    Theme.getColor(Theme.key_listSelector), 0, 0));

            final LinearLayout textColumn = new LinearLayout(context);
            textColumn.setOrientation(LinearLayout.VERTICAL);
            textColumn.setGravity((rtl ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);

            final TextView name = new TextView(context);
            name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            name.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setGravity((rtl ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
            textColumn.addView(name, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,
                    LayoutHelper.WRAP_CONTENT));

            final TextView id = new TextView(context);
            id.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            id.setTextColor(Theme.getColor(Theme.key_dialogTextGray));
            id.setSingleLine(true);
            id.setEllipsize(TextUtils.TruncateAt.END);
            id.setVisibility(View.GONE);
            id.setGravity((rtl ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
            textColumn.addView(id, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,
                    LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

            row.addView(textColumn, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

            final LinearLayout priceColumn = new LinearLayout(context);
            priceColumn.setOrientation(LinearLayout.HORIZONTAL);
            priceColumn.setGravity(Gravity.CENTER_VERTICAL);

            final TextView price = new TextView(context);
            price.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            price.setTextColor(Theme.getColor(Theme.key_dialogTextGray));
            price.setSingleLine(true);
            price.setGravity((rtl ? Gravity.LEFT : Gravity.RIGHT) | Gravity.CENTER_VERTICAL);
            priceColumn.addView(price, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT,
                    LayoutHelper.WRAP_CONTENT));

            final TextView check = new TextView(context);
            check.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            check.setTypeface(AndroidUtilities.bold());
            check.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGreenText));
            check.setGravity(Gravity.CENTER);
            priceColumn.addView(check, LayoutHelper.createLinear(dp(CHECK_WIDTH_DP),
                    LayoutHelper.WRAP_CONTENT));

            row.addView(priceColumn, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT,
                    LayoutHelper.WRAP_CONTENT, 8, 0, 0, 0));
            return new ViewHolder(row, name, id, price, check);
        }
    }
}
