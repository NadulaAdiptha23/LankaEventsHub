package com.sliit.eventsphere.dto;

import com.sliit.eventsphere.entity.EventType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Separate from EventCreateRequest on purpose: updateEventDetails() is a
 * partial update (only the fields the caller actually sends get
 * changed - see EventServiceImpl's null-checks), so nothing here can be
 * @NotNull/@NotBlank the way EventCreateRequest's fields are. What IS
 * validated is the shape of any field that's actually provided - e.g.
 * a capacity of 0 or a negative fee is rejected even on a partial
 * update, not just on creation.
 */
public class EventUpdateRequest {

    @Size(min = 3, max = 150, message = "Event name must be between 3 and 150 characters")
    private String name;

    private String description;

    @Size(max = 60, message = "Category must be 60 characters or fewer")
    private String category;

    private EventType eventType;

    @Size(max = 150, message = "Venue must be 150 characters or fewer")
    private String venue;

    private LocalDateTime startDatetime;
    private LocalDateTime endDatetime;

    @Min(value = 1, message = "capacity must be at least 1")
    private Integer capacity;

    private LocalDateTime registrationOpenDate;
    private LocalDateTime registrationDeadline;

    @DecimalMin(value = "0.0", message = "fee cannot be negative")
    @Digits(integer = 8, fraction = 2, message = "fee can have at most 2 decimal places")
    private BigDecimal fee;

    @Size(max = 150, message = "Contact person must be 150 characters or fewer")
    private String contactPerson;

    @Size(max = 150, message = "Contact details must be 150 characters or fewer")
    private String contactDetails;

    @Size(max = 255, message = "Banner URL must be 255 characters or fewer")
    @Pattern(regexp = "^$|^https?://.+", message = "Banner URL must start with http:// or https://")
    private String bannerUrl;

    @Size(max = 5000, message = "Special instructions must be 5000 characters or fewer")
    private String specialInstructions;

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
