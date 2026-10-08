package com.sliit.eventsphere.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * What an author submits when writing or editing an announcement.
 *
 * Note what is NOT here: status, author, event, or anything about
 * approval. The status is decided by which service method was called, the
 * author is the signed-in user, and the event comes from the URL. A
 * request body that could carry its own status would be a way to publish
 * without approval, which is the one thing this whole module exists to
 * prevent.
 */
public class AnnouncementRequest {

    @NotBlank(message = "Give the announcement a title.")
    @Size(max = 150, message = "Keep the title to 150 characters or fewer.")
    private String title;

    @NotBlank(message = "Write the message you want attendees to receive.")
    @Size(max = 1000, message = "Keep the message to 1000 characters or fewer.")
    private String message;

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
