package com.sliit.eventsphere.service;

import com.sliit.eventsphere.dto.RefundResponse;
import com.sliit.eventsphere.entity.*;
import com.sliit.eventsphere.events.EventCancelledEvent;
import com.sliit.eventsphere.exception.InvalidRequestException;
import com.sliit.eventsphere.exception.ResourceNotFoundException;
import com.sliit.eventsphere.repository.PaymentRepository;
import com.sliit.eventsphere.repository.RefundRepository;
import com.sliit.eventsphere.security.ActingUser;
import com.sliit.eventsphere.security.EventAccessPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class RefundServiceImpl implements RefundService {

    private static final Logger log = LoggerFactory.getLogger(RefundServiceImpl.class);

    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final NotificationService notificationService;
    private final EventAccessPolicy accessPolicy;

    /** When money is owed back at all - see {@link RefundPolicy}. */
    private final RefundPolicy refundPolicy;

    public RefundServiceImpl(PaymentRepository paymentRepository,
                              RefundRepository refundRepository,
                              NotificationService notificationService,
                              EventAccessPolicy accessPolicy,
                              RefundPolicy refundPolicy) {
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
        this.notificationService = notificationService;
        this.accessPolicy = accessPolicy;
        this.refundPolicy = refundPolicy;
    }

    /**
     * Whether self-cancellation currently refunds. Exposed so the booking
     * pages can warn the attendee about what is actually configured,
     * rather than repeating a hard-coded sentence that might be wrong.
     */
    @Override
    public boolean isRefundOnAttendeeCancellation() {
        return refundPolicy.refundsOnAttendeeCancellation();
    }

    @Override
    @Transactional
    public int createRefundsForCancelledEvent(Event event,
                                               List<EventCancelledEvent.AffectedRegistration> affected) {
        int created = 0;
        for (EventCancelledEvent.AffectedRegistration entry : affected) {
            if (raiseRefundIfPaid(entry.registration(), entry.heldASeat())) {
                created++;
            }
        }
        log.info("Created {} refund(s) for cancelled event {}", created, event.getId());
        return created;
    }

    @Override
    @Transactional
    public boolean createRefundForCancelledRegistration(Registration registration,
                                                         RegistrationStatus previousStatus,
                                                         boolean cancelledByStaff) {
        // Who gave the seat up is the whole question. An Administrator
        // cancelling a booking has taken the place away from somebody who
        // still wanted it, exactly as cancelling the event would - so the
        // money goes back. An attendee who cancels has chosen to give the
        // place up, and under the default policy that forfeits what they
        // paid.
        if (!refundPolicy.refundIsOwed(cancelledByStaff)) {
            log.debug("Registration {} cancelled by the attendee - no refund raised "
                            + "(app.refunds.on-attendee-cancellation is false)",
                    registration.getId());
            return false;
        }

        boolean created = raiseRefundIfPaid(registration,
                previousStatus == RegistrationStatus.CONFIRMED);
        if (created) {
            log.info("Registration {} cancelled by {} - refund raised",
                    registration.getId(), cancelledByStaff ? "staff" : "the attendee");
        }
        return created;
    }

    /**
     * The one copy of the refund rule, shared by both entry points above.
     *
     * Having a single implementation is the point: an event called off by
     * its organizer and a booking cancelled by its attendee must give the
     * same money back under the same conditions. Two separate copies of
     * these five checks would eventually disagree, and the disagreement
     * would be about somebody's money.
     *
     * @param heldASeat whether the registration held a seat (CONFIRMED or
     *                  PENDING_PAYMENT) immediately before it was
     *                  cancelled. Passed in rather than read off the
     *                  entity, which by now says CANCELLED.
     */
    private boolean raiseRefundIfPaid(Registration registration, boolean heldASeat) {
        // A waitlisted place was never charged for - it never reached a
        // payable state at all. A held seat might have been, so it falls
        // through to the payment checks below, which are what actually
        // decide: an unpaid hold has no COMPLETED payment and stops there.
        if (!heldASeat) return false;

        Optional<Payment> maybePayment = paymentRepository.findByRegistrationId(registration.getId());
        if (maybePayment.isEmpty()) return false;   // free event, or never paid

        Payment payment = maybePayment.get();
        // Money only comes back if it actually arrived. A PENDING or
        // FAILED payment took nothing, so there is nothing to return.
        if (payment.getStatus() != PaymentStatus.COMPLETED) return false;
        // A free registration produces a COMPLETED payment of 0.00 -
        // there is nothing to give back, so no refund row is created.
        if (payment.getFinalAmount() == null
                || payment.getFinalAmount().compareTo(BigDecimal.ZERO) <= 0) return false;
        // Idempotency: if this somehow runs twice, the existing refund is
        // left alone. refunds.payment_id is UNIQUE as the backstop.
        if (refundRepository.existsByPaymentId(payment.getId())) return false;

        Refund refund = new Refund();
        refund.setPayment(payment);
        refund.setStatus(RefundStatus.PENDING);
        // Exactly what was actually paid - never more.
        refund.setAmount(payment.getFinalAmount());
        refundRepository.save(refund);
        return true;
    }

    @Override
    @Transactional
    public void advanceRefundStatus(Long refundId, ActingUser actor) {
        // Locked, not findById. Two administrators pressing "Advance" on
        // the same refund at the same moment both read the same status and
        // both wrote the next one, so one refund could send the attendee
        // two "your refund is complete" notifications. The second
        // transaction now blocks here and re-reads what the first
        // committed, which is what makes the switch below a decision
        // rather than a guess.
        Refund refund = refundRepository.findByIdForUpdate(refundId)
                .orElseThrow(() -> new ResourceNotFoundException("Refund not found with id " + refundId));

        Event event = refund.getPayment().getRegistration().getEvent();
        accessPolicy.requireManager(event, actor,
                "You are not authorized to process refunds for this event.");

        RefundStatus next = switch (refund.getStatus()) {
            case PENDING -> RefundStatus.PROCESSED;
            case PROCESSED -> RefundStatus.COMPLETED;
            case COMPLETED -> throw new InvalidRequestException("This refund is already completed");
        };
        refund.setStatus(next);
        if (next == RefundStatus.PROCESSED) {
            refund.setProcessedAt(LocalDateTime.now());
        }
        refundRepository.save(refund);

        if (next == RefundStatus.COMPLETED) {
            User attendee = refund.getPayment().getRegistration().getUser();
            notificationService.notify(attendee, NotificationType.REFUND_PROCESSED,
                    NotificationChannel.IN_APP,
                    "Your refund of " + refund.getAmount() + " for \""
                            + event.getName() + "\" has been completed.");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<RefundResponse> getAllRefunds(ActingUser actor) {
        if (!actor.isAdmin()) {
            throw new AccessDeniedException("Only an Administrator can view the full refund queue.");
        }
        // Mapped to DTOs inside the transaction, so the page never
        // dereferences a lazy association while rendering.
        return refundRepository.findAllByOrderByRequestedAtDesc().stream()
                .map(RefundResponse::from)
                .toList();
    }
}
