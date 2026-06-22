package com.example.complaints.complaint.service;

import com.example.complaints.complaint.model.Complaint;
import com.example.complaints.complaint.model.ComplaintSeverity;
import com.example.complaints.complaint.model.ComplaintStatus;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.Collection;

/**
 * Composable Spring Data {@link Specification} factories for the complaint list endpoint
 * (Stage 16). Kept as static factory methods rather than a registry — there are only a handful
 * of predicates, each is one line, and a registry would add ceremony without flexibility win.
 *
 * <p>Callers compose with {@code Specification.allOf(...)} (Spring Data 4.x) so {@code null}
 * arms short-circuit cleanly.</p>
 */
final class ComplaintSpecifications {

    private ComplaintSpecifications() {}

    static Specification<Complaint> statusEq(ComplaintStatus status) {
        if (status == null) return null;
        return (root, q, cb) -> cb.equal(root.get("status"), status);
    }

    static Specification<Complaint> severityEq(ComplaintSeverity severity) {
        if (severity == null) return null;
        return (root, q, cb) -> cb.equal(root.get("severity"), severity);
    }

    static Specification<Complaint> categoryEq(Long categoryId) {
        if (categoryId == null) return null;
        return (root, q, cb) -> cb.equal(root.get("categoryId"), categoryId);
    }

    static Specification<Complaint> dcEq(Long dcId) {
        if (dcId == null) return null;
        return (root, q, cb) -> cb.equal(root.get("distributionCenterId"), dcId);
    }

    static Specification<Complaint> dcIn(Collection<Long> dcIds) {
        if (dcIds == null) return null;
        if (dcIds.isEmpty()) {
            // Admin in a subdivision with no DCs -> match nothing without breaking the query.
            return (root, q, cb) -> cb.disjunction();
        }
        return (root, q, cb) -> root.get("distributionCenterId").in(dcIds);
    }

    static Specification<Complaint> technicianEq(Long technicianId) {
        if (technicianId == null) return null;
        return (root, q, cb) -> cb.equal(root.get("assignedTechnicianId"), technicianId);
    }

    static Specification<Complaint> consumerMasterIdEq(Long consumerMasterId) {
        if (consumerMasterId == null) return null;
        return (root, q, cb) -> cb.equal(root.get("consumerMasterId"), consumerMasterId);
    }

    static Specification<Complaint> slaBreachedEq(Boolean breached) {
        if (breached == null) return null;
        return (root, q, cb) -> cb.equal(root.get("slaBreached"), breached);
    }

    static Specification<Complaint> createdFrom(Instant from) {
        if (from == null) return null;
        return (root, q, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), from);
    }

    static Specification<Complaint> createdTo(Instant to) {
        if (to == null) return null;
        return (root, q, cb) -> cb.lessThanOrEqualTo(root.get("createdAt"), to);
    }

    /**
     * Case-insensitive substring search over {@code ticket_no} and {@code description}. Used by
     * the FE list page's quick-search box. Postgres {@code LIKE} is good enough for v1 — the
     * description column is short and the table is moderate-sized; if we outgrow it the
     * upgrade path is a {@code tsvector} GIN index, not a different framework.
     */
    static Specification<Complaint> textSearch(String q) {
        if (q == null || q.isBlank()) return null;
        String pattern = "%" + q.toLowerCase() + "%";
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("ticketNo")), pattern),
                cb.like(cb.lower(root.get("description")), pattern)
        );
    }
}

