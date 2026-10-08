package com.sliit.eventsphere.service;

import com.sliit.eventsphere.dto.DiscountRequest;
import com.sliit.eventsphere.entity.*;
import com.sliit.eventsphere.exception.ConflictException;
import com.sliit.eventsphere.exception.InvalidRequestException;
import com.sliit.eventsphere.repository.DiscountRepository;
import com.sliit.eventsphere.repository.EventRepository;
import com.sliit.eventsphere.repository.PaymentRepository;
import com.sliit.eventsphere.security.ActingUser;
import com.sliit.eventsphere.security.EventAccessPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Discounts could previously only be created by writing SQL into the
 * table, which meant the four discount strategies had no supported way of
 * ever being exercised on a new event. These cover the rules that came
 * with giving them a real management path.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DiscountManagementServiceImplTest {

    @Mock private DiscountRepository discountRepository;
    @Mock private EventRepository eventRepository;
    @Mock private PaymentRepository paymentRepository;
    @Mock private AuditLogService auditLogService;
    @Spy private EventAccessPolicy accessPolicy = new EventAccessPolicy(org.mockito.Mockito.mock(
            com.sliit.eventsphere.repository.RegistrationRepository.class));

    @InjectMocks private DiscountManagementServiceImpl service;

    private User organizer;
    private ActingUser organizerActor;
    private ActingUser otherOrganizerActor;
    private ActingUser adminActor;
    private Event event;

    @BeforeEach
    void setUp() {
        organizer = new User("Kasun", "organizer@test.com", "hash", UserRole.ORGANIZER);
        organizer.setId(1L);
        organizerActor = ActingUser.of(organizer);
        otherOrganizerActor = new ActingUser(77L, UserRole.ORGANIZER);
        adminActor = new ActingUser(99L, UserRole.ADMIN);

        event = new Event();
        event.setId(100L);
        event.setName("Photography Masterclass");
        event.setOrganizer(organizer);
        event.setStatus(EventStatus.PUBLISHED);

        lenient().when(eventRepository.findById(100L)).thenReturn(Optional.of(event));
        lenient().when(discountRepository.findByEventId(100L)).thenReturn(List.of());
        lenient().when(discountRepository.save(any(Discount.class))).thenAnswer(inv -> {
            Discount d = inv.getArgument(0);
            if (d.getId() == null) d.setId(500L);
            return d;
        });
    }

    // ---------------- authorization ----------------

    @Test
    void anotherOrganizerCannotAddADiscountToSomebodyElsesEvent() {
        assertThrows(AccessDeniedException.class,
                () -> service.create(100L, request(DiscountType.GROUP, null, "10"),
                        otherOrganizerActor));
        verify(discountRepository, never()).save(any(Discount.class));
    }

    @Test
    void anAdministratorMayManageAnyEventsDiscounts() {
        assertDoesNotThrow(() -> service.create(100L,
                request(DiscountType.GROUP, null, "10"), adminActor));
    }

    // ---------------- validation ----------------

    @Test
    void aPercentageAboveOneHundredIsRefused() {
        InvalidRequestException ex = assertThrows(InvalidRequestException.class,
                () -> service.create(100L, request(DiscountType.GROUP, null, "150"),
                        organizerActor));
        assertTrue(ex.getMessage().contains("100%"));
    }

    @Test
    void aZeroPercentDiscountIsRefused() {
        assertThrows(InvalidRequestException.class,
                () -> service.create(100L, request(DiscountType.GROUP, null, "0"),
                        organizerActor));
    }

    @Test
    void aPromoCodeDiscountWithoutACodeIsRefused() {
        InvalidRequestException ex = assertThrows(InvalidRequestException.class,
                () -> service.create(100L, request(DiscountType.PROMO_CODE, null, "10"),
                        organizerActor));
        assertTrue(ex.getMessage().toLowerCase().contains("code"));
    }

    @Test
    void aValidityWindowThatEndsBeforeItStartsIsRefused() {
        DiscountRequest r = request(DiscountType.EARLY_BIRD, null, "10");
        r.setValidFrom(LocalDateTime.of(2026, 10, 10, 9, 0));
        r.setValidTo(LocalDateTime.of(2026, 10, 1, 9, 0));
        assertThrows(InvalidRequestException.class,
                () -> service.create(100L, r, organizerActor));
    }

    // ---------------- behaviour ----------------

    @Test
    void aPromoCodeIsStoredTrimmedAndUpperCased() {
        // An attendee typing a code off a poster should not have to match
        // the organizer's capitalisation.
        service.create(100L, request(DiscountType.PROMO_CODE, "  spring25 ", "15"),
                organizerActor);

        ArgumentCaptor<Discount> captor = ArgumentCaptor.forClass(Discount.class);
        verify(discountRepository).save(captor.capture());
        assertEquals("SPRING25", captor.getValue().getCode());
    }

    @Test
    void aCodeIsOnlyStoredForAPromoCodeDiscount() {
        service.create(100L, request(DiscountType.STUDENT, "IGNORED", "10"), organizerActor);

        ArgumentCaptor<Discount> captor = ArgumentCaptor.forClass(Discount.class);
        verify(discountRepository).save(captor.capture());
        assertNull(captor.getValue().getCode(),
                "a code on a non-promo discount would never be read");
    }

    @Test
    void anEventCannotHaveTwoDiscountsOfTheSameType() {
        // Otherwise "which one applies?" becomes a question about row order.
        Discount existing = discount(DiscountType.EARLY_BIRD);
        when(discountRepository.findByEventId(100L)).thenReturn(List.of(existing));

        assertThrows(ConflictException.class,
                () -> service.create(100L, request(DiscountType.EARLY_BIRD, null, "20"),
                        organizerActor));
    }

    @Test
    void aDiscountsTypeCannotBeChangedAfterCreation() {
        // Changing it would silently change which strategy produced the
        // price a completed payment already recorded.
        Discount existing = discount(DiscountType.EARLY_BIRD);
        when(discountRepository.findById(500L)).thenReturn(Optional.of(existing));

        assertThrows(InvalidRequestException.class,
                () -> service.update(500L, request(DiscountType.GROUP, null, "10"),
                        organizerActor));
    }

    @Test
    void aDiscountAlreadyUsedByAPaymentCannotBeDeleted() {
        // ON DELETE SET NULL means the database would allow it and quietly
        // erase why that payment was cheaper.
        Discount existing = discount(DiscountType.EARLY_BIRD);
        when(discountRepository.findById(500L)).thenReturn(Optional.of(existing));
        when(paymentRepository.existsByDiscountId(500L)).thenReturn(true);

        ConflictException ex = assertThrows(ConflictException.class,
                () -> service.delete(500L, organizerActor));
        assertTrue(ex.getMessage().toLowerCase().contains("deactivate"),
                "the error must point at the thing the operator should do instead");
        verify(discountRepository, never()).delete(any(Discount.class));
    }

    @Test
    void anUnusedDiscountCanBeDeleted() {
        Discount existing = discount(DiscountType.EARLY_BIRD);
        when(discountRepository.findById(500L)).thenReturn(Optional.of(existing));
        when(paymentRepository.existsByDiscountId(500L)).thenReturn(false);

        service.delete(500L, organizerActor);
        verify(discountRepository).delete(existing);
    }

    @Test
    void switchingADiscountOffKeepsTheRow() {
        Discount existing = discount(DiscountType.EARLY_BIRD);
        when(discountRepository.findById(500L)).thenReturn(Optional.of(existing));

        service.setActive(500L, false, organizerActor);

        assertFalse(existing.isActive());
        verify(discountRepository, never()).delete(any(Discount.class));
    }

    // ---------------- helpers ----------------

    private DiscountRequest request(DiscountType type, String code, String percent) {
        DiscountRequest r = new DiscountRequest();
        r.setType(type);
        r.setCode(code);
        r.setPercentOff(new BigDecimal(percent));
        r.setActive(true);
        return r;
    }

    private Discount discount(DiscountType type) {
        Discount d = new Discount();
        d.setId(500L);
        d.setEvent(event);
        d.setType(type);
        d.setPercentOff(new BigDecimal("10"));
        d.setActive(true);
        return d;
    }
}
