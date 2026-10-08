package com.sliit.eventsphere.service;

import com.sliit.eventsphere.dto.DiscountRequest;
import com.sliit.eventsphere.dto.DiscountResponse;
import com.sliit.eventsphere.entity.Discount;
import com.sliit.eventsphere.entity.DiscountType;
import com.sliit.eventsphere.entity.Event;
import com.sliit.eventsphere.exception.ConflictException;
import com.sliit.eventsphere.exception.InvalidRequestException;
import com.sliit.eventsphere.exception.ResourceNotFoundException;
import com.sliit.eventsphere.repository.DiscountRepository;
import com.sliit.eventsphere.repository.EventRepository;
import com.sliit.eventsphere.repository.PaymentRepository;
import com.sliit.eventsphere.security.ActingUser;
import com.sliit.eventsphere.security.EventAccessPolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Service
public class DiscountManagementServiceImpl implements DiscountManagementService {

    private static final BigDecimal MIN_PERCENT = new BigDecimal("0.01");
    private static final BigDecimal MAX_PERCENT = new BigDecimal("100.00");
    private static final int MAX_CODE_LENGTH = 40;

    private final DiscountRepository discountRepository;
    private final EventRepository eventRepository;
    private final PaymentRepository paymentRepository;
    private final EventAccessPolicy accessPolicy;
    private final AuditLogService auditLogService;

    public DiscountManagementServiceImpl(DiscountRepository discountRepository,
                                          EventRepository eventRepository,
                                          PaymentRepository paymentRepository,
                                          EventAccessPolicy accessPolicy,
                                          AuditLogService auditLogService) {
        this.discountRepository = discountRepository;
        this.eventRepository = eventRepository;
        this.paymentRepository = paymentRepository;
        this.accessPolicy = accessPolicy;
        this.auditLogService = auditLogService;
    }

