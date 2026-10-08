package com.sliit.eventsphere.repository;

import com.sliit.eventsphere.dto.EventSearchCriteria;
import com.sliit.eventsphere.entity.Event;
import com.sliit.eventsphere.entity.EventStatus;
import com.sliit.eventsphere.entity.EventType;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.invocation.Invocation;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.*;

/**
 * The filter query for the event listing page.
 *
 * The rule worth pinning down is the one that is easy to get wrong and
 * invisible when it breaks: a filter the visitor left blank must
 * contribute no SQL at all. If an empty text box became
 * `venue like '%%'` the page would still look right, and a filter for the
 * empty string would quietly exclude every event with a null venue.
 *
 * These tests therefore assert on which CriteriaBuilder calls happen, not
 * on the resulting SQL - that is the layer at which "this filter was
 * applied" is actually decided.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EventSpecificationsTest {

    @Mock(answer = Answers.RETURNS_DEEP_STUBS) private Root<Event> root;
    @Mock private CriteriaQuery<?> query;
    @Mock private CriteriaBuilder cb;

    /**
     * A bare @Mock CriteriaBuilder returns null from every builder call,
     * so cb.lower(...) fed null into cb.like(...), and cb.like(...) fed
     * null into cb.or(...). Matchers written as any(Predicate.class) do
     * an instanceof check and therefore do NOT match null, which made
     * two tests here fail for a reason that had nothing to do with the
     * code under test. Returning distinct mock values makes the builder
     * behave the way a real one does - each call produces something the
     * next call can use.
     */
    @BeforeEach
    void criteriaBuilderReturnsRealValues() {
        lenient().when(cb.lower(any())).thenAnswer(i -> mock(Expression.class));
        lenient().when(cb.like(any(), anyString())).thenAnswer(i -> mock(Predicate.class));
        lenient().when(cb.equal(any(), any())).thenAnswer(i -> mock(Predicate.class));
        lenient().when(cb.or(any(Predicate.class), any(Predicate.class)))
                 .thenAnswer(i -> mock(Predicate.class));
        lenient().when(cb.greaterThanOrEqualTo(any(), any(LocalDateTime.class)))
                 .thenAnswer(i -> mock(Predicate.class));
        lenient().when(cb.lessThanOrEqualTo(any(), any(LocalDateTime.class)))
                 .thenAnswer(i -> mock(Predicate.class));
        lenient().when(cb.lessThanOrEqualTo(any(), any(BigDecimal.class)))
                 .thenAnswer(i -> mock(Predicate.class));
        lenient().when(cb.lessThan(any(), any(Expression.class)))
                 .thenAnswer(i -> mock(Predicate.class));
    }

    /**
     * Reads the predicates that reached {@code cb.and(...)}.
     *
     * This used to be a plain {@code verify(cb).and(captor.capture())}.
     * CriteriaBuilder.and takes varargs, and one capture matcher only
     * matches a ONE-element varargs call - so the moment more than one
     * filter was applied the verification failed with "argument(s) are
     * different", and every multi-filter test in this class failed. Only
     * the empty-criteria case, which produces exactly one predicate, ever
     * passed. The suite had never compiled, so this had never been seen.
     *
     * Reading the recorded invocation directly sidesteps varargs matching
     * and works for any number of predicates.
     */
    private List<Predicate> applyFilters(EventSearchCriteria criteria) {
        EventSpecifications.matching(EventStatus.PUBLISHED, criteria)
                .toPredicate(root, query, cb);

        for (Invocation invocation : Mockito.mockingDetails(cb).getInvocations()) {
            if (!invocation.getMethod().getName().equals("and")) {
                continue;
            }
            Object[] raw = invocation.getRawArguments();
            Object last = raw.length == 0 ? null : raw[raw.length - 1];
            if (last instanceof Predicate[] array) {
                return Arrays.asList(array);
            }
            return Arrays.stream(raw)
                    .filter(Predicate.class::isInstance)
                    .map(Predicate.class::cast)
                    .toList();
        }
        throw new AssertionError("EventSpecifications never called CriteriaBuilder.and(...)");
    }

    @Test
    void emptyCriteriaFiltersOnStatusAndNothingElse() {
        List<Predicate> predicates = applyFilters(new EventSearchCriteria());

        assertEquals(1, predicates.size(), "only the PUBLISHED status predicate");
        verify(cb).equal(any(), eq(EventStatus.PUBLISHED));
        verify(cb, never()).like(any(), anyString());
    }

    @Test
    void blankStringsAreNotFilters() {
        // The listing form submits every input on every search, so most of
        // them arrive as "". Treating that as a filter would match nothing.
        EventSearchCriteria criteria = new EventSearchCriteria();
        criteria.setKeyword("   ");
        criteria.setCategory("");
        criteria.setVenue(" ");

        List<Predicate> predicates = applyFilters(criteria);

        assertEquals(1, predicates.size());
        verify(cb, never()).like(any(), anyString());
    }

    @Test
    void aKeywordSearchesNameAndDescription() {
        EventSearchCriteria criteria = new EventSearchCriteria();
        criteria.setKeyword("  AI  ");

        applyFilters(criteria);

        // Trimmed, lower-cased and wrapped, on both columns.
        verify(cb, times(2)).like(any(), eq("%ai%"));
        verify(cb).or(any(Predicate.class), any(Predicate.class));
    }

    @Test
    void categoryMatchesExactlyButIgnoresCase() {
        EventSearchCriteria criteria = new EventSearchCriteria();
        criteria.setCategory("Workshop");

        applyFilters(criteria);

        // Exact match, not a like: "Workshop" must not also return
        // "Workshop Series".
        verify(cb).equal(any(Expression.class), eq("workshop"));
        verify(cb, never()).like(any(), eq("%workshop%"));
    }

    @Test
    void venueIsAPartialMatch() {
        EventSearchCriteria criteria = new EventSearchCriteria();
        criteria.setVenue("Colombo");

        applyFilters(criteria);

        // A venue is typed from memory, so "Colombo" should find
        // "Colombo City Centre".
        verify(cb).like(any(), eq("%colombo%"));
    }

    @Test
    void aDateRangeCoversBothWholeBoundaryDays() {
        EventSearchCriteria criteria = new EventSearchCriteria();
        criteria.setDateFrom(LocalDate.of(2026, 9, 12));
        criteria.setDateTo(LocalDate.of(2026, 9, 14));

        applyFilters(criteria);

        ArgumentCaptor<LocalDateTime> from = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> to = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(cb).greaterThanOrEqualTo(any(), from.capture());
        verify(cb).lessThanOrEqualTo(any(), to.capture());

        // Midnight at the start, the last instant at the end. Comparing
        // both against atStartOfDay() would hide every event on the 14th.
        assertEquals(LocalDate.of(2026, 9, 12).atStartOfDay(), from.getValue());
        assertEquals(2026, to.getValue().getYear());
        assertEquals(14, to.getValue().getDayOfMonth());
        assertEquals(23, to.getValue().getHour());
    }

    @Test
    void maxFeeIsAnUpperBoundAndFreeEventsAlwaysQualify() {
        EventSearchCriteria criteria = new EventSearchCriteria();
        criteria.setMaxFee(new BigDecimal("5000"));

        applyFilters(criteria);

        verify(cb).lessThanOrEqualTo(any(), isA(BigDecimal.class));
    }

    @Test
    void availabilityComparesSeatsTakenAgainstCapacity() {
        EventSearchCriteria criteria = new EventSearchCriteria();
        criteria.setAvailableOnly(true);

        List<Predicate> predicates = applyFilters(criteria);

        assertEquals(2, predicates.size());
        // Column against column, not against a number - "has a seat left"
        // depends on this event's own capacity.
        verify(cb).lessThan(any(), any(Expression.class));
    }

    @Test
    void availabilityOffAddsNoPredicate() {
        List<Predicate> predicates = applyFilters(new EventSearchCriteria());

        assertEquals(1, predicates.size());
        verify(cb, never()).lessThan(any(), any(Expression.class));
    }

    @Test
    void eventTypeFiltersOnTheEnumItself() {
        EventSearchCriteria criteria = new EventSearchCriteria();
        criteria.setEventType(EventType.PUBLIC);

        applyFilters(criteria);

        verify(cb).equal(any(), eq(EventType.PUBLIC));
    }

    @Test
    void everyFilterAtOnceProducesOnePredicateEach() {
        EventSearchCriteria criteria = new EventSearchCriteria();
        criteria.setKeyword("ai");
        criteria.setCategory("Workshop");
        criteria.setVenue("Colombo");
        criteria.setDateFrom(LocalDate.now());
        criteria.setDateTo(LocalDate.now().plusDays(7));
        criteria.setMaxFee(new BigDecimal("5000"));
        criteria.setEventType(EventType.PUBLIC);
        criteria.setAvailableOnly(true);

        List<Predicate> predicates = applyFilters(criteria);

        // status + keyword + category + venue + from + to + fee + type + availability
        assertEquals(9, predicates.size());
    }

}
