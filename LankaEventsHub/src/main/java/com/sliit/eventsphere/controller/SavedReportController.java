package com.sliit.eventsphere.controller;

import com.sliit.eventsphere.dto.ReportResultResponse;
import com.sliit.eventsphere.dto.SavedReportRequest;
import com.sliit.eventsphere.dto.SavedReportResponse;
import com.sliit.eventsphere.entity.ReportType;
import com.sliit.eventsphere.security.ActingUser;
import com.sliit.eventsphere.security.UserPrincipal;
import com.sliit.eventsphere.service.SavedReportService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST API for saved report definitions - Function 6's CRUD half.
 *
 * Endpoints:
 *   GET    /api/saved-reports        - the caller's own saved reports
 *   GET    /api/saved-reports/types  - the types this role may save
 *   POST   /api/saved-reports        - save a new definition
 *   GET    /api/saved-reports/{id}   - one definition
 *   PUT    /api/saved-reports/{id}   - edit it
 *   DELETE /api/saved-reports/{id}   - remove it
 *   GET    /api/saved-reports/{id}/run - compute and return the figures
 *
 * Note the path. These deliberately do NOT live under /api/reports, which
 * is administrator-only and must stay that way: the platform dashboard is
 * an admin thing, while a saved report belongs to whoever saved it -
 * attendees included. Ownership is the real gate and the service applies
 * it to every method here.
 */
@RestController
@RequestMapping("/api/saved-reports")
public class SavedReportController {

    private final SavedReportService savedReportService;

    public SavedReportController(SavedReportService savedReportService) {
        this.savedReportService = savedReportService;
    }

    @GetMapping
    public ResponseEntity<List<SavedReportResponse>> mine(
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(savedReportService.getMyReports(ActingUser.of(principal)));
    }

    /** Literal path, declared before /{id}, so "types" is never read as an id. */
    @GetMapping("/types")
    public ResponseEntity<List<ReportType>> types(
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(savedReportService.getAvailableTypes(ActingUser.of(principal)));
    }

    @PostMapping
    public ResponseEntity<SavedReportResponse> create(
            @Valid @RequestBody SavedReportRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(savedReportService.create(request, ActingUser.of(principal)));
    }

    @GetMapping("/{id:[0-9]+}")
    public ResponseEntity<SavedReportResponse> getOne(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(savedReportService.getById(id, ActingUser.of(principal)));
    }

    @PutMapping("/{id:[0-9]+}")
    public ResponseEntity<SavedReportResponse> update(
            @PathVariable Long id,
            @Valid @RequestBody SavedReportRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(savedReportService.update(id, request, ActingUser.of(principal)));
    }

    @DeleteMapping("/{id:[0-9]+}")
    public ResponseEntity<Void> delete(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal) {
        savedReportService.delete(id, ActingUser.of(principal));
        return ResponseEntity.noContent().build();
    }

    /**
     * Runs the saved question and returns today's answer.
     *
     * A GET, because running a report changes nothing - the figures are
     * computed from the live tables and thrown away after the response.
     */
    @GetMapping("/{id:[0-9]+}/run")
    public ResponseEntity<ReportResultResponse> run(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(savedReportService.run(id, ActingUser.of(principal)));
    }
}
