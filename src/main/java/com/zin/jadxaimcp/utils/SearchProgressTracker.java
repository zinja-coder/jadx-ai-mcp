package com.zin.jadxaimcp.utils;

import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe singleton that tracks the progress of long-running search operations
 * in the JADX AI MCP plugin. Designed for concurrent access from Javalin request
 * threads and parallel stream workers.
 *
 * <p>Each search operation is identified by a unique searchId to prevent cross-request
 * confusion when multiple searches are triggered in close succession.</p>
 *
 * <p><b>Concurrency limitation:</b> This tracker supports only ONE active search at a
 * time.  If two searches run concurrently, the second {@code startSearch()} call
 * overwrites the first search's tracking state.  The actual search <em>results</em>
 * are unaffected — only the progress counters may be inaccurate during overlap.
 * The {@code searchId} guard on {@code completeSearch}/{@code failSearch} prevents
 * one search from accidentally marking another as completed.</p>
 */
public class SearchProgressTracker {

    private static final SearchProgressTracker INSTANCE = new SearchProgressTracker();

    public enum SearchState {
        IDLE,
        RUNNING,
        COMPLETED,
        FAILED
    }

    // Current search identification
    private final AtomicReference<String> currentSearchId = new AtomicReference<>(null);
    private final AtomicReference<SearchState> state = new AtomicReference<>(SearchState.IDLE);
    private final AtomicReference<String> operationType = new AtomicReference<>("");

    // Progress counters
    private final AtomicInteger scannedCount = new AtomicInteger(0);
    private final AtomicInteger totalCount = new AtomicInteger(0);
    private final AtomicInteger matchesFound = new AtomicInteger(0);

    // Timing
    private final AtomicLong startTimeMs = new AtomicLong(0);
    private final AtomicLong endTimeMs = new AtomicLong(0);

    // Error info
    private final AtomicReference<String> errorMessage = new AtomicReference<>(null);

    private SearchProgressTracker() {
    }

    public static SearchProgressTracker getInstance() {
        return INSTANCE;
    }

    /**
     * Begin tracking a new search operation. Generates a unique searchId.
     * If a previous search was running, it is implicitly superseded.
     *
     * @param type  description of the search type (e.g., "code", "method", "class,field")
     * @param total total number of classes to scan
     * @return the generated searchId for this operation
     */
    public String startSearch(String type, int total) {
        String searchId = UUID.randomUUID().toString();
        // Initialize ALL fields BEFORE setting state to RUNNING.
        // This ensures a concurrent poller never sees RUNNING with stale/zero counters.
        // AtomicReference.set() has volatile-write semantics, so setting state LAST
        // acts as a release fence — any thread that subsequently reads state==RUNNING
        // is guaranteed to see the updated values of all preceding stores.
        currentSearchId.set(searchId);
        operationType.set(type);
        scannedCount.set(0);
        totalCount.set(total);
        matchesFound.set(0);
        startTimeMs.set(System.currentTimeMillis());
        endTimeMs.set(0);
        errorMessage.set(null);
        state.set(SearchState.RUNNING);
        return searchId;
    }

    /**
     * Increment the scanned counter. Called from parallel stream workers.
     */
    public void incrementScanned() {
        scannedCount.incrementAndGet();
    }

    /**
     * Increment the matches counter. Called from parallel stream workers.
     */
    public void incrementMatches() {
        matchesFound.incrementAndGet();
    }

    /**
     * Mark the search as completed successfully.
     *
     * @param searchId the searchId returned by startSearch — only completes if it
     *                 matches the current active search (prevents stale completions)
     * @param finalMatches the final match count
     */
    public void completeSearch(String searchId, int finalMatches) {
        if (searchId != null && searchId.equals(currentSearchId.get())) {
            matchesFound.set(finalMatches);
            endTimeMs.set(System.currentTimeMillis());
            state.set(SearchState.COMPLETED);
        }
    }

    /**
     * Mark the search as failed.
     *
     * @param searchId the searchId returned by startSearch
     * @param error    description of the failure
     */
    public void failSearch(String searchId, String error) {
        if (searchId != null && searchId.equals(currentSearchId.get())) {
            errorMessage.set(error);
            endTimeMs.set(System.currentTimeMillis());
            state.set(SearchState.FAILED);
        }
    }

    /**
     * Get the current progress snapshot as a Map suitable for JSON serialization.
     * This is called by the /search-progress endpoint.
     */
    public Map<String, Object> getProgress() {
        Map<String, Object> progress = new HashMap<>();
        SearchState currentState = state.get();
        String searchId = currentSearchId.get();
        int scanned = scannedCount.get();
        int total = totalCount.get();

        progress.put("search_id", searchId != null ? searchId : "");
        progress.put("state", currentState.name().toLowerCase());
        progress.put("operation_type", operationType.get());
        progress.put("scanned", scanned);
        progress.put("total", total);
        progress.put("matches", matchesFound.get());

        long start = startTimeMs.get();
        if (start > 0) {
            long elapsed;
            if (currentState == SearchState.RUNNING) {
                elapsed = System.currentTimeMillis() - start;
            } else {
                long end = endTimeMs.get();
                elapsed = end > 0 ? end - start : 0;
            }
            progress.put("elapsed_ms", elapsed);
        } else {
            progress.put("elapsed_ms", 0);
        }

        if (currentState == SearchState.FAILED) {
            progress.put("error", errorMessage.get());
        }

        // Flag potentially stale searches — if RUNNING for over 15 minutes,
        // the search thread likely crashed without calling completeSearch/failSearch
        if (currentState == SearchState.RUNNING && start > 0) {
            long runningMs = System.currentTimeMillis() - start;
            if (runningMs > 15 * 60 * 1000L) {
                progress.put("stale", true);
            }
        }

        return progress;
    }
}
