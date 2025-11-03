package jadx.gui.plugins;

import io.javalin.http.Context;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.ArrayList;


// Utility class for handling pagination across different MCP tools
public class JadxAIMCPPaginationUtils {

    // Configuration constants
    public static final int DEFAULT_PAGE_SIZE = 100;
    public static final int MAX_PAGE_SIZE = 10000;
    public static final int MAX_OFFSET = 1000000;

    // Generic pagination handler that can be used by any endpoint
    public static <T> Map<String, Object> handlePagination(
            Context ctx,
            List<T> allItems,
            String dataType,
            String itemsKey) throws PaginationException {

        return handlePagination(ctx, allItems, dataType, itemsKey, item -> item.toString());
    }

    // Generic pagination handler with custom item transformer
    public static <T> Map<String, Object> handlePagination(
            Context ctx,
            List<T> allItems,
            String dataType,
            String itemsKey,
            Function<T, Object> itemTransformer) throws PaginationException {

        if (allItems == null) {
            allItems = new ArrayList<>();
        }

        int totalItems = allItems.size();

        // Parse pagination parameters
        PaginationParams params = parsePaginationParams(ctx, totalItems);

        // Calculate bounds
        PaginationBounds bounds = calculatePaginationBounds(params, totalItems);

        // Transform and extract paginated subset
        List<Object> transformedItems = allItems.subList(bounds.startIndex, bounds.endIndex)
                .stream()
                .map(itemTransformer)
                .collect(Collectors.toList());

        // Build response
        return buildPaginationResponse(transformedItems, params, bounds, totalItems, dataType, itemsKey);
    }

    // Parse and validate pagination parameters
    private static PaginationParams parsePaginationParams(Context ctx, int totalItems) throws PaginationException {
        String offsetParam = ctx.queryParam("offset");
        String limitParam = ctx.queryParam("limit");
        String countParam = ctx.queryParam("count"); // Legacy support

        // Use 'limit' if provided, otherwise fall back to 'count'
        String pageSizeParam = limitParam != null ? limitParam : countParam;

        int offset = 0;
        int requestedLimit = 0;
        boolean hasCustomLimit = pageSizeParam != null && !pageSizeParam.isEmpty();

        // Parse offset
        if (offsetParam != null && !offsetParam.isEmpty()) {
            try {
                offset = Integer.parseInt(offsetParam.trim());
                if (offset < 0) {
                    throw new PaginationException("Offset must be non-negative, got: " + offset);
                }
                if (offset > MAX_OFFSET) {
                    throw new PaginationException("Offset too large, maximum: " + MAX_OFFSET);
                }
            } catch (NumberFormatException e) {
                throw new PaginationException("Invalid offset format: '" + offsetParam + "'");
            }
        }

        // Parse limit/count
        if (hasCustomLimit) {
            try {
                requestedLimit = Integer.parseInt(pageSizeParam.trim());
                if (requestedLimit < 0) {
                    throw new PaginationException("Limit must be non-negative, got: " + requestedLimit);
                }
                if (requestedLimit > MAX_PAGE_SIZE) {
                    throw new PaginationException("Limit too large, maximum: " + MAX_PAGE_SIZE);
                }
            } catch (NumberFormatException e) {
                throw new PaginationException("Invalid limit format: '" + pageSizeParam + "'");
            }
        }

        // Determine effective limit
        int effectiveLimit;
        if (hasCustomLimit) {
            effectiveLimit = requestedLimit == 0 ? Math.max(0, totalItems - offset) : requestedLimit;
        } else {
            effectiveLimit = Math.min(DEFAULT_PAGE_SIZE, Math.max(0, totalItems - offset));
        }

        effectiveLimit = Math.max(0, Math.min(effectiveLimit, totalItems - offset));

        return new PaginationParams(offset, effectiveLimit, requestedLimit, hasCustomLimit);
    }

    // Calculate pagination boundaries
    private static PaginationBounds calculatePaginationBounds(PaginationParams params, int totalItems) {
        if (params.offset >= totalItems) {
            return new PaginationBounds(0, 0, false, totalItems);
        }

        int startIndex = params.offset;
        int endIndex = Math.min(startIndex + params.limit, totalItems);
        boolean hasMore = endIndex < totalItems;
        int nextOffset = hasMore ? endIndex : -1;

        return new PaginationBounds(startIndex, endIndex, hasMore, nextOffset);
    }

    // Build comprehensive pagination response
    private static Map<String, Object> buildPaginationResponse(
            List<Object> data,
            PaginationParams params,
            PaginationBounds bounds,
            int totalItems,
            String dataType,
            String itemsKey) {

        Map<String, Object> result = new HashMap<>();

        // Core data
        result.put("type", dataType);
        result.put(itemsKey, data);

        // Pagination metadata
        Map<String, Object> pagination = new HashMap<>();
        pagination.put("total", totalItems);
        pagination.put("offset", params.offset);
        pagination.put("limit", params.limit);
        pagination.put("count", data.size());
        pagination.put("has_more", bounds.hasMore);

        // Navigation helpers
        if (bounds.hasMore) {
            pagination.put("next_offset", bounds.nextOffset);
        }

        if (params.offset > 0) {
            int prevOffset = Math.max(0, params.offset - params.limit);
            pagination.put("prev_offset", prevOffset);
        }

        // Page calculations
        if (params.limit > 0) {
            int currentPage = (params.offset / params.limit) + 1;
            int totalPages = (int) Math.ceil((double) totalItems / params.limit);
            pagination.put("current_page", currentPage);
            pagination.put("total_pages", totalPages);
            pagination.put("page_size", params.limit);
        }

        // Legacy compatibility
        result.put("requested_count", params.requestedLimit);
        result.put("pagination", pagination);

        return result;
    }

    // Helper classes remain the same as before
    private static class PaginationParams {
        final int offset;
        final int limit;
        final int requestedLimit;
        final boolean hasCustomLimit;

        PaginationParams(int offset, int limit, int requestedLimit, boolean hasCustomLimit) {
            this.offset = offset;
            this.limit = limit;
            this.requestedLimit = requestedLimit;
            this.hasCustomLimit = hasCustomLimit;
        }
    }

    private static class PaginationBounds {
        final int startIndex;
        final int endIndex;
        final boolean hasMore;
        final int nextOffset;

        PaginationBounds(int startIndex, int endIndex, boolean hasMore, int nextOffset) {
            this.startIndex = startIndex;
            this.endIndex = endIndex;
            this.hasMore = hasMore;
            this.nextOffset = nextOffset;
        }
    }

    public static class PaginationException extends Exception {
        public PaginationException(String message) {
            super(message);
        }
    }
}