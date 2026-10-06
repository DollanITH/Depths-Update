package sayys.depthsupdate.core;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import net.minecraft.world.World;
import net.minecraft.world.WorldType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import sayys.depthsupdate.DepthsUpdateConfig;

/**
 * Manager for per-dimension height contexts.
 *
 * For dimensions that are not registered as extended, HeightContext#VANILLA is returned.
 */
public final class HeightManager {
    private static final Logger LOGGER = LogManager.getLogger("DepthsUpdate/HeightManager");

    private static volatile Map<Integer, HeightContext> contexts = Map.of();

    /**
     * Direct-index companion to {@link #contexts}, used by the per-block-call
     * {@link #get(int)} / {@link #isExtended(int)} paths. Those are reached from mixins
     * that run once per block read, write and light query (hundreds of thousands of
     * times per chunk rebuild), where the map's Integer boxing and hashing showed up in
     * profiling. Rebuilt together with {@code contexts} on every initialize().
     */
    private static volatile HeightContext[] contextByDimension = new HeightContext[0];

    private static volatile HeightContext maxContext = HeightContext.VANILLA;
    private static volatile boolean initialized = false;
    private static final Object INIT_LOCK = new Object();

    private HeightManager() {}

    /**
     * How many dimension ids the direct-index table covers. Vanilla dimensions are
     * -1..1 and mods allocate a handful more, so 256 is generous while staying small.
     * Ids outside this range fall back to a map lookup.
     */
    private static final int DIMENSION_TABLE_SIZE = 256;

    /**
     * Sentinel stored in {@link #contextByDimension} for a dimension that is not
     * height-extended. Distinct from any real context so a plain array read can answer
     * both "which context" and "is it extended".
     */
    private static final HeightContext NOT_EXTENDED = new HeightContext(0, 256, 11, -64, 63);

    /**
     * Reads configuration and builds the per-dimension context map.
     * Called during mod init and on config change events.
     */
    public static void initialize() {
        synchronized (INIT_LOCK) {
            DepthsUpdateConfig.HeightExtension cfg = DepthsUpdateConfig.heightExtension;

            int globalMinY = roundToMultipleOf16(cfg.globalMinY, "globalMinY");
            int globalMaxY = roundToMultipleOf16(cfg.globalMaxY, "globalMaxY");

            // Defensive clamp. HeightContext rejects a non-multiple-of-16 bound by
            // throwing, and initialize() runs from a config static initialiser, so a bad
            // value (e.g. globalMaxY = 401) would otherwise surface as
            // ExceptionInInitializerError while the config class is being loaded, leaving
            // no usable fallback. roundToMultipleOf16() above does not guard against this:
            // it computes (value >> 4) << 4, which is the identity for any value already
            // on the 16-grid but silently leaves one that is not unchanged, so it reports
            // 401 as "401" while still calling it rounded.
            if (globalMaxY % 16 != 0) {
                int clamped = (globalMaxY >> 4) << 4;

                if (clamped < 256) {
                    clamped = 256;
                }

                LOGGER.error("globalMaxY ({}) is not a multiple of 16, clamping to {}", globalMaxY, clamped);
                globalMaxY = clamped;
            }

            if (globalMinY % 16 != 0) {
                int clamped = (globalMinY >> 4) << 4;

                if (clamped > 0) {
                    clamped = 0;
                }

                LOGGER.error("globalMinY ({}) is not a multiple of 16, clamping to {}", globalMinY, clamped);
                globalMinY = clamped;
            }

            if (globalMinY >= globalMaxY) {
                LOGGER.error("globalMinY ({}) must be less than globalMaxY ({}), using defaults", globalMinY, globalMaxY);
                globalMinY = -64;
                globalMaxY = 400;
            }

            int seaLevel = cfg.seaLevel;
            int lavaLevel = cfg.lavaLevel;
            int voidDamageLevel = cfg.voidDamageLevel;

            if (lavaLevel < globalMinY) {
                LOGGER.error("lavaLevel ({}) must be greater than globalMinY ({})!", lavaLevel, globalMinY);
                lavaLevel = globalMinY + 11;
            }

            HeightContext globalContext = new HeightContext(globalMinY, globalMaxY, lavaLevel, voidDamageLevel, seaLevel);

            Map<Integer, HeightContext> newContexts = new HashMap<>();
            HeightContext largest = globalContext;

            // Register extended dimensions with global context
            for (int dimId : cfg.extendedDimensions) {
                newContexts.put(dimId, globalContext);
            }

            // Apply per-dimension overrides
            for (String override : cfg.dimensionOverrides) {
                parseOverride(override, lavaLevel, voidDamageLevel, seaLevel, newContexts);
            }

            // Find the largest context for shared resource sizing
            for (HeightContext ctx : newContexts.values()) {
                if (ctx.primerArraySize() > largest.primerArraySize()) {
                    largest = ctx;
                }
            }

            verifyContextsDoNotOverflowSectionMask(newContexts, globalContext);

            // Publish atomically: build the direct-index table, then swap both references.
            HeightContext[] table = buildDimensionTable(newContexts);

            contexts = Map.copyOf(newContexts);
            contextByDimension = table;
            maxContext = largest;
            initialized = true;

            LOGGER.info("HeightManager initialized: {} extended dimension(s), max context: {}", newContexts.size(), largest);
        }
    }