    @Override
    @Transactional(readOnly = true)
    public List<DiscountResponse> getDiscountsForEvent(Long eventId, ActingUser actor) {
        Event event = requireEvent(eventId);
        accessPolicy.requireManager(event, actor,
                "You are not authorized to manage discounts for this event.");
        return discountRepository.findByEventId(eventId).stream()
                .map(DiscountResponse::from)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<DiscountResponse> getManageableDiscounts(ActingUser actor) {
        // An Administrator sees the platform; an organizer sees only their
        // own events. Filtering by owner in the query rather than loading
        // everything and discarding most of it in Java.
        List<Event> events = actor.isAdmin()
                ? eventRepository.findAll()
                : eventRepository.findByOrganizerId(actor.id());
        if (events.isEmpty()) {
            return List.of();
        }
        // One query for every event's discounts, not one query per event.
        List<Long> eventIds = events.stream().map(Event::getId).toList();
        return discountRepository.findByEventIdInOrderByEventIdAsc(eventIds).stream()
                .map(DiscountResponse::from)
                .toList();
    }

    @Override
    @Transactional
    public DiscountResponse create(Long eventId, DiscountRequest request, ActingUser actor) {
        Event event = requireEvent(eventId);
        accessPolicy.requireManager(event, actor,
                "You are not authorized to manage discounts for this event.");

        validate(request);
        String code = normalizeCode(request);

        // One discount of each kind per event. Two active EARLY_BIRD rows
        // would make "which one applies?" a matter of row order, and the
        // strategies are written to answer that question once.
        if (discountRepository.findByEventId(eventId).stream()
                .anyMatch(d -> d.getType() == request.getType())) {
            throw new ConflictException("This event already has a "
                    + label(request.getType()) + " discount. Edit that one instead.");
        }

        if (code != null) {
            requireCodeIsFree(eventId, code, null);
        }

        Discount discount = new Discount();
        discount.setEvent(event);
        apply(discount, request, code);
        Discount saved = discountRepository.save(discount);

        auditLogService.log(event.getOrganizer(), "CREATE_DISCOUNT", "Discount", saved.getId(),
                label(saved.getType()) + " " + saved.getPercentOff() + "% on event " + eventId);
        return DiscountResponse.from(saved);
    }

    @Override
    @Transactional
    public DiscountResponse update(Long discountId, DiscountRequest request, ActingUser actor) {
        Discount discount = requireDiscount(discountId);
        accessPolicy.requireManager(discount.getEvent(), actor,
                "You are not authorized to manage discounts for this event.");

        validate(request);
        String code = normalizeCode(request);

        // The TYPE is fixed once created. Changing an EARLY_BIRD into a
        // PROMO_CODE would silently change which strategy runs for
        // payments that already reference this row, and the price those
        // payments recorded would no longer be explainable.
        if (request.getType() != discount.getType()) {
            throw new InvalidRequestException(
                    "A discount's type cannot be changed after it is created. "
                            + "Deactivate this one and create a new discount instead.");
        }

        if (code != null) {
            requireCodeIsFree(discount.getEvent().getId(), code, discountId);
        }

        apply(discount, request, code);
        Discount saved = discountRepository.save(discount);

        auditLogService.log(discount.getEvent().getOrganizer(), "UPDATE_DISCOUNT",
                "Discount", saved.getId(),
                label(saved.getType()) + " now " + saved.getPercentOff() + "%, active="
                        + saved.isActive());
        return DiscountResponse.from(saved);
    }

    @Override
    @Transactional
    public DiscountResponse setActive(Long discountId, boolean active, ActingUser actor) {
        Discount discount = requireDiscount(discountId);
        accessPolicy.requireManager(discount.getEvent(), actor,
                "You are not authorized to manage discounts for this event.");

        discount.setActive(active);
        Discount saved = discountRepository.save(discount);

        auditLogService.log(discount.getEvent().getOrganizer(),
                active ? "ACTIVATE_DISCOUNT" : "DEACTIVATE_DISCOUNT",
                "Discount", saved.getId(),
                label(saved.getType()) + " on event " + saved.getEvent().getId());
        return DiscountResponse.from(saved);
    }

    @Override
    @Transactional
    public void delete(Long discountId, ActingUser actor) {
        Discount discount = requireDiscount(discountId);
        accessPolicy.requireManager(discount.getEvent(), actor,
                "You are not authorized to manage discounts for this event.");

        // payments.discount_id is ON DELETE SET NULL, so deleting a used
        // discount does not fail - it quietly erases the reason a past
        // payment was cheaper than the event fee. Refusing here is the
        // difference between a constraint the database can enforce and a
        // rule about what the data means.
        if (paymentRepository.existsByDiscountId(discountId)) {
            throw new ConflictException(
                    "This discount has already been applied to a payment, so deleting it "
                            + "would erase why that payment was discounted. "
                            + "Deactivate it instead - it will stop being offered immediately.");
        }

        Long eventId = discount.getEvent().getId();
        DiscountType type = discount.getType();
        discountRepository.delete(discount);

        auditLogService.log(discount.getEvent().getOrganizer(), "DELETE_DISCOUNT",
                "Discount", discountId, label(type) + " removed from event " + eventId);
    }

    // =================================================================
    // Validation - repeated here rather than trusted to the DTO
    // annotations, because the Thymeleaf form never passes through them.
    // =================================================================

    private void validate(DiscountRequest request) {
        if (request.getType() == null) {
            throw new InvalidRequestException("A discount type is required.");
        }

        BigDecimal percent = request.getPercentOff();
        if (percent == null) {
            throw new InvalidRequestException("A discount percentage is required.");
        }
        if (percent.compareTo(MIN_PERCENT) < 0 || percent.compareTo(MAX_PERCENT) > 0) {
            throw new InvalidRequestException(
                    "A discount must be greater than 0% and at most 100%.");
        }

        if (request.getType() == DiscountType.PROMO_CODE) {
            String code = request.getCode();
            if (code == null || code.isBlank()) {
                throw new InvalidRequestException(
                        "A promo code discount needs a code for attendees to enter.");
            }
            if (code.trim().length() > MAX_CODE_LENGTH) {
                throw new InvalidRequestException(
                        "A promo code must be " + MAX_CODE_LENGTH + " characters or fewer.");
            }
        }

        if (request.getValidFrom() != null && request.getValidTo() != null
                && !request.getValidTo().isAfter(request.getValidFrom())) {
            throw new InvalidRequestException(
                    "The discount must stop being valid after it starts, not before.");
        }
    }

    /**
     * Promo codes are stored and compared upper-case and trimmed, so
     * "summer24", " SUMMER24 " and "Summer24" are one code rather than
     * three - an attendee typing it off a poster should not have to match
     * the organizer's capitalisation.
     *
     * Every other type has no code at all; any value submitted is dropped
     * rather than stored where it would never be read.
     */
    private String normalizeCode(DiscountRequest request) {
        if (request.getType() != DiscountType.PROMO_CODE) {
            return null;
        }
        return request.getCode().trim().toUpperCase();
    }

    private void requireCodeIsFree(Long eventId, String code, Long selfId) {
        Optional<Discount> existing = discountRepository.findByEventIdAndCode(eventId, code);
        if (existing.isPresent() && !existing.get().getId().equals(selfId)) {
            // uq_discount_code (event_id, code) is the backstop; this is
            // the readable error instead of a constraint violation.
            throw new ConflictException(
                    "The code \"" + code + "\" is already used by another discount on this event.");
        }
    }

    private void apply(Discount discount, DiscountRequest request, String code) {
        discount.setType(request.getType());
        discount.setCode(code);
        discount.setPercentOff(request.getPercentOff());
        discount.setValidFrom(request.getValidFrom());
        discount.setValidTo(request.getValidTo());
        discount.setActive(request.isActive());
    }

    private Event requireEvent(Long eventId) {
        return eventRepository.findById(eventId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Event not found with id " + eventId));
    }

    private Discount requireDiscount(Long discountId) {
        return discountRepository.findById(discountId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Discount not found with id " + discountId));
    }

    private String label(DiscountType type) {
        return switch (type) {
            case EARLY_BIRD -> "Early bird";
            case STUDENT -> "Student";
            case PROMO_CODE -> "Promo code";
            case GROUP -> "Group";
        };
    }
}
