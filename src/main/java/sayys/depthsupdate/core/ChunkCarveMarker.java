package sayys.depthsupdate.core;

/**
 * Marker interface for the deep-carve flag that {@code MixinChunk} stores on every
 * chunk.
 *
 * <p>This is deliberately a plain interface, not a mixin: the flag is a {@code @Unique}
 * field injected by {@code MixinChunk}, and a {@code @Accessor} in a separate mixin
 * cannot resolve a field that another mixin injects (Mixin reports "Cannot find field
 * in target class"). {@code MixinChunk} therefore implements this interface and
 * provides the two methods; other mixins reach them by casting the chunk to this
 * interface.
 */
public interface ChunkCarveMarker {

    boolean depthsupdate$isCarved();

    void depthsupdate$setCarved(boolean carved);
}
