package com.sliit.eventsphere.controller;

import com.sliit.eventsphere.dto.*;
import com.sliit.eventsphere.entity.EventStatus;
import com.sliit.eventsphere.entity.RegistrationStatus;
import com.sliit.eventsphere.entity.EventType;
import com.sliit.eventsphere.exception.InvalidRequestException;
import com.sliit.eventsphere.exception.ResourceNotFoundException;
import com.sliit.eventsphere.security.ActingUser;
import com.sliit.eventsphere.security.UserPrincipal;
import com.sliit.eventsphere.service.EventInvitationService;
import com.sliit.eventsphere.service.EventService;
import com.sliit.eventsphere.service.EventSessionService;
import com.sliit.eventsphere.service.NotificationService;
import com.sliit.eventsphere.service.PaymentService;
import com.sliit.eventsphere.service.RegistrationService;
import com.sliit.eventsphere.service.PublicStatsService;
import com.sliit.eventsphere.service.PurgeService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Server-rendered pages (Thymeleaf), separate from the JSON REST API.
 * Every method here calls the same services the REST controllers use, so
 * there is exactly one source of business logic either way - including
 * one source of authorization, since the services do the ownership checks
 * rather than the templates.
 */
@Controller
public class PageController {

    private final EventService eventService;
    private final RegistrationService registrationService;
    private final EventInvitationService eventInvitationService;
    private final PaymentService paymentService;
    private final NotificationService notificationService;
    private final EventSessionService eventSessionService;
    /** Permanent deletion lives apart from cancellation - see PurgeService. */
    private final PurgeService purgeService;
    /** Real figures for the public homepage; never invented ones. */
    private final PublicStatsService publicStatsService;

    public PageController(EventService eventService,
                           RegistrationService registrationService,
                           EventInvitationService eventInvitationService,
                           PaymentService paymentService,
                           NotificationService notificationService,
                           EventSessionService eventSessionService,
                           PurgeService purgeService,
                           PublicStatsService publicStatsService) {
        this.eventService = eventService;
        this.registrationService = registrationService;
        this.eventInvitationService = eventInvitationService;
        this.paymentService = paymentService;
        this.notificationService = notificationService;
        this.eventSessionService = eventSessionService;
        this.purgeService = purgeService;
        this.publicStatsService = publicStatsService;
    }

