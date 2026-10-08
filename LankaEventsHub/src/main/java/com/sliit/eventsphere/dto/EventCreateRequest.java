package com.sliit.eventsphere.dto;

import com.sliit.eventsphere.entity.EventType;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * What an organizer submits when creating a new event.
 *
 * Note what organizerId is NOT: required. The owning organizer is the
 * authenticated caller. The field is kept only so an ADMIN can create an
 * event on behalf of another organizer; for every other caller
 * EventServiceImpl ignores it, which is what stops
 * {"organizerId": 999} from assigning an event to an arbitrary account.
 *
 * These annotations cover JSON clients. The Thymeleaf form posts plain
 * request parameters and never reaches them, so the same rules are
 * enforced again in EventServiceImpl - the browser is not the last line
 * of defence, and neither is this class.
 */
public class EventCreateRequest {

    /** ADMIN only; ignored for everyone else. */
    @Positive(message = "organizerId must be a positive id")
    private Long organizerId;

    @NotBlank(message = "Event name is required")
    @Size(min = 3, max = 150, message = "Event name must be between 3 and 150 characters")
    private String name;

    @Size(max = 5000, message = "Description must be 5000 characters or fewer")
    private String description;

    @Size(max = 60, message = "Category must be 60 characters or fewer")
    private String category;

    @NotNull(message = "eventType is required (PUBLIC or RESTRICTED)")
    private EventType eventType;

    @Size(max = 150, message = "Venue must be 150 characters or fewer")
    private String venue;

    private LocalDateTime startDatetime;
    private LocalDateTime endDatetime;

    @NotNull(message = "capacity is required")
    @Min(value = 1, message = "capacity must be at least 1")
    private Integer capacity;

    private LocalDateTime registrationOpenDate;
    private LocalDateTime registrationDeadline;

    @DecimalMin(value = "0.0", message = "fee cannot be negative")
    @Digits(integer = 8, fraction = 2, message = "fee can have at most 2 decimal places")
    private BigDecimal fee = BigDecimal.ZERO;

    @Size(max = 150, message = "Contact person must be 150 characters or fewer")
    private String contactPerson;

    @Size(max = 150, message = "Contact details must be 150 characters or fewer")
    private String contactDetails;

    @Size(max = 255, message = "Banner URL must be 255 characters or fewer")
    @Pattern(regexp = "^$|^https?://.+", message = "Banner URL must start with http:// or https://")
    private String bannerUrl;

    @Size(max = 5000, message = "Special instructions must be 5000 characters or fewer")
    private String specialInstructions;

    public Long getOrganizerId() { return organizerId; }
    public void setOrganizerId(Long organizerId) { this.organizerId = organizerId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public EventType getEventType() { return eventType; }
    public void setEventType(EventType eventType) { this.eventType = eventType; }
    public String getVenue() { return venue; }
    public void setVenue(String venue) { this.venue = venue; }
    public LocalDateTime getStartDatetime() { return startDatetime; }
    public void setStartDatetime(LocalDateTime startDatetime) { this.startDatetime = startDatetime; }
    public LocalDateTime getEndDatetime() { return endDatetime; }
    public void setEndDatetime(LocalDateTime endDatetime) { this.endDatetime = endDatetime; }
    public Integer getCapacity() { return capacity; }
    public void setCapacity(Integer capacity) { this.capacity = capacity; }
    public LocalDateTime getRegistrationOpenDate() { return registrationOpenDate; }
    public void setRegistrationOpenDate(LocalDateTime registrationOpenDate) { this.registrationOpenDate = registrationOpenDate; }
    public LocalDateTime getRegistrationDeadline() { return registrationDeadline; }
    public void setRegistrationDeadline(LocalDateTime registrationDeadline) { this.registrationDeadline = registrationDeadline; }
    public BigDecimal getFee() { return fee; }
    public void setFee(BigDecimal fee) { this.fee = fee; }
    public String getContactPerson() { return contactPerson; }
    public void setContactPerson(String contactPerson) { this.contactPerson = contactPerson; }
    public String getContactDetails() { return contactDetails; }
    public void setContactDetails(String contactDetails) { this.contactDetails = contactDetails; }
    public String getBannerUrl() { return bannerUrl; }
    public void setBannerUrl(String bannerUrl) { this.bannerUrl = bannerUrl; }
    public String getSpecialInstructions() { return specialInstructions; }
    public void setSpecialInstructions(String specialInstructions) { this.specialInstructions = specialInstructions; }
}
