package com.sliit.eventsphere.repository;

import com.sliit.eventsphere.entity.Discount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DiscountRepository extends JpaRepository<Discount, Long> {

    List<Discount> findByEventId(Long eventId);

    Optional<Discount> findByEventIdAndCode(Long eventId, String code);

    /**
     * Every discount across a set of events, in one query.
     *
     * The discount management screen lists one organizer's whole
     * portfolio. Calling findByEventId once per event is the N+1 select
     * problem - an organizer with 20 events would issue 21 queries to
     * render one page.
     */
    List<Discount> findByEventIdInOrderByEventIdAsc(Collection<Long> eventIds);
}
