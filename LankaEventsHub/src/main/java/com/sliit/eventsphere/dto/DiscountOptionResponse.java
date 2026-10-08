package com.sliit.eventsphere.dto;

import com.sliit.eventsphere.entity.Discount;
import com.sliit.eventsphere.entity.DiscountType;

/**
 * A discount as the checkout page needs to show it: enough to render the
 * option and decide which extra field (promo code, group size) to reveal,
 * and nothing else.
 *
 * The page used to iterate over Discount entities straight from a
 * repository the controller injected. This keeps the entity - and its
 * validity window, its event association, and the repository - out of the
 * view layer.
 */
public class DiscountOptionResponse {

    private Long id;
    private DiscountType type;
    private String label;
    private boolean requiresCode;
    private boolean requiresGroupSize;

    public static DiscountOptionResponse from(Discount discount) {
        DiscountOptionResponse dto = new DiscountOptionResponse();
        dto.id = discount.getId();
        dto.type = discount.getType();
        dto.label = switch (discount.getType()) {
            case EARLY_BIRD -> "Early bird";
            case STUDENT -> "Student (verified students only)";
            case PROMO_CODE -> "Promo code";
            case GROUP -> "Group booking (3 or more)";
        };
        dto.requiresCode = discount.getType() == DiscountType.PROMO_CODE;
        dto.requiresGroupSize = discount.getType() == DiscountType.GROUP;
        return dto;
    }

    public Long getId() { return id; }
    public DiscountType getType() { return type; }
    public String getLabel() { return label; }
    public boolean isRequiresCode() { return requiresCode; }
    public boolean isRequiresGroupSize() { return requiresGroupSize; }
}
