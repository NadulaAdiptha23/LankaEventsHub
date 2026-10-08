package com.sliit.eventsphere.dto;

import com.sliit.eventsphere.entity.Event;
import com.sliit.eventsphere.entity.EventStatus;
import com.sliit.eventsphere.entity.EventType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

public class EventResponse {

    private Long id;
    private String organizerName;
    private String name;
    private String description;
    private String category;
    private EventType eventType;
    private EventStatus status;
    private String venue;
    private LocalDateTime startDatetime;
    private LocalDateTime endDatetime;
    private Integer capacity;
    private Integer seatsTaken;
    private Integer seatsAvailable;
    private LocalDateTime registrationOpenDate;
    private LocalDateTime registrationDeadline;
    private BigDecimal fee;
    private String bannerUrl;
    private String contactPerson;
    private String contactDetails;
    private String specialInstructions;
    private List<EventSessionResponse> sessions;

    public static EventResponse from(Event event) {
        EventResponse dto = new EventResponse();
        dto.id = event.getId();
        dto.organizerName = event.getOrganizer() != null ? event.getOrganizer().getFullName() : null;
        dto.name = event.getName();
        dto.description = event.getDescription();
        dto.category = event.getCategory();
        dto.eventType = event.getEventType();
        dto.status = event.getStatus();
        dto.venue = event.getVenue();
        dto.startDatetime = event.getStartDatetime();
        dto.endDatetime = event.getEndDatetime();
        dto.capacity = event.getCapacity();
        dto.seatsTaken = event.getSeatsTaken();
        dto.seatsAvailable = Math.max(0, event.getCapacity() - event.getSeatsTaken());
        dto.registrationOpenDate = event.getRegistrationOpenDate();
        dto.registrationDeadline = event.getRegistrationDeadline();
        dto.fee = event.getFee();
        dto.bannerUrl = event.getBannerUrl();
        dto.contactPerson = event.getContactPerson();
        dto.contactDetails = event.getContactDetails();
        dto.specialInstructions = event.getSpecialInstructions();
        // Safe to access lazily here as long as the caller (EventService)
        // is running inside an open transaction - see @Transactional on
        // EventServiceImpl.getEventById/getAllEvents/getPublishedEvents.
        dto.sessions = event.getSessions() == null ? List.of() :
                event.getSessions().stream().map(EventSessionResponse::from).collect(Collectors.toList());
        return dto;
    }

    // --- Getters (no setters needed - this is a read-only view) ---
    public Long getId() {
        return id;
    }

    public String getOrganizerName() {
        return organizerName;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getCategory() {
        return category;
    }

    public EventType getEventType() {
        return eventType;
    }

    public EventStatus getStatus() {
        return status;
    }

    public String getVenue() {
        return venue;
    }

    public LocalDateTime getStartDatetime() {
        return startDatetime;
    }

    public LocalDateTime getEndDatetime() {
        return endDatetime;
    }

    public Integer getCapacity() {
        return capacity;
    }

    public Integer getSeatsTaken() {
        return seatsTaken;
    }

    public Integer getSeatsAvailable() {
        return seatsAvailable;
    }

    public LocalDateTime getRegistrationOpenDate() {
        return registrationOpenDate;
    }

    public LocalDateTime getRegistrationDeadline() {
        return registrationDeadline;
    }

    public String getContactPerson() {
        return contactPerson;
    }

    public String getContactDetails() {
        return contactDetails;
    }

    public String getSpecialInstructions() {
        return specialInstructions;
    }

    public BigDecimal getFee() {
        return fee;
    }

    public List<EventSessionResponse> getSessions() {
        return sessions;
    }

    public String getBannerUrl() {
        return bannerUrl;
    }
}

