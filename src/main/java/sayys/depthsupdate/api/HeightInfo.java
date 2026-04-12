package sayys.depthsupdate.api;

/**
 * View of a dimension's height configuration.
 */
public interface HeightInfo {
    /** Minimum Y coordinate (inclusive). Vanilla: 0, extended default: -64. */
    int minY();

    /** Maximum Y coordinate (exclusive). Vanilla: 256, extended default: 320. */
    int maxY();

    /** Total height in blocks: maxY() - minY(). */
    int totalHeight();

    /** Sea level Y coordinate. Default: 63. */
    int seaLevel();

    /** Y level at which underground air is replaced with lava. Default: -54. */
    int lavaLevel();

    /** Y level at which entities start taking void damage. Default: -128. */
    int voidDamageLevel();

    /** Whether this dimension extends beyond vanilla [0, 256). */
    boolean isExtended();

    /** Whether the given Y coordinate is within this dimension's build bounds. */
    boolean isInBounds(int y);
}