    /**
     * Flattens the per-dimension contexts into a direct-index array, padding the
     * uncovered slots with {@link #NOT_EXTENDED}. Dimensions outside
     * {@code [0, DIMENSION_TABLE_SIZE)} keep using the map.
     */
    private static HeightContext[] buildDimensionTable(Map<Integer, HeightContext> newContexts) {
        HeightContext[] table = new HeightContext[DIMENSION_TABLE_SIZE];

        java.util.Arrays.fill(table, NOT_EXTENDED);

        for (Map.Entry<Integer, HeightContext> entry : newContexts.entrySet()) {
            int dimId = entry.getKey();

            if (dimId >= 0 && dimId < DIMENSION_TABLE_SIZE) {
                table[dimId] = entry.getValue();
            }
        }

        return table;
    }

    /**
     * The largest number of storage sections the chunk protocol can address.
     *
     * <p>Both the "which sections are present" mask on {@code SPacketChunkData} and the
     * changed-section filter on {@code PlayerChunkMapEntry} are 32-bit ints written as
     * {@code availableSections |= 1 << sectionIndex}. At index 32 that shift wraps
     * around, so the affected sections are silently never sent to the client. The
     * client then keeps an empty section where the server has terrain: the player sees
     * holes ("fake chunks" / 假区块), falls through solid ground, and the mismatch also
     * makes the server believe it has already delivered a section it never did.
     *
     * <p>This is a hard protocol ceiling, not a tuning knob, so exceeding it produces a
     * loud error naming the offending dimension instead of silently corrupting chunks.
     */
    private static final int MAX_STORAGE_SECTIONS = 31;

    private static void verifyContextsDoNotOverflowSectionMask(Map<Integer, HeightContext> newContexts,
                                                               HeightContext globalContext) {
        for (Map.Entry<Integer, HeightContext> entry : newContexts.entrySet()) {
            HeightContext ctx = entry.getValue();

            if (ctx.totalStorageSections() > MAX_STORAGE_SECTIONS) {
                LOGGER.error("Dimension {} requests {} storage sections (minY={}, maxY={}), but the chunk protocol "
                                + "can only address {}. Sections at index >= {} are never sent to the client, which "
                                + "shows up as missing/holes in the world at those heights. Reduce globalMinY/maxY "
                                + "or the per-dimension override so that (maxY - minY) / 16 + 16 <= {}.",
                        entry.getKey(), ctx.totalStorageSections(), ctx.minY(), ctx.maxY(),
                        MAX_STORAGE_SECTIONS, MAX_STORAGE_SECTIONS, MAX_STORAGE_SECTIONS);
            }
        }

        if (!newContexts.containsValue(globalContext) && globalContext.totalStorageSections() > MAX_STORAGE_SECTIONS) {
            LOGGER.error("The global height range requests {} storage sections (minY={}, maxY={}), but the chunk "
                            + "protocol can only address {}.",
                    globalContext.totalStorageSections(), globalContext.minY(), globalContext.maxY(),
                    MAX_STORAGE_SECTIONS);
        }
    }

    /**
     * Ensures initialization has occurred. Safe to call from early mixin code.
     * If not yet initialized, performs initialization immediately.
     */
    public static void ensureInitialized() {
        if (!initialized) {
            initialize();
        }
    }

    /**
     * Returns the HeightContext for the given world.
     *
     * <p>The context is gated by the world's terrain type: height extension only
     * applies to RTG, the vanilla main world, amplified and large-biomes worlds.
     * For any other terrain type (e.g. superflat or debug worlds, which also
     * register dimension 0) the vanilla height range is returned, so those worlds
     * do not use the extended height.
     */
    public static HeightContext get(World world) {
        if (world == null || !isEligibleWorldType(world)) return HeightContext.VANILLA;
        return get(world.provider.getDimension());
    }

