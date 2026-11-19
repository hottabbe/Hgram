package xyz.nextalone.nagram.hgram;

import org.junit.Test;
import org.telegram.tgnet.TLRPC;
import java.util.Arrays;
import java.util.Map;
import static org.junit.Assert.*;

public class HgramShortMessageMergerTest {
    private HgramShortMessageMerger.Entry entry(int id, int date, long sender, String text, boolean eligible) {
        return new HgramShortMessageMerger.Entry(id, date, 1, sender, text, eligible);
    }
    @Test public void joinsInChronologicalOrderWithoutLosingIds() {
        Map<Integer, HgramShortMessageMerger.Chain> groups = HgramShortMessageMerger.build(Arrays.asList(
                entry(3, 600, 7, "third", true), entry(2, 500, 7, "second", true), entry(1, 400, 7, "first", true)));
        assertEquals(3, groups.size());
        assertSame(groups.get(1), groups.get(3));
        assertEquals("first\nsecond\nthird", groups.get(3).text);
        assertEquals(Arrays.asList(1, 2, 3), groups.get(3).ids);
        assertEquals(3, groups.get(3).anchorId);
        assertEquals(1, groups.get(3).key);
    }
    @Test public void senderOrServiceBoundaryStopsChain() {
        assertTrue(HgramShortMessageMerger.build(Arrays.asList(entry(3, 600, 7, "a", true),
                entry(2, 500, 8, "b", true), entry(1, 400, 7, "c", true))).isEmpty());
        assertTrue(HgramShortMessageMerger.build(Arrays.asList(entry(3, 600, 7, "a", true),
                entry(2, 500, 7, "service", false), entry(1, 400, 7, "c", true))).isEmpty());
    }
    @Test public void respectsTenMinuteWindowAndSixWordLimit() {
        assertTrue(HgramShortMessageMerger.build(Arrays.asList(entry(2, 1001, 7, "a", true),
                entry(1, 400, 7, "b", true))).isEmpty());
        assertTrue(HgramShortMessageMerger.isShort("раз два три четыре пять шесть"));
        assertFalse(HgramShortMessageMerger.isShort("раз два три четыре пять шесть семь"));
        assertFalse(HgramShortMessageMerger.isShort("   "));
    }
    @Test public void handlesWhitespaceAndLengthBoundaries() {
        assertTrue(HgramShortMessageMerger.isShort("\u0001one\ttwo\nthree\r four\u000bfive\fsix\u0002"));
        assertFalse(HgramShortMessageMerger.isShort("one\ttwo\nthree\r four\u000bfive\fsix seven"));
        assertTrue(HgramShortMessageMerger.isShort("one\u00a0two\u00a0three\u00a0four\u00a0five\u00a0six\u00a0seven"));
        assertFalse(HgramShortMessageMerger.isShort(new String(new char[161]).replace('\0', 'a')));
        assertFalse(HgramShortMessageMerger.isShort(null));
    }
    @Test public void twoConsecutiveMessagesFormAChain() {
        Map<Integer, HgramShortMessageMerger.Chain> groups = HgramShortMessageMerger.build(Arrays.asList(entry(2, 500, 7, "a", true),
                entry(1, 400, 7, "b", true)));
        assertEquals(2, groups.size());
        assertEquals("b\na", groups.get(1).text);
    }
    @Test public void respectsAllConfiguredBoundaries() {
        assertTrue(HgramShortMessageMerger.isShort(new String(new char[159]).replace('\0', 'a')));
        assertTrue(HgramShortMessageMerger.isShort(new String(new char[160]).replace('\0', 'a')));
        assertFalse(HgramShortMessageMerger.isShort(new String(new char[161]).replace('\0', 'a')));
        assertTrue(HgramShortMessageMerger.isShort("one two three four five six"));
        assertFalse(HgramShortMessageMerger.isShort("one two three four five six seven"));

        // 599/600 seconds are inside the window; 601 is outside.
        assertFalse(HgramShortMessageMerger.build(Arrays.asList(entry(2, 1000, 7, "a", true),
                entry(1, 401, 7, "b", true))).isEmpty());
        assertFalse(HgramShortMessageMerger.build(Arrays.asList(entry(2, 1000, 7, "a", true),
                entry(1, 400, 7, "b", true))).isEmpty());
        assertTrue(HgramShortMessageMerger.build(Arrays.asList(entry(2, 1000, 7, "a", true),
                entry(1, 399, 7, "b", true))).isEmpty());

        // The chain is capped at 12 messages; the 13th becomes a separate non-chain entry.
        java.util.List<HgramShortMessageMerger.Entry> thirteen = new java.util.ArrayList<>();
        for (int id = 13; id >= 1; id--) {
            thirteen.add(entry(id, 1000 - (13 - id) * 10, 7, "m" + id, true));
        }
        Map<Integer, HgramShortMessageMerger.Chain> capped = HgramShortMessageMerger.build(thirteen);
        assertEquals(12, capped.size());
        assertEquals(12, capped.get(13).ids.size());
        assertFalse(capped.containsKey(1));
    }

