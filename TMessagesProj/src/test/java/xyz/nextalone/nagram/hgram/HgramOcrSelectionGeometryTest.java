package xyz.nextalone.nagram.hgram;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HgramOcrSelectionGeometryTest {

    private static HgramOcrSelectionGeometry geometry(float sx, float sy, float tx, float ty) {
        final HgramOcrSelectionGeometry geometry = new HgramOcrSelectionGeometry();
        assertTrue(geometry.set(new float[]{sx, 0f, tx, 0f, sy, ty, 0f, 0f, 1f}));
        return geometry;
    }

    @Test
    public void normalizedToScreenRoundTripWithScaleAndPan() {
        final HgramOcrSelectionGeometry geometry = geometry(2.5f, 3f, 120f, -40f);
        final float[] screen = new float[2];
        final float[] back = new float[2];
        assertTrue(geometry.mapNormalizedToScreen(0.25f, 0.75f, screen));
        assertEquals(120.625f, screen[0], 0.001f);
        assertEquals(-37.75f, screen[1], 0.001f);
        assertTrue(geometry.mapScreenToNormalized(screen[0], screen[1], back));
        assertEquals(0.25f, back[0], 0.001f);
        assertEquals(0.75f, back[1], 0.001f);
        assertEquals(2.5f, geometry.screenScaleX(), 0.001f);
        assertEquals(3f, geometry.screenScaleY(), 0.001f);
    }

    @Test
    public void clampKeepsMarkersInsideImage() {
        final float left = 100f, top = 200f, right = 500f, bottom = 800f;
        assertEquals(left, HgramOcrSelectionGeometry.clamp(50f, left, right), 0f);
        assertEquals(right, HgramOcrSelectionGeometry.clamp(700f, left, right), 0f);
        assertEquals(top, HgramOcrSelectionGeometry.clamp(0f, top, bottom), 0f);
        assertEquals(bottom, HgramOcrSelectionGeometry.clamp(900f, top, bottom), 0f);
    }

    @Test
    public void degenerateMatrixIsRejected() {
        final HgramOcrSelectionGeometry geometry = new HgramOcrSelectionGeometry();
        assertTrue(!geometry.set(new float[]{0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f}));
        assertTrue(!geometry.isValid());
    }

    @Test
    public void rotatedAndFlippedCornersAreNormalized() {
        final float[] values = {-2f, 0f, 1f, 0f, -2f, 1f, 0f, 0f, 1f};
        final HgramOcrSelectionGeometry geometry = new HgramOcrSelectionGeometry();
        assertTrue(geometry.set(values));
        final float[] a = new float[2];
        final float[] b = new float[2];
        assertTrue(geometry.mapNormalizedToScreen(0.1f, 0.2f, a));
        assertTrue(geometry.mapNormalizedToScreen(0.9f, 0.8f, b));
        assertTrue(Math.min(a[0], b[0]) < Math.max(a[0], b[0]));
        assertTrue(Math.min(a[1], b[1]) < Math.max(a[1], b[1]));
        final float[] back = new float[2];
        assertTrue(geometry.mapScreenToNormalized(a[0], a[1], back));
        assertEquals(0.1f, back[0], 0.001f);
        assertEquals(0.2f, back[1], 0.001f);
    }

    @Test
    public void copyHitRejectsClippedAreaOnNarrowPhoto() {
        final float[] visible = {0.2f, 0.1f, 0.8f, 0.1f, 0.8f, 0.9f, 0.2f, 0.9f};
        // Local Copy rect is wider than the photo; only the visible polygon counts.
        assertTrue(HgramOcrSelectionGeometry.copyHit(
                0.1f, 0.2f, 1.3f, 0.5f, visible, 0.5f, 0.3f));
        assertTrue(!HgramOcrSelectionGeometry.copyHit(
                0.1f, 0.2f, 1.3f, 0.5f, visible, 1.1f, 0.3f));
        assertTrue(!HgramOcrSelectionGeometry.copyHit(
                0.1f, 0.2f, 1.3f, 0.5f, visible, 0.9f, 0.3f));
        assertTrue(!HgramOcrSelectionGeometry.copyHit(
                0.2f, 0.2f, 0.2f, 0.4f, visible, 0.2f, 0.3f));
    }

    @Test
    public void obliqueViewportUsesFourCornerPolygon() {
        final float[] visible = {0.1f, 0.2f, 0.9f, 0.1f, 0.8f, 0.9f, 0.2f, 0.8f};
        assertTrue(HgramOcrSelectionGeometry.copyHit(
                0.1f, 0.1f, 0.9f, 0.9f, visible, 0.5f, 0.5f));
        assertTrue(!HgramOcrSelectionGeometry.copyHit(
                0.1f, 0.1f, 0.9f, 0.9f, visible, 0.15f, 0.15f));
    }

    @Test
    public void nearestMarkerTiesChooseFocus() {
        assertEquals(1, HgramOcrSelectionGeometry.nearestMarker(5f, 10f));
        assertEquals(2, HgramOcrSelectionGeometry.nearestMarker(10f, 5f));
        assertEquals(2, HgramOcrSelectionGeometry.nearestMarker(5f, 5f));
    }
}
