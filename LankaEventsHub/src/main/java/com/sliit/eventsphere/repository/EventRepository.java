package com.sliit.eventsphere.repository;

import com.sliit.eventsphere.entity.Event;
import com.sliit.eventsphere.entity.EventStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface EventRepository extends JpaRepository<Event, Long>,
        JpaSpecificationExecutor<Event> {
    List<Event> findByStatus(EventStatus status);
    List<Event> findByOrganizerId(Long organizerId);
    List<Event> findByCategoryIgnoreCase(String category);

    /**
     * Locks the event row for the duration of the transaction. Used by
     * RegistrationServiceImpl to make the "check seats, then take one"
     * sequence atomic - this is what actually prevents two concurrent
     * registrations from both succeeding for the last seat (the
     * "first-completed-wins" rule confirmed with the client).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Event e where e.id = :id")
    Optional<Event> findByIdForUpdate(@Param("id") Long id);

    /**
     * Finds any non-cancelled event at the same venue whose time window
     * overlaps [startDatetime, endDatetime), excluding one event id. Runs
     * as a single indexed database query instead of pulling every event
     * into memory and filtering in Java - matters once there's more than a
     * handful of events in the table. Used by EventServiceImpl's venue
     * double-booking check on BOTH create and update; on update the event
     * being edited is passed as excludeEventId so it does not conflict
     * with itself. Pass -1 (an id that cannot exist) when creating.
     */
    @Query("select e from Event e where e.status <> :excludedStatus "
            + "and e.id <> :excludeEventId "
            + "and lower(e.venue) = lower(:venue) "
            + "and e.startDatetime < :endDatetime and e.endDatetime > :startDatetime")
    List<Event> findOverlappingEvents(@Param("venue") String venue,
                                       @Param("startDatetime") LocalDateTime startDatetime,
                                       @Param("endDatetime") LocalDateTime endDatetime,
                                       @Param("excludedStatus") EventStatus excludedStatus,
                                       @Param("excludeEventId") Long excludeEventId);

    /** Counts events in one status without loading them (Reporting). */
    long countByStatus(EventStatus status);

    /**
     * How many events this organizer still owns that are not finished.
     * Used before demoting or suspending an organizer, so an event is never
     * left without an owner who can still manage it.
     */
    long countByOrganizerIdAndStatusIn(Long organizerId, java.util.Collection<EventStatus> statuses);

    /**
     * Every event this person owns, whatever its status - including the
     * cancelled and completed ones.
     *
     * Deliberately broader than the count above, which asks about LIVE
     * events for a suspension. A permanent delete has to care about all of
     * them: a cancelled event still holds its registrations and its
     * payment history, and events.organizer_id is ON DELETE RESTRICT, so
     * removing the account would fail at the database anyway - after the
     * rest of the purge had already run.
     */
    long countByOrganizerId(Long organizerId);

    /**
     * Category totals computed by the database rather than by streaming
     * every event row into Java - the reporting dashboard only needs the
     * counts, never the rows.
     */
    @Query("select e.category, count(e) from Event e where e.category is not null group by e.category")
    List<Object[]> countGroupedByCategory();

    /**
     * Backs the event list's search/filter form (Minor Function: "Search
     * and filter events by keyword, category, date, or venue").
     *
     * The filters themselves live in EventSpecifications rather than in a
     * @Query here: there are eight of them, all optional, and a JPQL
     * string with eight `:param is null or ...` branches is both
     * unreadable and fragile about binding nulls of non-String types.
     * JpaSpecificationExecutor gives findAll(Specification) - see
     * EventServiceImpl.searchPublishedEvents.
     */

    /** The categories that actually exist on published events, for the filter list. */
    @Query("select distinct e.category from Event e "
            + "where e.status = :status and e.category is not null "
            + "order by e.category")
    List<String> findDistinctCategoriesByStatus(@Param("status") EventStatus status);

    /**
     * The same categories, but ordered by how many published events each
     * one actually holds - which is what the homepage's featured grid
     * needs and the alphabetical list above cannot give it.
     *
     * Category is free text an organizer types, not an enum, so the only
     * honest way to decide what to feature is to ask the data. A grid of
     * hard-coded category names is one organizer's vocabulary away from
     * sending every visitor to an empty listing.
     *
     * Returns [category, count] pairs, busiest first, with the name as
     * the tie-break so the order is stable between requests.
     */
    @Query("select e.category, count(e) from Event e "
            + "where e.status = :status and e.category is not null and e.category <> '' "
            + "group by e.category order by count(e) desc, e.category asc")
    List<Object[]> countPublishedGroupedByCategory(@Param("status") EventStatus status);
}