    @Test public void runtimeCanMergeLimitsAndNarrowToggleZone() {
        final TLRPC.TL_message message = new TLRPC.TL_message();
        message.message = new String(new char[100]).replace('\0', 'a');
        assertTrue(HgramShortMessageMerger.canMergeText(message, false));
        assertTrue(HgramShortMessageMerger.canMergeText(message, false, 100, 3));
        message.message = new String(new char[101]).replace('\0', 'a');
        assertFalse(HgramShortMessageMerger.canMergeText(message, false, 100, 3));

        message.message = "one two three";
        assertTrue(HgramShortMessageMerger.canMergeText(message, false, 160, 3));
        message.message = "one two three four";
        assertFalse(HgramShortMessageMerger.canMergeText(message, false, 160, 3));

        assertTrue(HgramShortMessageMerger.isInToggleZone(10, 10, 0, 0, 20, 20));
        assertFalse(HgramShortMessageMerger.isInToggleZone(21, 10, 0, 0, 20, 20));
        assertFalse(HgramShortMessageMerger.isInToggleZone(10, 21, 0, 0, 20, 20));

        // RTL label: lineLeft/lineRight produce a shifted hit box; the old fixed x missed it.
        final float origin = 40;
        final float rtlLineLeft = 22;
        final float rtlLineRight = 138;
        final float left = origin + rtlLineLeft;
        final float right = origin + rtlLineRight;
        assertTrue(HgramShortMessageMerger.isInToggleZone(origin + 30, 10, left, 5, right, 20));
        assertFalse(HgramShortMessageMerger.isInToggleZone(origin + 10, 10, left, 5, right, 20));
        assertFalse(HgramShortMessageMerger.isInToggleZone(right + 1, 10, left, 5, right, 20));
    }

    @Test public void expandedHeightDiffersFromNormalByExactlyFooterInLtrAndRtl() {
        final int text = 36;
        final int names = 12;
        final int reactions = 20;
        final int footer = 24;
        for (int timeRow : new int[]{0, 14, 18}) {
            // Independent oracle: explicit sum, not a second helper call.
            final int expectedNormal = text + names + reactions + timeRow;
            final int normal = HgramShortMessageMerger.normalCellHeight(
                    text, names, 0, reactions, timeRow);
            final int expanded = HgramShortMessageMerger.normalCellHeight(
                    text, names, footer, reactions, timeRow);
            assertEquals(expectedNormal, normal);
            assertEquals(expectedNormal + footer, expanded);
            assertEquals(footer, expanded - normal);

            // drawPinnedTop does not change the ordinary baseline; recovery uses the stable
            // pre-correction names value, so fresh and recovery match.
            final int pinnedFresh = HgramShortMessageMerger.normalCellHeight(
                    text, names, 0, reactions, timeRow);
            final int pinnedRecovery = HgramShortMessageMerger.normalCellHeight(
                    text, names, 0, reactions, timeRow);
            assertEquals(pinnedFresh, pinnedRecovery);
            assertEquals(normal, pinnedFresh);
        }
        assertEquals(24, HgramShortMessageMerger.normalCellHeight(
                text, names, 24, reactions, 14)
                - HgramShortMessageMerger.normalCellHeight(text, names, 0, reactions, 14));
    }