    /**
     * Returns the HeightContext for the given dimension ID.
     *
     * <p>Hot path: called per block access through many mixins, so it reads the
     * direct-index table rather than doing a boxed map lookup.
     */
    public static HeightContext get(int dimensionId) {
        ensureInitialized();

        HeightContext[] table = contextByDimension;
        HeightContext ctx = dimensionId >= 0 && dimensionId < table.length ? table[dimensionId] : null;

        if (ctx == null) {
            return contexts.getOrDefault(dimensionId, HeightContext.VANILLA);
        }

        return ctx == NOT_EXTENDED ? HeightContext.VANILLA : ctx;
    }

    /**
     * Returns whether the given world uses extended height.
     * Gated by {@link #isEligibleWorldType(World)}.
     */
    public static boolean isExtended(World world) {
        return world != null && isEligibleWorldType(world) && isExtended(world.provider.getDimension());
    }

    public static boolean isExtended(int dimensionId) {
        ensureInitialized();

        HeightContext[] table = contextByDimension;

        if (dimensionId >= 0 && dimensionId < table.length) {
            return table[dimensionId] != NOT_EXTENDED;
        }

        return contexts.containsKey(dimensionId);
    }

    /**
     * World types eligible for height extension. Only the vanilla terrain types
     * (default, amplified, large biomes and their legacy variants) and RTG's
     * custom world type are eligible. All other terrain types — superflat, debug
     * and customized worlds among them — keep the vanilla height range.
     */
    private static boolean isEligibleWorldType(World world) {
        WorldType worldType = world.getWorldType();
        if (worldType == null) {
            return false;
        }
        if (worldType == WorldType.FLAT || worldType == WorldType.DEBUG_ALL_BLOCK_STATES) {
            return false;
        }
        return true;
    }

    /**
     * Returns the largest HeightContext across all extended dimensions.
     * Used for sizing shared resources like ChunkPrimer arrays.
     */
    public static HeightContext getMaxContext() {
        ensureInitialized();
        return maxContext;
    }

    public static int toStorageIndex(World world, int y) {
        return get(world).toStorageIndex(y);
    }

    public static int fromStorageIndex(World world, int index) {
        return get(world).fromStorageIndex(index);
    }

    public static int getMinY(World world) {
        return get(world).minY();
    }

    public static int getMaxY(World world) {
        return get(world).maxY();
    }

    public static int getTotalHeight(World world) {
        return get(world).totalHeight();
    }

    public static int getStorageSections(World world) {
        return get(world).totalStorageSections();
    }

    public static int getSeaLevel(World world) {
        return get(world).seaLevel();
    }

    public static int getLavaLevel(World world) {
        return get(world).lavaLevel();
    }

    public static int getVoidDamageLevel(World world) {
        return get(world).voidDamageLevel();
    }

    private static int roundToMultipleOf16(int value, String name) {
        int rounded = (value >> 4) << 4;

        if (rounded != value) {
            LOGGER.warn("{} ({}) is not a multiple of 16, rounding to {}", name, value, rounded);
        }

        return rounded;
    }

    private static void parseOverride(String override, int defaultLavaLevel, int defaultVoidDamageLevel, int defaultSeaLevel, Map<Integer, HeightContext> map) {
        String[] parts = override.split(":");

        if (parts.length != 3 && parts.length != 5) {
            LOGGER.error("Invalid dimension override format: '{}' (expected 'dimId:minY:maxY' or 'dimId:minY:maxY:lavaLevel:voidDamageLevel')", override);
            return;
        }

        try {
            int dimId = Integer.parseInt(parts[0].trim());
            int minY = roundToMultipleOf16(Integer.parseInt(parts[1].trim()), "override minY for dim " + dimId);
            int maxY = roundToMultipleOf16(Integer.parseInt(parts[2].trim()), "override maxY for dim " + dimId);
            int lava = parts.length >= 5 ? Integer.parseInt(parts[3].trim()) : defaultLavaLevel;
            int voidDmg = parts.length >= 5 ? Integer.parseInt(parts[4].trim()) : defaultVoidDamageLevel;

            if (minY >= maxY) {
                LOGGER.error("Override for dim {}: minY ({}) must be less than maxY ({})", dimId, minY, maxY);

                return;
            }

            HeightContext ctx = new HeightContext(minY, maxY, lava, voidDmg, defaultSeaLevel);
            map.put(dimId, ctx);
            LOGGER.info("Registered height override for dimension {}: {}", dimId, ctx);
        } catch (NumberFormatException e) {
            LOGGER.error("Invalid number in dimension override: '{}'", override, e);
        } catch (IllegalArgumentException e) {
            LOGGER.error("Invalid height context for override '{}': {}", override, e.getMessage());
        }
    }
}
