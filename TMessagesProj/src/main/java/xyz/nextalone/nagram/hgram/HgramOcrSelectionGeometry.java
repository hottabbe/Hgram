package xyz.nextalone.nagram.hgram;

/**
 * One affine transform shared by OCR highlight, markers, Copy and touch. The matrix maps
 * normalized OCR coordinates [0..1] directly to viewer/screen coordinates.
 */
public final class HgramOcrSelectionGeometry {

    private final float[] matrix = new float[9];
    private final float[] inverse = new float[9];
    private boolean valid;

    public boolean set(float[] values) {
        valid = false;
        if (values == null || values.length < 9) return false;
        System.arraycopy(values, 0, matrix, 0, 9);
        final float det = matrix[0] * matrix[4] - matrix[1] * matrix[3];
        if (Math.abs(det) < 0.000001f) return false;
        final float inv = 1f / det;
        inverse[0] = matrix[4] * inv;
        inverse[1] = -matrix[1] * inv;
        inverse[2] = (matrix[1] * matrix[5] - matrix[4] * matrix[2]) * inv;
        inverse[3] = -matrix[3] * inv;
        inverse[4] = matrix[0] * inv;
        inverse[5] = (matrix[3] * matrix[2] - matrix[0] * matrix[5]) * inv;
        inverse[6] = 0f;
        inverse[7] = 0f;
        inverse[8] = 1f;
        valid = true;
        return true;
    }

    public boolean isValid() {
        return valid;
    }

    public boolean mapNormalizedToScreen(float nx, float ny, float[] out) {
        if (!valid || out == null || out.length < 2) return false;
        out[0] = matrix[0] * nx + matrix[1] * ny + matrix[2];
        out[1] = matrix[3] * nx + matrix[4] * ny + matrix[5];
        return true;
    }

    public boolean mapScreenToNormalized(float x, float y, float[] out) {
        if (!valid || out == null || out.length < 2) return false;
        out[0] = inverse[0] * x + inverse[1] * y + inverse[2];
        out[1] = inverse[3] * x + inverse[4] * y + inverse[5];
        return true;
    }

    /** Screen-space scale of one normalized unit along each axis. */
    public float screenScaleX() {
        return (float) Math.hypot(matrix[0], matrix[3]);
    }

    public float screenScaleY() {
        return (float) Math.hypot(matrix[1], matrix[4]);
    }

    public static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(value, max));
    }

    /** 1 = anchor, 2 = focus; ties choose focus so a short word is deterministic. */
    public static int nearestMarker(float anchorDistance, float focusDistance) {
        return focusDistance <= anchorDistance ? 2 : 1;
    }

    /**
     * Copy is clickable only where the local rect, the image [0..1] and the actual viewport
     * polygon all overlap. Polygon points are normalized (x0,y0,x1,y1,...); null means [0..1].
     */
    public static boolean copyHit(float left, float top, float right, float bottom,
                                  float[] visiblePolygon, float nx, float ny) {
        if (right <= left || bottom <= top) return false;
        if (nx < 0f || nx > 1f || ny < 0f || ny > 1f) return false;
        if (nx < left || nx > right || ny < top || ny > bottom) return false;
        return visiblePolygon == null || visiblePolygon.length < 6
                || pointInPolygon(visiblePolygon, nx, ny);
    }

    /** Even-odd point-in-polygon for a normalized convex/concave viewport quad. */
    public static boolean pointInPolygon(float[] points, float x, float y) {
        if (points == null || points.length < 6) return false;
        boolean inside = false;
        final int count = points.length / 2;
        for (int i = 0, j = count - 1; i < count; j = i++) {
            final float xi = points[i * 2], yi = points[i * 2 + 1];
            final float xj = points[j * 2], yj = points[j * 2 + 1];
            if (((yi > y) != (yj > y))
                    && (x < (xj - xi) * (y - yi) / (yj - yi) + xi)) {
                inside = !inside;
            }
        }
        return inside;
    }
}