    @Test public void collapsedFallbackHeightIsNeverZeroAndAddsFooter() {
        assertTrue(HgramShortMessageMerger.normalCellHeight(0, 0, 0, 0) > 0);
        assertEquals(48, HgramShortMessageMerger.normalCellHeight(24, 0, 24, 0));
        assertEquals(60, HgramShortMessageMerger.normalCellHeight(24, 12, 24, 0));
        assertEquals(48, HgramShortMessageMerger.normalCellHeight(24, 0, 0, 24));
    }

    @Test public void footerRowLeavesNormalWidthAndUsesItsOwnHitZone() {
        assertEquals(24, HgramShortMessageMerger.COLLAPSE_ROW_HEIGHT_DP);
        // Expanded adds only the footer height; normal bubble width is unchanged.
        final int normalBubbleWidth = 320;
        assertEquals(320, normalBubbleWidth);

        final float left = 40, top = 100, right = 300, bottom = 124;
        assertTrue(HgramShortMessageMerger.isInToggleZone(170, 112, left, top, right, bottom));
        assertFalse(HgramShortMessageMerger.isInToggleZone(170, 90, left, top, right, bottom));
        assertFalse(HgramShortMessageMerger.isInToggleZone(20, 112, left, top, right, bottom));
    }

    @Test public void customLimitsChangeTheChainSet() {
        java.util.List<HgramShortMessageMerger.Entry> entries = new java.util.ArrayList<>();
        for (int id = 5; id >= 1; id--) {
            entries.add(entry(id, 1000 - (5 - id) * 10, 7, "a", true));
        }
        Map<Integer, HgramShortMessageMerger.Chain> normal = HgramShortMessageMerger.build(entries);
        assertEquals(5, normal.get(5).ids.size());

        Map<Integer, HgramShortMessageMerger.Chain> capped = HgramShortMessageMerger.build(
                entries, 160, 6, 600, 2);
        assertEquals(2, capped.get(5).ids.size());

        java.util.List<HgramShortMessageMerger.Entry> longText = Arrays.asList(
                entry(2, 500, 7, "one two", true),
                entry(1, 400, 7, "three four", true));
        assertTrue(HgramShortMessageMerger.build(longText, 160, 1, 600, 12).isEmpty());
        assertFalse(HgramShortMessageMerger.build(longText, 160, 2, 600, 12).isEmpty());
    }

    @Test public void collapsedReadStateCoversIncomingUnreadPartialAndReadChains() {
        final HgramShortMessageMerger.Entry in1 = new HgramShortMessageMerger.Entry(
                1, 400, 1, 7, "a", true, false, false);
        final HgramShortMessageMerger.Entry in2 = new HgramShortMessageMerger.Entry(
                2, 500, 1, 7, "b", true, false, false);
        assertEquals(0, HgramShortMessageMerger.build(Arrays.asList(in2, in1)).get(2).readState());

        final HgramShortMessageMerger.Entry outUnread1 = new HgramShortMessageMerger.Entry(
                3, 600, 1, 7, "c", true, true, false);
        final HgramShortMessageMerger.Entry outUnread2 = new HgramShortMessageMerger.Entry(
                4, 700, 1, 7, "d", true, true, false);
        assertEquals(1, HgramShortMessageMerger.build(Arrays.asList(outUnread2, outUnread1)).get(4).readState());

        final HgramShortMessageMerger.Entry outRead = new HgramShortMessageMerger.Entry(
                5, 800, 1, 7, "e", true, true, true);
        final HgramShortMessageMerger.Entry outUnread = new HgramShortMessageMerger.Entry(
                6, 900, 1, 7, "f", true, true, false);
        assertEquals(2, HgramShortMessageMerger.build(Arrays.asList(outUnread, outRead)).get(6).readState());

        final HgramShortMessageMerger.Entry outRead1 = new HgramShortMessageMerger.Entry(
                7, 1000, 1, 7, "g", true, true, true);
        final HgramShortMessageMerger.Entry outRead2 = new HgramShortMessageMerger.Entry(
                8, 1100, 1, 7, "h", true, true, true);
        assertEquals(3, HgramShortMessageMerger.build(Arrays.asList(outRead2, outRead1)).get(8).readState());
    }

