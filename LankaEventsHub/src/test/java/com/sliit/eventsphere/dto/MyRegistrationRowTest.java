package com.sliit.eventsphere.dto;

import com.sliit.eventsphere.entity.Payment;
import com.sliit.eventsphere.entity.PaymentStatus;
import com.sliit.eventsphere.entity.Registration;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * "Paid" on the My Registrations page.
 *
 * This read-model used to answer {@code payment != null}, which made a
 * DECLINED card render as "Paid" and hid the "Pay now" link from the one
 * person who still needed it. A failed attempt is a row like any other;
 * only its status says whether anybody was actually charged.
 */
class MyRegistrationRowTest {

    private PaymentResponse paymentWith(PaymentStatus status) {
        Registration registration = new Registration();
        registration.setId(1L);
        Payment payment = new Payment();
        payment.setId(500L);
        payment.setRegistration(registration);
        payment.setOriginalAmount(new BigDecimal("2500.00"));
        payment.setDiscountAmount(BigDecimal.ZERO);
        payment.setFinalAmount(new BigDecimal("2500.00"));
        payment.setStatus(status);
        return PaymentResponse.from(payment);
    }

    private MyRegistrationRow rowWith(PaymentStatus status) {
        return new MyRegistrationRow(new RegistrationResponse(),
                status == null ? null : paymentWith(status), true);
    }

    @Test
    void aCompletedPaymentIsPaid() {
        assertTrue(rowWith(PaymentStatus.COMPLETED).isPaid());
    }

    @Test
    void aFailedPaymentIsNotPaid() {
        // The regression this class exists for.
        assertFalse(rowWith(PaymentStatus.FAILED).isPaid(),
                "a declined card must not render as Paid");
    }

    @Test
    void aPendingPaymentIsNotPaid() {
        assertFalse(rowWith(PaymentStatus.PENDING).isPaid());
    }

    @Test
    void noPaymentAtAllIsNotPaid() {
        assertFalse(rowWith(null).isPaid());
    }

    @Test
    void onlyAFailedPaymentReadsAsFailed() {
        assertTrue(rowWith(PaymentStatus.FAILED).isPaymentFailed());
        assertFalse(rowWith(PaymentStatus.COMPLETED).isPaymentFailed());
        assertFalse(rowWith(PaymentStatus.PENDING).isPaymentFailed());
        assertFalse(rowWith(null).isPaymentFailed());
    }

    @Test
    void aDeclinedCardIsNeitherPaidNorSilentlyTreatedAsUnattempted() {
        // The two flags together are what let the page say "Payment
        // failed" instead of the less useful "Payment due".
        MyRegistrationRow declined = rowWith(PaymentStatus.FAILED);

        assertFalse(declined.isPaid());
        assertTrue(declined.isPaymentFailed());
    }
}
