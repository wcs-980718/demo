package com.yiwei.midplat.exceptionlog;

import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Locale;
import org.springframework.data.jpa.domain.Specification;

final class ExceptionReportSpecifications {

    private ExceptionReportSpecifications() {}

    static Specification<ExceptionReport> matching(
            String platformId,
            String method,
            Integer status,
            String keyword,
            Instant occurredFrom,
            Instant occurredTo) {
        return (root, query, builder) -> {
            var predicates = new ArrayList<Predicate>();
            var normalizedPlatformId = trimToNull(platformId);
            var normalizedMethod = trimToNull(method);
            var normalizedKeyword = trimToNull(keyword);

            if (normalizedPlatformId != null) {
                predicates.add(builder.equal(root.get("platformId"), normalizedPlatformId));
            }
            if (normalizedMethod != null) {
                predicates.add(builder.equal(root.get("method"), normalizedMethod.toUpperCase(Locale.ROOT)));
            }
            if (status != null) {
                predicates.add(builder.equal(root.get("status"), status));
            }
            if (normalizedKeyword != null) {
                String pattern = "%" + escapeLike(normalizedKeyword.toLowerCase(Locale.ROOT)) + "%";
                predicates.add(builder.or(
                        builder.like(builder.lower(root.get("url")), pattern, '\\'),
                        builder.like(builder.lower(root.get("errorMessage")), pattern, '\\')));
            }
            if (occurredFrom != null) {
                predicates.add(builder.greaterThanOrEqualTo(root.<Instant>get("occurredAt"), occurredFrom));
            }
            if (occurredTo != null) {
                predicates.add(builder.lessThanOrEqualTo(root.<Instant>get("occurredAt"), occurredTo));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