    @Test public void readRefreshGuardDoesNotChangeExpansionInSelectionMode() {
        assertFalse(HgramShortMessageMerger.shouldRefreshChainsForRead(true));
        assertTrue(HgramShortMessageMerger.shouldRefreshChainsForRead(false));
        final HgramShortChainExpansion expansion = new HgramShortChainExpansion();
        assertTrue(expansion.toggle(42));
        final int size = expansion.size();
        if (HgramShortMessageMerger.shouldRefreshChainsForRead(true)) {
            expansion.toggle(42);
        }
        assertTrue(expansion.contains(42));
        assertEquals(size, expansion.size());

        final Map<Integer, HgramShortMessageMerger.Chain> chains = HgramShortMessageMerger.build(Arrays.asList(
                new HgramShortMessageMerger.Entry(41, 1000, 1, Long.MIN_VALUE, "שלום", true, true, false),
                new HgramShortMessageMerger.Entry(42, 1100, 1, Long.MIN_VALUE, "ok", true, true, true)));
        final HgramShortMessageMerger.Chain before = chains.get(41);
        assertEquals(2, before.readState());
        final HgramShortMessageMerger.Chain after =
                HgramShortMessageMerger.withReadCounts(before, before.outgoingCount, before.outgoingCount);
        assertEquals(3, after.readState());
        assertEquals(before.key, after.key);
        assertEquals(before.anchorId, after.anchorId);
        assertEquals(before.ids, after.ids);
        assertEquals(before.text, after.text);
        assertSame(after, HgramShortMessageMerger.withReadCounts(after, after.outgoingCount, after.readCount));
        assertTrue(expansion.contains(42));
        assertEquals(size, expansion.size());

        final HgramShortMessageMerger.Chain snapshot = after;
        assertFalse(HgramShortChainPayload.canApply(false, snapshot.key, snapshot.anchorId,
                snapshot.ids, 41, 7, 7, true, snapshot));
        assertTrue(HgramShortChainPayload.canApply(true, snapshot.key, snapshot.anchorId,
                snapshot.ids, 42, 7, 7, true, snapshot));
        assertFalse(HgramShortChainPayload.canApply(true, snapshot.key, snapshot.anchorId,
                after.ids, 42, 7, 8, true, snapshot));
        assertFalse(HgramShortChainPayload.canApply(true, snapshot.key + 1, snapshot.anchorId,
                snapshot.ids, 42, 7, 7, true, snapshot));
        assertFalse(HgramShortChainPayload.canApply(true, snapshot.key, snapshot.anchorId,
                after.ids, 41, 7, 7, true, snapshot));
        assertTrue(HgramShortChainPayload.canApply(true, snapshot.key, snapshot.anchorId,
                after.ids, 42, 7, 7, false, snapshot));
    }

