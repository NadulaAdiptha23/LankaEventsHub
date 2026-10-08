package com.sliit.eventsphere.repository;

import com.sliit.eventsphere.entity.EventSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EventSessionRepository extends JpaRepository<EventSession, Long> {
    List<EventSession> findByEventId(Long eventId);
}
