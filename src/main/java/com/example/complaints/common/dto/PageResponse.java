package com.example.complaints.common.dto;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;

import java.util.List;

/**
 * Wraps a Spring Data {@link Page} for HTTP serialization. See TECHNICAL_DESIGN.md §16.3.
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        List<String> sort
) {
    public static <T> PageResponse<T> from(Page<T> page) {
        List<String> sortDescriptors = page.getSort().stream()
                .map(o -> o.getProperty() + "," + o.getDirection().name().toLowerCase())
                .toList();
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                sortDescriptors
        );
    }

    public static <T, R> PageResponse<R> from(Page<T> page, java.util.function.Function<T, R> mapper) {
        return PageResponse.from(page.map(mapper));
    }

    /** Default sort applied when the request sends no {@code sort} parameter. */
    public static Sort defaultSort() {
        return Sort.by(Sort.Direction.DESC, "createdAt");
    }
}

