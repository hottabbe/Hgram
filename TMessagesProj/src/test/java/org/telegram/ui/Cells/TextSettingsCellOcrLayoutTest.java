package org.telegram.ui.Cells;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.text.Layout;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.ui.Components.AnimatedTextView;

import java.lang.reflect.Field;

@RunWith(RobolectricTestRunner.class)
@Config(application = android.app.Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class TextSettingsCellOcrLayoutTest {

    private static final int WIDTH = 400;
    private static final String LONG_TITLE = "Очень длинное название строки OCR-моделей для проверки";
    private static final String LONG_VALUE = "Загрузка не завершена · Удалить незавершённую загрузку";
    private static final String LONG_ACTION = "Удалить незавершённую загрузку";
    private static final String SHORT_ACTION = "Скачать модели";

    private Context context;
    private FrameLayout host;

    @Before
    public void setup() {
        context = ApplicationProvider.getApplicationContext();
        ApplicationLoader.applicationContext = context;
        LocaleController.isRTL = false;
        AndroidUtilities.density = 1;
        AndroidUtilities.displaySize.set(1080, 1920);
        host = new FrameLayout(context);
    }

    @After
    public void tearDown() {
        LocaleController.isRTL = false;
    }

    private TextSettingsCell newCell() {
        TextSettingsCell cell = new TextSettingsCell(context, 21, 0xff000000, 0xff000000, 0xff000000);
        host.addView(cell, new FrameLayout.LayoutParams(WIDTH, FrameLayout.LayoutParams.WRAP_CONTENT));
        return cell;
    }

    private int availableWidth() {
        return WIDTH - 34;
    }

    private void measure(TextSettingsCell cell) {
        cell.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY));
        cell.layout(0, 0, WIDTH, cell.getMeasuredHeight());
    }

    private static int ellipsisCount(AnimatedTextView view) throws Exception {
        Object drawable = view.getDrawable();
        Field partsField = drawable.getClass().getDeclaredField("currentParts");
        partsField.setAccessible(true);
        Object[] parts = (Object[]) partsField.get(drawable);
        if (parts == null || parts.length == 0) {
            return 0;
        }
        Field layoutField = parts[0].getClass().getDeclaredField("layout");
        layoutField.setAccessible(true);
        Layout layout = (Layout) layoutField.get(parts[0]);
        return layout == null ? 0 : layout.getEllipsisCount(0);
    }

    @Test
    public void longValueStaysInHalfAndTitleNeverNegative() throws Exception {
        TextSettingsCell cell = newCell();
        cell.setBetterLayout(false);
        cell.setTextAndValue("OCR", LONG_VALUE, false);
        cell.setValueEllipsizeToHalf(true);
        measure(cell);

        final int half = availableWidth() / 2;
        final AnimatedTextView value = cell.getValueTextView();
        final TextView title = cell.getTextView();
        assertEquals(half, value.getMeasuredWidth());
        assertTrue(value.getMeasuredWidth() <= half);
        assertTrue("title width must not be negative", title.getMeasuredWidth() >= 0);
        assertEquals(availableWidth() - half - 8, title.getMeasuredWidth());
        assertTrue("long value must show an ellipsis", ellipsisCount(value) > 0);
        assertEquals(50, cell.getMeasuredHeight());
    }

    @Test
    public void repeatedSetTextWithSameLongTextKeepsClampedWidth() throws Exception {
        TextSettingsCell cell = newCell();
        cell.setBetterLayout(false);
        cell.setTextAndValue("OCR", LONG_VALUE, false);
        cell.setValueEllipsizeToHalf(true);
        measure(cell);
        final int half = availableWidth() / 2;
        final AnimatedTextView value = cell.getValueTextView();
        assertEquals(half, value.getMeasuredWidth());

        // The same text on the same bounds must not restore the natural ("screen") width.
        value.setText(LONG_VALUE, false);
        measure(cell);
        assertEquals(half, value.getMeasuredWidth());
        assertEquals(availableWidth() - half - 8, cell.getTextView().getMeasuredWidth());
        assertTrue(ellipsisCount(value) > 0);
        assertTrue(value.getDrawable().getWidth() <= half + 1);
    }

    @Test
    public void actionKeepsItsSpaceWhenTextChanges() {
        TextSettingsCell cell = newCell();
        cell.setBetterLayout(false);
        cell.setTextAndValueAction("OCR", "Загрузка не завершена", SHORT_ACTION, false);
        measure(cell);
        TextView action = cell.getValueActionTextView();
        assertNotNull(action);
        final int shortWidth = action.getMeasuredWidth();
        assertTrue(shortWidth > 0);
        assertEquals(SHORT_ACTION, action.getText().toString());
        assertEquals(50, cell.getMeasuredHeight());

        cell.setTextAndValueAction("OCR", "Загрузка не завершена", LONG_ACTION, false);
        measure(cell);
        final int longWidth = action.getMeasuredWidth();
        assertTrue("long action must be measured again", longWidth > shortWidth);
        assertEquals(LONG_ACTION, action.getText().toString());
        assertTrue("action must fit the row", longWidth <= availableWidth());
        assertEquals("the full action must stay visible", 0, action.getLayout().getEllipsisCount(0));
        assertTrue(cell.getValueTextView().getMeasuredWidth() <= availableWidth() / 2);
        assertTrue(cell.getTextView().getMeasuredWidth() > 0);

        FrameLayout.LayoutParams actionParams = (FrameLayout.LayoutParams) action.getLayoutParams();
        final int actionRight = WIDTH - actionParams.rightMargin;
        assertTrue(actionRight > 0);
        assertTrue(WIDTH - actionParams.rightMargin - longWidth >= 0);
    }

    @Test
    public void ordinaryCellKeepsLegacyGeometry() {
        TextSettingsCell cell = newCell();
        cell.setBetterLayout(true);
        cell.setTextAndValue("Wi-Fi", "On", false);
        measure(cell);

        final int valueWidth = cell.getValueTextView().getMeasuredWidth();
        assertTrue(valueWidth < availableWidth() / 2);
        assertEquals(availableWidth() - valueWidth - 8, cell.getTextView().getMeasuredWidth());
        assertNull(cell.getValueActionTextView());
        assertEquals(50, cell.getMeasuredHeight());
    }

    @Test
    public void longTitleEllipsizesInsideRemainingWidth() {
        TextSettingsCell cell = newCell();
        cell.setBetterLayout(false);
        cell.setTextAndValue(LONG_TITLE, "OK", false);
        cell.setValueEllipsizeToHalf(true);
        measure(cell);

        final int titleWidth = cell.getTextView().getMeasuredWidth();
        assertEquals(availableWidth() - availableWidth() / 2 - 8, titleWidth);
        assertTrue("title must be measured with the remaining width", titleWidth > 0);
        assertTrue("long title must ellipsize", cell.getTextView().getLayout().getEllipsisCount(0) > 0);
        assertTrue(titleWidth + cell.getValueTextView().getMeasuredWidth() + 8 <= availableWidth());
    }

    @Test
    public void rtlLongValueAlsoStaysInHalf() {
        LocaleController.isRTL = true;
        TextSettingsCell cell = newCell();
        cell.setBetterLayout(false);
        cell.setTextAndValue("OCR", LONG_VALUE, false);
        cell.setValueEllipsizeToHalf(true);
        measure(cell);

        final int half = availableWidth() / 2;
        assertTrue(cell.getValueTextView().getMeasuredWidth() <= half);
        assertTrue(cell.getTextView().getMeasuredWidth() >= 0);
        assertTrue(cell.getTextView().getMeasuredWidth() <= availableWidth());
    }
}
