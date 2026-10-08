package com.sliit.eventsphere.dto;

import com.sliit.eventsphere.entity.DiscountType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * What an organizer submits to create or edit a discount on their event.
 *
 * As with EventCreateRequest, these annotations only cover JSON clients.
 * The Thymeleaf form posts plain request parameters and never reaches
 * them, so every rule here is enforced again inside
 * DiscountManagementServiceImpl. The browser is not the last line of
 * defence and neither is this class.
 */
public class DiscountRequest {

    @NotNull(message = "A discount type is required")
    private DiscountType type;

    /** Required for PROMO_CODE, meaningless for the other three. */
    @Size(max = 40, message = "A promo code must be 40 characters or fewer")
    private String code;

    @NotNull(message = "A percentage is required")
    @DecimalMin(value = "0.01", message = "A discount must be greater than 0%")
    @DecimalMax(value = "100.00", message = "A discount cannot exceed 100%")
    private BigDecimal percentOff;

    private LocalDateTime validFrom;
    private LocalDateTime validTo;

    /**
     * Defaults to true. A discount created switched off would be a
     * surprising default, and switching one off is the normal way to
     * retire it - see the note on delete in the service.
     */
    private boolean active = true;

    public DiscountType getType() { return type; }
    public void setType(DiscountType type) { this.type = type; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public BigDecimal getPercentOff() { return percentOff; }
    public void setPercentOff(BigDecimal percentOff) { this.percentOff = percentOff; }

    public LocalDateTime getValidFrom() { return validFrom; }
    public void setValidFrom(LocalDateTime validFrom) { this.validFrom = validFrom; }

    public LocalDateTime getValidTo() { return validTo; }
    public void setValidTo(LocalDateTime validTo) { this.validTo = validTo; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
