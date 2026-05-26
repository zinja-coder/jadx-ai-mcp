package com.zin.jadxaimcp.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * caches decompiled source in compressed form to keep memory usage down.
 *
 * each class source is stored as a compressed byte array instead of a raw string,
 * since large APKs can contain a lot of decompiled code.
 * this cache is thread-safe and intentionaly unbounded for a single loaded APK.
 * entries are kept in memory only and rebuilt as needed.
 *
 * if support for switching projects or loading multiple APKs is added later,
 * this cache should be cleared between loads.
 */

public class DecompilationCache {

    private static final Logger logger = LoggerFactory.getLogger(DecompilationCache.class);
    private static final DecompilationCache INSTANCE = new DecompilationCache();

    private final ConcurrentHashMap<String, byte[]> cache = new ConcurrentHashMap<>();

    //observability counters
    private final AtomicLong hits = new AtomicLong(0);
    private final AtomicLong misses = new AtomicLong(0);
    private final AtomicLong compressedBytes = new AtomicLong(0);
    private final AtomicLong originalBytes = new AtomicLong(0);

    private DecompilationCache() {
    }

    public static DecompilationCache getInstance() {
        return INSTANCE;
    }

    /**
     * store a decompiled source string for the given class name.
     * Compresses the source using Deflate level 1 (BEST_SPEED).
     *
     * @param className fully qualified class name
     * @param source    decompiled Java source code
     */
    public void put(String className, String source) {
        if (className == null || source == null) return;
        byte[] sourceBytes = source.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] compressed = compress(sourceBytes);
        if (compressed != null) {
            byte[] previous = cache.put(className, compressed);
            if (previous == null) {
                // New entry -- track size
                compressedBytes.addAndGet(compressed.length);
                originalBytes.addAndGet(sourceBytes.length);
            } else {
                // replacement -- adjust size delta
                compressedBytes.addAndGet(compressed.length - previous.length);
                // originalBytes is approximate; don't bother adjusting for replacements
            }
        }
    }

    /**
     * retrieve and decompress the cached source for a class.
     * Updates hit/miss counters for observability.
     *
     * @param className fully qualified class name
     * @return decompiled source string, or null if not cached
     */
    public String get(String className) {
        byte[] compressed = cache.get(className);
        if (compressed == null) {
            misses.incrementAndGet();
            return null;
        }
        String decompressed = decompress(compressed);
        if (decompressed == null) {
            // Evict corrupt entry so the next lookup re-decompiles
            cache.remove(className);
            misses.incrementAndGet();
            return null;
        }
        hits.incrementAndGet();
        return decompressed;
    }

    /**
     * check if a class is in the cache without counting as a hit/miss.
     */
    public boolean contains(String className) {
        return cache.containsKey(className);
    }

    /**
     * Clear the entire cache and reset all counters.
     * Call this when opening a new APK/project or for debugging.
     */
    public void clear() {
        int size = cache.size();
        long compBytes = compressedBytes.get();
        cache.clear();
        hits.set(0);
        misses.set(0);
        compressedBytes.set(0);
        originalBytes.set(0);
        logger.info("DecompilationCache cleared: evicted {} entries ({} bytes compressed)", size, compBytes);
    }

    /**
     * get cache statistics as a Map suitable for JSON serialization.
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new java.util.HashMap<>();
        long h = hits.get();
        long m = misses.get();
        long total = h + m;
        stats.put("cached_classes", cache.size());
        stats.put("hits", h);
        stats.put("misses", m);
        stats.put("hit_rate", total > 0 ? String.format("%.1f%%", (h * 100.0) / total) : "N/A");
        stats.put("compressed_bytes", compressedBytes.get());
        stats.put("compressed_mb", String.format("%.1f", compressedBytes.get() / (1024.0 * 1024.0)));
        stats.put("original_bytes", originalBytes.get());
        stats.put("original_mb", String.format("%.1f", originalBytes.get() / (1024.0 * 1024.0)));
        long origBytes = originalBytes.get();
        long compBytes = compressedBytes.get();
        stats.put("compression_ratio", compBytes > 0 ? String.format("%.1fx", (double) origBytes / compBytes) : "N/A");
        return stats;
    }

    // Compression helpers

    /**
     * compress a byte array using Deflate level 1 (BEST_SPEED).
     * Level 1 compresses at ~500 MB/s with 8-15x ratio on Java source ( according to some AI calculations....).
     */
    private static byte[] compress(byte[] data) {
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        try {
            deflater.setInput(data);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(256, data.length / 4));
            byte[] buffer = new byte[8192];
            while (!deflater.finished()) {
                int compressedSize = deflater.deflate(buffer);
                if (compressedSize > 0) {
                    out.write(buffer, 0, compressedSize);
                }
            }
            return out.toByteArray();
        } catch (Exception e) {
            logger.warn("Failed to compress source: {}", e.getMessage());
            return null;
        } finally {
            deflater.end();
        }
    }

    /**
     * decompress a Deflate-compressed byte array back to a UTF-8 string.
     */
    private static String decompress(byte[] compressed) {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(compressed);
            ByteArrayOutputStream out = new ByteArrayOutputStream(compressed.length * 4);
            byte[] buffer = new byte[8192];
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                if (count > 0) {
                    out.write(buffer, 0, count);
                } else if (!inflater.finished()) {
                    logger.warn("Failed to decompress source: inflater stalled");
                    return null;
                }
            }
            return out.toString(java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            logger.warn("Failed to decompress source: {}", e.getMessage());
            return null;
        } finally {
            inflater.end();
        }
    }
}
