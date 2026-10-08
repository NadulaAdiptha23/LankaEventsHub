package com.sliit.eventsphere.dto;

import com.sliit.eventsphere.entity.User;
import com.sliit.eventsphere.entity.UserRole;

public class UserResponse {

    private Long id;
    private String fullName;
    private String email;
    private UserRole role;
    private boolean active;
    private boolean verifiedStudent;
    private String contactNumber;

    public static UserResponse from(User u) {
        UserResponse dto = new UserResponse();
        dto.id = u.getId();
        dto.fullName = u.getFullName();
        dto.email = u.getEmail();
        dto.role = u.getRole();
        dto.active = u.isActive();
        dto.verifiedStudent = u.isVerifiedStudent();
        dto.contactNumber = u.getContactNumber();
        return dto;
    }

    public Long getId() { return id; }
    public String getFullName() { return fullName; }
    public String getEmail() { return email; }
    public UserRole getRole() { return role; }
    public boolean isActive() { return active; }
    public boolean isVerifiedStudent() { return verifiedStudent; }
    public String getContactNumber() { return contactNumber; }
}