    // =================================================================
    // Public browsing
    // =================================================================

    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("events", eventService.getPublishedEvents());
        // Backs the hero search's category filter, same source as the
        // listing page uses, so it can never offer an empty result.
        model.addAttribute("categories", eventService.getPublishedCategories());
        // The featured grid. Five because that is what the layout holds,
        // ordered by how many published events each category really has,
        // so every tile leads somewhere with something behind it.
        model.addAttribute("featured", eventService.getFeaturedCategories(5));
        // Counted from the live tables - see PublicStatsService. The
        // homepage renders the statistics row only when these say there
        // is something true to report.
        model.addAttribute("stats", publicStatsService.get());
        return "home";
    }

    @GetMapping("/login")
    public String login() {
        return "login";
    }

    /**
     * The public event listing and its filter form.
     *
     * Spring binds the query string straight onto EventSearchCriteria, so
     * ?keyword=ai&category=Workshop still works exactly as it did and the
     * newer filters are simply more optional parameters. The criteria
     * object goes back into the model as well, so the form can render
     * itself already filled in rather than resetting on every search.
     */
    @GetMapping("/events")
    public String listEvents(EventSearchCriteria criteria, Model model) {
        model.addAttribute("events", eventService.searchPublishedEvents(criteria));
        model.addAttribute("criteria", criteria);
        // Built from the events that exist, so the filter never offers a
        // category with nothing behind it.
        model.addAttribute("categories", eventService.getPublishedCategories());
        model.addAttribute("eventTypes", EventType.values());
        return "event-list";
    }

    @GetMapping("/events/{id:[0-9]+}")
    public String eventDetail(@PathVariable Long id,
                               @AuthenticationPrincipal UserPrincipal principal,
                               Model model) {
        ActingUser actor = principal == null ? null : ActingUser.of(principal);
        // getEventForViewer, not getEventById: this route is permitAll, so
        // an unapproved draft would otherwise render in full - venue,
        // pricing, special instructions and all - to anybody who guessed
        // its id.
        EventResponse event = eventService.getEventForViewer(id, actor);

        model.addAttribute("event", event);
        // The template renders from this, never from its own guesses - so
        // "Register now" only appears when the server would actually accept it.
        model.addAttribute("availability", registrationService.checkAvailability(id, actor));
        model.addAttribute("canManage", canManage(id, actor));
        return "event-detail";
    }

    // =================================================================
    // Attendee actions
    // =================================================================

    /**
     * The review step between "Register now" and an actual booking - see
     * event-detail.html, which now GETs here with the chosen seat count
     * instead of posting straight to registerForEvent below. Nothing here
     * is authoritative: the price shown is a preview computed the same
     * way registerForEvent's eventual charge will be, and the seat count
     * is re-validated on submit exactly as it always was. This page
     * exists so an attendee sees what they are agreeing to before the
     * booking is made, not to add a security check that was not already
     * there - registerForEvent already re-checks everything server-side.
     */
    @GetMapping("/events/{id:[0-9]+}/register/review")
    public String registerReview(@PathVariable Long id,
                                  @RequestParam(defaultValue = "1") int seats,
                                  @AuthenticationPrincipal UserPrincipal principal,
                                  Model model,
                                  RedirectAttributes flash) {
        if (principal == null) {
            return "redirect:/login";
        }
        ActingUser actor = ActingUser.of(principal);
        EventResponse event = eventService.getEventForViewer(id, actor);
        RegistrationAvailability availability = registrationService.checkAvailability(id, actor);

        if (!availability.isCanRegister()) {
            flash.addFlashAttribute("flashError",
                    availability.getBlockedReason() != null
                            ? availability.getBlockedReason()
                            : "You cannot register for this event right now.");
            return "redirect:/events/" + id;
        }
        int boundedSeats = Math.max(1, Math.min(seats, availability.getMaxSeatsBookable()));

        BigDecimal feePerSeat = event.getFee() == null ? BigDecimal.ZERO : event.getFee();
        BigDecimal subtotal = feePerSeat.multiply(BigDecimal.valueOf(boundedSeats));

        model.addAttribute("event", event);
        model.addAttribute("availability", availability);
        model.addAttribute("seats", boundedSeats);
        model.addAttribute("feePerSeat", feePerSeat);
        model.addAttribute("subtotal", subtotal);
        model.addAttribute("wouldWaitlist", availability.isWouldWaitlist());
        model.addAttribute("attendeeName", principal.getUser().getFullName());
        model.addAttribute("attendeeEmail", principal.getUser().getEmail());
        return "registration-review";
    }

    /**
     * The seat count arrives from a select on the event page, but it is
     * never trusted: the service re-checks the range and the capacity
     * under a lock. Defaulting to 1 keeps the old single-seat form
     * working and means a missing parameter books one seat rather than
     * failing with a binding error.
     */
    @PostMapping("/events/{id:[0-9]+}/register")
    public String registerForEvent(@PathVariable Long id,
                                    @RequestParam(defaultValue = "1") int seats,
                                    @AuthenticationPrincipal UserPrincipal principal,
                                    RedirectAttributes flash) {
        try {
            RegistrationResponse response =
                    registrationService.register(id, seats, ActingUser.of(principal));
            String party = response.getSeats() == 1
                    ? ""
                    : " for " + response.getSeats() + " people";
            flash.addFlashAttribute("flashSuccess",
                    response.getStatus().name().equals("WAITLISTED")
                            ? "That event does not have room for that booking"
                                    + " - you have been added to the waitlist."
                            : "You are registered for this event" + party + ".");
        } catch (InvalidRequestException ex) {
            flash.addFlashAttribute("flashError", ex.getMessage());
        }
        return "redirect:/events/" + id;
    }

    @PostMapping("/events/{id:[0-9]+}/cancel")
    public String cancelRegistration(@PathVariable Long id,
                                      @AuthenticationPrincipal UserPrincipal principal,
                                      RedirectAttributes flash) {
        try {
            registrationService.cancelRegistration(id, ActingUser.of(principal));
            flash.addFlashAttribute("flashSuccess", "Your registration has been cancelled.");
        } catch (InvalidRequestException ex) {
            flash.addFlashAttribute("flashError", ex.getMessage());
        }
        return "redirect:/events/" + id;
    }

    @PostMapping("/events/{id:[0-9]+}/request-invitation")
    public String requestInvitation(@PathVariable Long id,
                                     @AuthenticationPrincipal UserPrincipal principal,
                                     RedirectAttributes flash) {
        try {
            eventInvitationService.requestInvitation(id, ActingUser.of(principal));
            flash.addFlashAttribute("flashSuccess",
                    "Your invitation request has been sent to the organizer.");
        } catch (InvalidRequestException ex) {
            flash.addFlashAttribute("flashError", ex.getMessage());
        }
        return "redirect:/events/" + id;
    }

    @GetMapping("/my-registrations")
    public String myRegistrations(@AuthenticationPrincipal UserPrincipal principal, Model model) {
        ActingUser actor = ActingUser.of(principal);
        List<RegistrationResponse> registrations = registrationService.getRegistrationsForUser(actor);
        List<MyRegistrationRow> rows = registrations.stream().map(r -> {
            PaymentResponse payment = paymentService.findByRegistrationId(r.getId(), actor).orElse(null);
            boolean requiresPayment = r.getEventFee() != null
                    && r.getEventFee().compareTo(BigDecimal.ZERO) > 0;
            return new MyRegistrationRow(r, payment, requiresPayment);
        }).collect(Collectors.toList());
        model.addAttribute("rows", rows);
        model.addAttribute("unreadNotifications", notificationService.countMyUnread(actor));
        return "my-registrations";
    }

    @GetMapping("/notifications")
    public String notifications(@AuthenticationPrincipal UserPrincipal principal, Model model) {
        ActingUser actor = ActingUser.of(principal);
        model.addAttribute("notifications", notificationService.getMyNotifications(actor));
        model.addAttribute("unreadNotifications", notificationService.countMyUnread(actor));
        return "notifications";
    }

    @PostMapping("/notifications/read-all")
    public String markNotificationsRead(@AuthenticationPrincipal UserPrincipal principal) {
        notificationService.markAllRead(ActingUser.of(principal));
        return "redirect:/notifications";
    }

    // =================================================================
    // Organizer: create / edit / manage
    // =================================================================

    @GetMapping("/events/new")
    public String newEventForm(Model model) {
        model.addAttribute("eventTypes", EventType.values());
        return "event-form";
    }

    @PostMapping("/events/new")
    public String submitNewEvent(@AuthenticationPrincipal UserPrincipal principal,
                                  @RequestParam String name,
                                  @RequestParam(required = false) String description,
                                  @RequestParam(required = false) String category,
                                  @RequestParam EventType eventType,
                                  @RequestParam(required = false) String venue,
                                  @RequestParam(required = false)
                                  @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm") LocalDateTime startDatetime,
                                  @RequestParam(required = false)
                                  @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm") LocalDateTime endDatetime,
                                  @RequestParam(required = false)
                                  @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm") LocalDateTime registrationOpenDate,
                                  @RequestParam(required = false)
                                  @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm") LocalDateTime registrationDeadline,
                                  @RequestParam(required = false) Integer capacity,
                                  @RequestParam(required = false) BigDecimal fee,
                                  @RequestParam(required = false) String bannerUrl,
                                  @RequestParam(required = false) String contactPerson,
                                  @RequestParam(required = false) String contactDetails,
                                  @RequestParam(required = false) String specialInstructions,
                                  // "draft" (the default) or "submit". Which
                                  // button was pressed, nothing more - it
                                  // grants no permission and skips no check.
                                  @RequestParam(required = false, defaultValue = "draft") String action,
                                  RedirectAttributes flash,
                                  Model model) {
        EventCreateRequest request = new EventCreateRequest();
        request.setName(name);
        request.setDescription(description);
        request.setCategory(category);
        request.setEventType(eventType);
        request.setVenue(venue);
        request.setStartDatetime(startDatetime);
        request.setEndDatetime(endDatetime);
        request.setRegistrationOpenDate(registrationOpenDate);
        request.setRegistrationDeadline(registrationDeadline);
        request.setCapacity(capacity);
        request.setFee(fee != null ? fee : BigDecimal.ZERO);
        request.setBannerUrl(blankToNull(bannerUrl));
        request.setContactPerson(contactPerson);
        request.setContactDetails(contactDetails);
        request.setSpecialInstructions(specialInstructions);

        try {
            ActingUser actor = ActingUser.of(principal);
            EventResponse created = eventService.createEvent(request, actor);

            // Every event is saved as a draft first. "Save and submit" is
            // the same two steps run back to back, not a different code
            // path - so there is exactly one way an event reaches the
            // approval queue.
            if (!"submit".equals(action)) {
                return "redirect:/my-events?saved";
            }
            try {
                eventService.submitForApproval(created.getId(), actor);
                return "redirect:/my-events?submitted";
            } catch (InvalidRequestException submitFailure) {
                // The draft is saved; only the submission was refused.
                // Saying so is the difference between "your work is safe,
                // here is what is missing" and the organizer assuming
                // they have lost it.
                flash.addFlashAttribute("flashError",
                        "Saved as a draft, but not submitted. " + submitFailure.getMessage());
                return "redirect:/my-events";
            }
        } catch (InvalidRequestException ex) {
            // Re-render with the message AND everything the organizer typed,
            // so a validation failure never costs them the form contents.
            model.addAttribute("error", ex.getMessage());
            model.addAttribute("form", request);
            model.addAttribute("eventTypes", EventType.values());
            return "event-form";
        }
    }

    @GetMapping("/my-events")
    public String myEvents(@AuthenticationPrincipal UserPrincipal principal, Model model) {
        ActingUser actor = ActingUser.of(principal);
        model.addAttribute("events", actor.isAdmin()
                ? eventService.getAllEvents()
                : eventService.getEventsForOrganizer(actor.id()));
        return "my-events";
    }

    @GetMapping("/events/{id:[0-9]+}/edit")
    public String editEventForm(@PathVariable Long id,
                                 @AuthenticationPrincipal UserPrincipal principal,
                                 Model model) {
        ActingUser actor = ActingUser.of(principal);
        eventService.requireEventManager(id, actor); // 403 before anything is rendered
        model.addAttribute("event", eventService.getEventById(id));
        model.addAttribute("eventTypes", EventType.values());
        // The agenda is managed on this page too - sessions had an API
        // from the start but no screen, so nobody could build one.
        model.addAttribute("sessions", eventSessionService.getSessionsForEvent(id, actor));
        return "event-edit";
    }

    @PostMapping("/events/{id:[0-9]+}/edit")
    public String submitEventEdit(@PathVariable Long id,
                                   @AuthenticationPrincipal UserPrincipal principal,
                                   @RequestParam(required = false) String name,
                                   @RequestParam(required = false) String description,
                                   @RequestParam(required = false) String category,
                                   @RequestParam(required = false) EventType eventType,
                                   @RequestParam(required = false) String venue,
                                   @RequestParam(required = false)
                                   @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm") LocalDateTime startDatetime,
                                   @RequestParam(required = false)
                                   @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm") LocalDateTime endDatetime,
                                   @RequestParam(required = false)
                                   @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm") LocalDateTime registrationOpenDate,
                                   @RequestParam(required = false)
                                   @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm") LocalDateTime registrationDeadline,
                                   @RequestParam(required = false) Integer capacity,
                                   @RequestParam(required = false) BigDecimal fee,
                                   @RequestParam(required = false) String bannerUrl,
                                   @RequestParam(required = false) String contactPerson,
                                   @RequestParam(required = false) String contactDetails,
                                   @RequestParam(required = false) String specialInstructions,
                                   Model model,
                                   RedirectAttributes flash) {
        EventUpdateRequest request = new EventUpdateRequest();
        request.setName(blankToNull(name));
        request.setDescription(description);
        request.setCategory(blankToNull(category));
        request.setEventType(eventType);
        request.setVenue(blankToNull(venue));
        request.setStartDatetime(startDatetime);
        request.setEndDatetime(endDatetime);
        request.setRegistrationOpenDate(registrationOpenDate);
        request.setRegistrationDeadline(registrationDeadline);
        request.setCapacity(capacity);
        request.setFee(fee);
        request.setBannerUrl(blankToNull(bannerUrl));
        request.setContactPerson(contactPerson);
        request.setContactDetails(contactDetails);
        request.setSpecialInstructions(specialInstructions);

        try {
            eventService.updateEventDetails(id, request, ActingUser.of(principal));
            flash.addFlashAttribute("flashSuccess", "Event updated.");
            return "redirect:/events/" + id;
        } catch (InvalidRequestException ex) {
            model.addAttribute("error", ex.getMessage());
            model.addAttribute("event", eventService.getEventById(id));
            model.addAttribute("eventTypes", EventType.values());
            return "event-edit";
        }
    }

    /**
     * The organizer says a draft is finished. Ownership and the DRAFT
     * precondition are both checked in the service - this only decides
     * where the browser lands afterwards.
     */
    @PostMapping("/events/{id:[0-9]+}/submit")
    public String submitEventForApproval(@PathVariable Long id,
                                          @AuthenticationPrincipal UserPrincipal principal,
                                          RedirectAttributes flash) {
        try {
            eventService.submitForApproval(id, ActingUser.of(principal));
            flash.addFlashAttribute("flashSuccess",
                    "Submitted for approval. An administrator will review it before it goes live.");
        } catch (InvalidRequestException ex) {
            flash.addFlashAttribute("flashError", ex.getMessage());
        }
        return "redirect:/my-events";
    }

    @PostMapping("/events/{id:[0-9]+}/cancel-event")
    public String cancelEvent(@PathVariable Long id,
                               @AuthenticationPrincipal UserPrincipal principal,
                               RedirectAttributes flash) {
        try {
            // Read the status before the change, so the message can say
            // what actually happened. Discarding a draft notifies nobody
            // and refunds nothing; claiming otherwise would be a lie the
            // organizer could check in one click.
            boolean wasDraft = eventService.getEventById(id).getStatus() == EventStatus.DRAFT;
            eventService.cancelEvent(id, ActingUser.of(principal));
            flash.addFlashAttribute("flashSuccess", wasDraft
                    ? "Draft discarded."
                    : "Event cancelled. Registered attendees have been notified and refunds queued.");
        } catch (InvalidRequestException ex) {
            flash.addFlashAttribute("flashError", ex.getMessage());
        }
        return "redirect:/my-events";
    }

    @PostMapping("/events/{id:[0-9]+}/complete")
    public String completeEvent(@PathVariable Long id,
                                 @AuthenticationPrincipal UserPrincipal principal,
                                 RedirectAttributes flash) {
        try {
            eventService.completeEvent(id, ActingUser.of(principal));
            flash.addFlashAttribute("flashSuccess", "Event marked as completed.");
        } catch (InvalidRequestException ex) {
            flash.addFlashAttribute("flashError", ex.getMessage());
        }
        return "redirect:/my-events";
    }

    /** The attendee list for one event - organizer (own event) or admin. */
    @GetMapping("/events/{id:[0-9]+}/attendees")
    public String eventAttendees(@PathVariable Long id,
                                  @AuthenticationPrincipal UserPrincipal principal,
                                  Model model) {
        ActingUser actor = ActingUser.of(principal);
        List<RegistrationResponse> registrations =
                registrationService.getRegistrationsForEvent(id, actor);

        // Counted here from the list the page is already rendering, rather
        // than with two more queries for numbers we are holding in memory.
        // In PEOPLE, not bookings. One registration can be a party of
        // four, so summing seats is the only way the two figures beside
        // each other - and the rate built from them - mean the same
        // thing. Counting rows would show a sold-out event as a handful
        // of attendees.
        long confirmed = registrations.stream()
                .filter(r -> r.getStatus() == RegistrationStatus.CONFIRMED)
                .mapToLong(RegistrationResponse::getSeats)
                .sum();
        long checkedIn = registrations.stream()
                .filter(RegistrationResponse::isCheckedIn)
                .mapToLong(r -> r.getSeatsCheckedIn() == null ? r.getSeats() : r.getSeatsCheckedIn())
                .sum();

        model.addAttribute("event", eventService.getEventById(id));
        model.addAttribute("registrations", registrations);
        model.addAttribute("confirmedCount", confirmed);
        model.addAttribute("checkedInCount", checkedIn);
        model.addAttribute("attendanceRate",
                confirmed <= 0 ? 0 : Math.round((checkedIn * 100.0) / confirmed));
        return "event-attendees";
    }

    /**
     * Check one attendee in at the door.
     *
     * Ownership and every precondition are enforced in the service; this
     * only turns the outcome into a message and sends the organizer back
     * to the roster they were looking at.
     */
    @PostMapping("/events/{eventId:[0-9]+}/attendees/{id:[0-9]+}/check-in")
    public String checkInAttendee(@PathVariable Long eventId,
                                   @PathVariable Long id,
                                   @RequestParam(required = false) Integer seatsArrived,
                                   @AuthenticationPrincipal UserPrincipal principal,
                                   RedirectAttributes flash) {
        try {
            RegistrationResponse updated =
                    registrationService.checkIn(id, seatsArrived, ActingUser.of(principal));
            Integer arrived = updated.getSeatsCheckedIn();
            flash.addFlashAttribute("flashSuccess",
                    updated.getSeats() == 1
                            ? updated.getUserName() + " checked in."
                            : updated.getUserName() + " checked in - " + arrived
                                    + " of " + updated.getSeats() + " arrived.");
        } catch (InvalidRequestException ex) {
            flash.addFlashAttribute("flashError", ex.getMessage());
        }
        return "redirect:/events/" + eventId + "/attendees";
    }

    @PostMapping("/events/{eventId:[0-9]+}/attendees/{id:[0-9]+}/undo-check-in")
    public String undoCheckInAttendee(@PathVariable Long eventId,
                                       @PathVariable Long id,
                                       @AuthenticationPrincipal UserPrincipal principal,
                                       RedirectAttributes flash) {
        try {
            RegistrationResponse updated =
                    registrationService.undoCheckIn(id, ActingUser.of(principal));
            flash.addFlashAttribute("flashSuccess",
                    "Check-in reversed for " + updated.getUserName() + ".");
        } catch (InvalidRequestException ex) {
            flash.addFlashAttribute("flashError", ex.getMessage());
        }
        return "redirect:/events/" + eventId + "/attendees";
    }

    // =================================================================
    // Organizer/Admin: invitation requests
    // =================================================================

    @GetMapping("/events/{id:[0-9]+}/invitations")
    public String manageInvitations(@PathVariable Long id,
                                     @AuthenticationPrincipal UserPrincipal principal,
                                     Model model) {
        ActingUser actor = ActingUser.of(principal);
        model.addAttribute("eventId", id);
        model.addAttribute("event", eventService.getEventById(id));
        // Throws 403 unless this organizer owns the event.
        model.addAttribute("invitations", eventInvitationService.getInvitationsForEvent(id, actor));
        return "invitations";
    }

    @PostMapping("/events/{eventId:[0-9]+}/invitations/{invitationId:[0-9]+}/approve")
    public String approveInvitation(@PathVariable Long eventId, @PathVariable Long invitationId,
                                     @AuthenticationPrincipal UserPrincipal principal,
                                     RedirectAttributes flash) {
        try {
            eventInvitationService.approve(eventId, invitationId, ActingUser.of(principal));
            flash.addFlashAttribute("flashSuccess", "Invitation approved.");
        } catch (InvalidRequestException ex) {
            flash.addFlashAttribute("flashError", ex.getMessage());
        }
        return "redirect:/events/" + eventId + "/invitations";
    }

    @PostMapping("/events/{eventId:[0-9]+}/invitations/{invitationId:[0-9]+}/reject")
    public String rejectInvitation(@PathVariable Long eventId, @PathVariable Long invitationId,
                                    @AuthenticationPrincipal UserPrincipal principal,
                                    RedirectAttributes flash) {
        try {
            eventInvitationService.reject(eventId, invitationId, ActingUser.of(principal));
            flash.addFlashAttribute("flashSuccess", "Invitation rejected.");
        } catch (InvalidRequestException ex) {
            flash.addFlashAttribute("flashError", ex.getMessage());
        }
        return "redirect:/events/" + eventId + "/invitations";
    }

    /**
     * Bulk approve/reject. The page's "Approve selected" control now
     * actually submits the ticked ids to this endpoint - previously those
     * links were href="#" and did nothing at all.
     */
    @PostMapping("/events/{eventId:[0-9]+}/invitations/bulk")
    public String bulkDecideInvitations(@PathVariable Long eventId,
                                         @RequestParam(name = "invitationIds", required = false)
                                         List<Long> invitationIds,
                                         @RequestParam String decision,
                                         @AuthenticationPrincipal UserPrincipal principal,
                                         RedirectAttributes flash) {
        try {
            int count = eventInvitationService.decideAll(eventId, invitationIds,
                    "approve".equalsIgnoreCase(decision), ActingUser.of(principal));
            flash.addFlashAttribute("flashSuccess", count + " request(s) "
                    + ("approve".equalsIgnoreCase(decision) ? "approved." : "rejected."));
        } catch (InvalidRequestException ex) {
            flash.addFlashAttribute("flashError", ex.getMessage());
        }
        return "redirect:/events/" + eventId + "/invitations";
    }

    // =================================================================
    // Admin: approval dashboard
    // =================================================================

    @GetMapping("/admin/events")
    public String adminEvents(Model model) {
        model.addAttribute("events", eventService.getAllEvents());
        return "events-admin";
    }

    @PostMapping("/admin/events/{id:[0-9]+}/approve")
    public String approveEvent(@PathVariable Long id,
                                @AuthenticationPrincipal UserPrincipal principal,
                                RedirectAttributes flash) {
        try {
            eventService.approveEvent(id, ActingUser.of(principal));
            flash.addFlashAttribute("flashSuccess", "Event approved and published.");
        } catch (InvalidRequestException ex) {
            flash.addFlashAttribute("flashError", ex.getMessage());
        }
        return "redirect:/admin/events";
    }

    /**
     * Permanently deletes an event and everything under it.
     *
     * Administrator only, and enforced in PurgeService rather than here -
     * an organizer must not be able to delete an event that carries other
     * people's bookings and other people's money, even one they own.
     */
    @PostMapping("/admin/events/{id:[0-9]+}/delete")
    public String deleteEvent(@PathVariable Long id,
                               @RequestParam(required = false) String reason,
                               @AuthenticationPrincipal UserPrincipal principal,
                               RedirectAttributes flash) {
        try {
            purgeService.purgeEvent(id, ActingUser.of(principal), reason);
            flash.addFlashAttribute("flashSuccess",
                    "Event permanently deleted. The audit log records what went with it.");
        } catch (InvalidRequestException | ResourceNotFoundException ex) {
            flash.addFlashAttribute("flashError", ex.getMessage());
        }
        return "redirect:/admin/events";
    }

    // =================================================================
    // Helpers
    // =================================================================

    private boolean canManage(Long eventId, ActingUser actor) {
        if (actor == null || actor.isAttendee()) {
            return false;
        }
        try {
            eventService.requireEventManager(eventId, actor);
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
