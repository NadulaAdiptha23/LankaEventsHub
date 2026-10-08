package com.sliit.eventsphere.controller;

import com.sliit.eventsphere.dto.PaymentRequest;
import com.sliit.eventsphere.dto.PaymentResponse;
import com.sliit.eventsphere.entity.PaymentStatus;
import com.sliit.eventsphere.dto.PriceQuoteRequest;
import com.sliit.eventsphere.dto.PriceQuoteResponse;
import com.sliit.eventsphere.dto.RegistrationResponse;
import com.sliit.eventsphere.exception.InvalidRequestException;
import com.sliit.eventsphere.payment.MockPaymentGateway;
import com.sliit.eventsphere.security.ActingUser;
import com.sliit.eventsphere.security.UserPrincipal;
import com.sliit.eventsphere.service.EventService;
import com.sliit.eventsphere.service.PaymentService;
import com.sliit.eventsphere.service.RegistrationService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Server-rendered pages for Function 3 (Payment & Ticketing). Sits on top
 * of the same PaymentService the REST API uses.
 *
 * Every entry point starts by resolving the registration through
 * RegistrationService.requireOwnRegistration, so opening
 * /registrations/57/pay or /registrations/57/ticket with somebody else's
 * id returns 403 instead of their checkout page or their ticket. Hiding
 * the links in the HTML was never the control - this is.
 */
@Controller
public class PaymentPageController {

    private final PaymentService paymentService;
    private final RegistrationService registrationService;
    /** Only to name the event on the door list. */
    private final EventService eventService;

    public PaymentPageController(PaymentService paymentService,
                                  RegistrationService registrationService,
                                  EventService eventService) {
        this.paymentService = paymentService;
        this.registrationService = registrationService;
        this.eventService = eventService;
    }

    @GetMapping("/registrations/{id:[0-9]+}/pay")
    public String payForm(@PathVariable Long id,
                           @AuthenticationPrincipal UserPrincipal principal,
                           Model model) {
        ActingUser actor = ActingUser.of(principal);
        registrationService.requireOwnRegistration(id, actor);

        // Already paid? Send them to the ticket rather than showing a
        // checkout the server would refuse.
        //
        // COMPLETED only. A failed attempt also leaves a payment row, and
        // redirecting on "a row exists" sent somebody whose card was
        // declined to a ticket page instead of back to the checkout -
        // exactly the opposite of what they needed.
        if (paymentService.findByRegistrationId(id, actor)
                .filter(p -> p.getStatus() == PaymentStatus.COMPLETED)
                .isPresent()) {
            return "redirect:/registrations/" + id + "/ticket";
        }

        populatePaymentModel(model, id, actor);
        return "payment-form";
    }

    /**
     * The card fields arrive here and go no further than the gateway
     * call: they are not stored, not logged, and not put back on the page
     * when something goes wrong. Re-populating a card number into the
     * HTML after an error would put it in the browser's history and in
     * any proxy between here and there, for the sake of saving somebody
     * eight keystrokes.
     */
    @PostMapping("/registrations/{id:[0-9]+}/pay")
    public String submitPayment(@PathVariable Long id,
                                 @RequestParam(required = false) Long discountId,
                                 @RequestParam(required = false) String enteredCode,
                                 @RequestParam(required = false) String method,
                                 @RequestParam(required = false) String cardNumber,
                                 @RequestParam(required = false) String cardHolder,
                                 @RequestParam(required = false) Integer expiryMonth,
                                 @RequestParam(required = false) Integer expiryYear,
                                 @RequestParam(required = false) String securityCode,
                                 @AuthenticationPrincipal UserPrincipal principal,
                                 Model model) {
        ActingUser actor = ActingUser.of(principal);
        RegistrationResponse registration = registrationService.getRegistrationById(id, actor);

        PaymentRequest request = new PaymentRequest();
        request.setRegistrationId(id);
        request.setEventId(registration.getEventId());
        request.setDiscountId(discountId);
        request.setEnteredCode(enteredCode);
        request.setMethod(method);
        request.setCardNumber(cardNumber);
        request.setCardHolder(cardHolder);
        request.setExpiryMonth(expiryMonth);
        request.setExpiryYear(expiryYear);
        request.setSecurityCode(securityCode);

        try {
            // The amount is recalculated server-side inside pay(). Whatever
            // the page's live preview showed is irrelevant to what is charged.
            paymentService.pay(request, actor);
            return "redirect:/registrations/" + id + "/ticket";
        } catch (InvalidRequestException ex) {
            populatePaymentModel(model, id, actor);
            model.addAttribute("error", ex.getMessage());
            // Keep what they chose so the form does not reset on an error.
            // The card fields are deliberately NOT among these.
            model.addAttribute("selectedDiscountId", discountId);
            model.addAttribute("enteredCode", enteredCode);
            model.addAttribute("selectedMethod", method);
            model.addAttribute("cardHolder", cardHolder);
            return "payment-form";
        }
    }

