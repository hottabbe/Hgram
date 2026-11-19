package xyz.nextalone.nagram.hgram;

/**
 * Pure layout arithmetic for the collapsed short chain: the body uses the exact content
 * width, the label keeps a stable column for the read checks, and both are derived from
 * measured text widths instead of the paragraph direction of the first line.
 */
public final class HgramShortChainLayout {

    public final int contentWidth;
    public final int labelWidth;
    public final boolean checksOnOwnLine;

    private HgramShortChainLayout(int contentWidth, int labelWidth, boolean checksOnOwnLine) {
        this.contentWidth = contentWidth;
        this.labelWidth = labelWidth;
        this.checksOnOwnLine = checksOnOwnLine;
    }

    public static HgramShortChainLayout measure(float bodyWidest, float labelWidest, int maxWidth,
                                                boolean readState, int checkReserve, int minWidth) {
        final boolean ownLine = readState && labelWidest + checkReserve > maxWidth;
        final float desired = Math.max(bodyWidest,
                readState && !ownLine ? labelWidest + checkReserve : labelWidest);
        final int content = Math.max(minWidth, Math.min(maxWidth, (int) Math.ceil(desired)));
        final int label = readState && !ownLine ? content - checkReserve : content;
        return new HgramShortChainLayout(content, label, ownLine);
    }

    public static float labelOriginX(float contentLeft, boolean rtl, int blockWidth, int edgeInset) {
        return rtl ? contentLeft + blockWidth + edgeInset : contentLeft;
    }

    public static float checksLeft(float contentLeft, float contentRight, boolean rtl,
                                   boolean ownLine, int blockWidth, int labelWidth, int edgeInset) {
        if (ownLine) {
            return rtl ? contentLeft : contentRight - blockWidth - edgeInset;
        }
        return rtl ? contentLeft : contentLeft + labelWidth + edgeInset;
    }
}