    @Test public void collapsedChainLayoutKeepsMixedParagraphsAndChecksInsideBubble() {
        final int maxWidth = 340;
        final int reserve = 28;
        final int inset = 4;
        final float bodyWidest = 180f;
        final float labelWidest = 120f;
        final HgramShortChainLayout layout = HgramShortChainLayout.measure(
                bodyWidest, labelWidest, maxWidth, true, reserve, 60);
        assertFalse(layout.checksOnOwnLine);
        assertTrue(layout.contentWidth >= bodyWidest);
        assertTrue(layout.labelWidth >= labelWidest);
        final float contentLeft = 64f;
        final float contentRight = contentLeft + layout.contentWidth;
        final float ltrSlot = HgramShortChainLayout.checksLeft(
                contentLeft, contentRight, false, false, 24, layout.labelWidth, inset);
        final float rtlSlot = HgramShortChainLayout.checksLeft(
                contentLeft, contentRight, true, false, 24, layout.labelWidth, inset);
        assertTrue(ltrSlot + 24 <= contentRight);
        assertTrue(rtlSlot >= contentLeft);
        assertTrue(HgramShortChainLayout.labelOriginX(contentLeft, false, 24, inset) >= contentLeft);
        assertTrue(HgramShortChainLayout.labelOriginX(contentLeft, true, 24, inset) >= contentLeft + 24);

        final int slotWidth = 24;
        final float stableLabel = HgramShortChainLayout.labelOriginX(contentLeft, true, slotWidth, inset);
        final float stableChecks = HgramShortChainLayout.checksLeft(
                contentLeft, contentRight, true, false, slotWidth, layout.labelWidth, inset);
        for (int state = 1; state <= 3; state++) {
            assertEquals(stableLabel, HgramShortChainLayout.labelOriginX(
                    contentLeft, true, slotWidth, inset), 0.01f);
            assertEquals(stableChecks, HgramShortChainLayout.checksLeft(
                    contentLeft, contentRight, true, false, slotWidth, layout.labelWidth, inset), 0.01f);
        }

        final HgramShortChainLayout narrow = HgramShortChainLayout.measure(
                bodyWidest, labelWidest, 140, true, reserve, 60);
        assertTrue(narrow.checksOnOwnLine);
        assertEquals(narrow.contentWidth, narrow.labelWidth);
        assertEquals(contentLeft, HgramShortChainLayout.checksLeft(
                contentLeft, contentLeft + narrow.contentWidth, true, true, 24, narrow.labelWidth, inset), 0.01f);
    }

    @Test public void dialogsClipTracksPanelsThroughActionModeAndFolderSwipe() {
        assertEquals(0, HgramDialogsClip.clipTop(300f, 300f, 900));
        assertEquals(200, HgramDialogsClip.clipTop(380f, 180f, 900));
        assertEquals(140, HgramDialogsClip.clipTop(360f, 220f, 900));
        assertEquals(0, HgramDialogsClip.clipTop(100f, 200f, 900));
        assertEquals(900, HgramDialogsClip.clipTop(2000f, 100f, 900));
        assertEquals(0, HgramDialogsClip.clipTop(200f, 100f, 0));
        assertFalse(HgramDialogsClip.acceptsDown(99f, 100));
        assertTrue(HgramDialogsClip.acceptsDown(100f, 100));
        assertTrue(HgramDialogsClip.acceptsDown(150f, 100));

        final HgramDialogsClip.Cache cache = new HgramDialogsClip.Cache();
        assertTrue(cache.commit(120));
        assertEquals(120, cache.clipTop);
        assertFalse(cache.commit(120));
        assertEquals(120, cache.clipTop);
        assertTrue(cache.commit(0));
        assertEquals(0, cache.clipTop);
    }

    @Test public void paragraphShiftAndClampKeepRtlInsideCollapseRect() {
        assertEquals(-40f, HgramShortMessageMerger.paragraphShift(100f, 60f, true), 0.01f);
        assertEquals(0f, HgramShortMessageMerger.paragraphShift(60f, 60f, true), 0.01f);
        assertEquals(0f, HgramShortMessageMerger.paragraphShift(100f, 60f, false), 0.01f);
        assertEquals(4f, HgramShortMessageMerger.clampLeft(0f, 4f, 20f), 0.01f);
        assertEquals(20f, HgramShortMessageMerger.clampLeft(30f, 4f, 20f), 0.01f);
        assertEquals(12f, HgramShortMessageMerger.clampLeft(12f, 4f, 20f), 0.01f);
    }

    @Test public void acceptsHistoryInOldestFirstOrderAndSkipsDisplaySeparatorsBeforeBuild() {
        Map<Integer, HgramShortMessageMerger.Chain> groups = HgramShortMessageMerger.build(Arrays.asList(
                entry(1, 400, 7, "first", true), entry(2, 500, 7, "second", true), entry(3, 600, 7, "third", true)));
        assertEquals(3, groups.size());
        assertEquals("first\nsecond\nthird", groups.get(1).text);
        assertEquals(3, groups.get(1).anchorId);
    }
}