    /**
     * Live price preview for the checkout page.
     *
     * This deliberately lives on the session-authenticated page chain
     * rather than under /api/**: the browser is already authenticated by
     * cookie here, so the call is CSRF-protected like every other
     * state-changing page request. Routing it through the stateless /api
     * chain would have meant either disabling CSRF for cookie-authenticated
     * requests or leaving the endpoint anonymous.
     */
    @PostMapping("/registrations/{id:[0-9]+}/quote")
    @ResponseBody
    public PriceQuoteResponse quote(@PathVariable Long id,
                                     @Valid @RequestBody PriceQuoteRequest request,
                                     @AuthenticationPrincipal UserPrincipal principal) {
        return paymentService.quoteForRegistration(id, request, ActingUser.of(principal));
    }

    @GetMapping("/registrations/{id:[0-9]+}/ticket")
    public String viewTicket(@PathVariable Long id,
                              @AuthenticationPrincipal UserPrincipal principal,
                              Model model) {
        ActingUser actor = ActingUser.of(principal);
        RegistrationResponse registration = registrationService.getRegistrationById(id, actor);
        // Owner-only: the organizer may see the attendee roster, never
        // somebody else's ticket or payment record.
        PaymentResponse payment = paymentService.getByRegistrationId(id, actor);

        // A ticket is proof of payment, so a declined or abandoned card
        // attempt goes back to the checkout rather than being handed
        // something that looks admissible at a door and is not.
        //
        // A booking being settled ON ARRIVAL is the one case where a code
        // exists before the money does, and it belongs here rather than at
        // the checkout: the attendee needs something to present and the
        // door needs something to look up. The page renders it as a
        // reservation with the amount still owed, in as many words - see
        // the unpaid branch in ticket.html.
        boolean reservedForTheDoor = payment.isOwedAtTheDoor();
        if ((payment.getStatus() != PaymentStatus.COMPLETED && !reservedForTheDoor)
                || payment.getTicketCode() == null
                || payment.getTicketCode().isBlank()) {
            return "redirect:/registrations/" + id + "/pay";
        }

        model.addAttribute("registration", registration);
        model.addAttribute("payment", payment);
        return "ticket";
    }

    // =================================================================
    // The door: taking money from people who said they would pay on
    // arrival
    // =================================================================

    /**
     * The collection list for one event. Authorized inside the service to
     * the owning organizer and administrators - it says who owes what,
     * which is not an attendee's business.
     */
    @GetMapping("/events/{id:[0-9]+}/door")
    public String doorList(@PathVariable Long id,
                            @AuthenticationPrincipal UserPrincipal principal,
                            Model model) {
        ActingUser actor = ActingUser.of(principal);
        model.addAttribute("event", eventService.getEventById(id));
        model.addAttribute("owed", paymentService.getOwedAtTheDoor(id, actor));
        return "event-door";
    }

    @PostMapping("/events/{eventId:[0-9]+}/door/{registrationId:[0-9]+}/settle")
    public String settleAtTheDoor(@PathVariable Long eventId,
                                   @PathVariable Long registrationId,
                                   @RequestParam(required = false) String note,
                                   @AuthenticationPrincipal UserPrincipal principal,
                                   RedirectAttributes flash) {
        try {
            PaymentResponse settled = paymentService.settleOnArrival(
                    registrationId, note, ActingUser.of(principal));
            flash.addFlashAttribute("flashSuccess",
                    settled.getFinalAmount() + " received"
                            + (settled.getAttendeeName() == null
                                    ? "" : " from " + settled.getAttendeeName())
                            + " - the booking is confirmed.");
        } catch (InvalidRequestException ex) {
            flash.addFlashAttribute("flashError", ex.getMessage());
        }
        return "redirect:/events/" + eventId + "/door";
    }

    private void populatePaymentModel(Model model, Long registrationId, ActingUser actor) {
        RegistrationResponse registration = registrationService.getRegistrationById(registrationId, actor);
        // Only discounts that are usable right now are offered - showing an
        // expired one and then rejecting it is a worse experience than not
        // showing it at all. The filtering, and the "must belong to this
        // registration's event" rule, live in the service; this controller
        // injects no repository at all.
        model.addAttribute("registration", registration);
        model.addAttribute("discounts", paymentService.getSelectableDiscounts(registrationId, actor));
        // The test cards, read from the gateway itself rather than
        // retyped into the template. A card that approves in this list
        // and declines in the code would be worse than no list.
        model.addAttribute("approvingCard", MockPaymentGateway.APPROVES);
        model.addAttribute("decliningCards", MockPaymentGateway.declinedCards());
    }
}
