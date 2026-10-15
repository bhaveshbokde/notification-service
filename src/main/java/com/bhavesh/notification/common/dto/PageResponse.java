package com.bhavesh.notification.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * Paginated collection envelope.
 *
 * <p>Spring Data's own {@code Page} is deliberately not returned from
 * controllers. Its JSON shape is an implementation detail that has changed
 * between Spring Data versions — it serialises the whole {@code Pageable},
 * including sort internals — and Spring Boot 3.3+ logs a warning about exactly
 * this. A stable, explicit envelope means a Spring Data upgrade cannot silently
 * reshape the API.
 */
@Schema(name = "PageResponse", description = "Paginated collection envelope")
public record PageResponse<T>(

        List<T> content,

        @Schema(example = "0", description = "Zero-based page index")
        int page,

        @Schema(example = "20")
        int size,

        @Schema(example = "137", description = "Total matching elements across all pages")
        long totalElements,

        @Schema(example = "7")
        int totalPages,

        boolean first,
        boolean last,

        @Schema(example = "false", description = "True when this page contains no elements")
        boolean empty
) {

    public static <E, T> PageResponse<T> from(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast(),
                page.isEmpty());
    }

    public static <T> PageResponse<T> from(Page<T> page) {
        return from(page, Function.identity());
    }
}
