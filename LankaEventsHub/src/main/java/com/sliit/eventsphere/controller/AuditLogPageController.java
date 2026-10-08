package com.sliit.eventsphere.controller;

import com.sliit.eventsphere.dto.AuditLogRow;
import com.sliit.eventsphere.dto.AuditSearchCriteria;
import com.sliit.eventsphere.security.ActingUser;
import com.sliit.eventsphere.security.UserPrincipal;
import com.sliit.eventsphere.service.AuditLogService;
import com.sliit.eventsphere.web.CsvWriter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

/**
 * The audit trail, read back.
 *
 * Rows have been written on every state change since the first version of
 * this system; until now nothing read them. This is the screen that turns
 * that table into an audit trail.
 *
 * Read-only by design. There is no edit action and no delete action,
 * because a trail somebody can amend answers nothing - which is also why
 * audit_logs.user_id is ON DELETE SET NULL rather than CASCADE: deleting
 * an account must not delete the record of what it did.
 */
@Controller
@PreAuthorize("hasRole('ADMIN')")
public class AuditLogPageController {

    private static final int PAGE_SIZE = 50;
    private static final int EXPORT_LIMIT = 10000;

    private final AuditLogService auditLogService;

    public AuditLogPageController(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @GetMapping("/admin/audit")
    public String audit(@ModelAttribute("criteria") AuditSearchCriteria criteria,
                         @RequestParam(defaultValue = "0") int page,
                         @AuthenticationPrincipal UserPrincipal principal,
                         Model model) {
        ActingUser actor = ActingUser.of(principal);

        Page<AuditLogRow> entries = auditLogService.search(criteria,
                PageRequest.of(Math.max(0, page), PAGE_SIZE,
                        Sort.by(Sort.Direction.DESC, "createdAt")),
                actor);

        model.addAttribute("entries", entries);
        model.addAttribute("actions", auditLogService.getActions(actor));
        model.addAttribute("entityTypes", auditLogService.getEntityTypes(actor));
        model.addAttribute("queryString", queryString(criteria));
        return "admin-audit";
    }

    @GetMapping("/admin/audit.csv")
    public void auditCsv(@ModelAttribute("criteria") AuditSearchCriteria criteria,
                          @AuthenticationPrincipal UserPrincipal principal,
                          HttpServletResponse response) throws IOException {
        Page<AuditLogRow> entries = auditLogService.search(criteria,
                PageRequest.of(0, EXPORT_LIMIT, Sort.by(Sort.Direction.DESC, "createdAt")),
                ActingUser.of(principal));

        CsvWriter.prepare(response, "eventsphere-audit-" + LocalDate.now() + ".csv");
        PrintWriter out = response.getWriter();
        CsvWriter.row(out, "id", "when", "actor_id", "actor_name", "actor_email",
                "action", "entity_type", "entity_id", "details");
        for (AuditLogRow e : entries.getContent()) {
            CsvWriter.row(out, e.getId(), e.getCreatedAt(), e.getActorId(),
                    e.getActorName(), e.getActorEmail(), e.getAction(),
                    e.getEntityType(), e.getEntityId(), e.getDetails());
        }
        out.flush();
    }

    private String queryString(AuditSearchCriteria c) {
        StringBuilder sb = new StringBuilder();
        append(sb, "actor", c.getActor());
        append(sb, "action", c.getAction());
        append(sb, "entityType", c.getEntityType());
        append(sb, "entityId", c.getEntityId() == null ? null : c.getEntityId().toString());
        return sb.toString();
    }

    private void append(StringBuilder sb, String key, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        sb.append('&').append(key).append('=')
          .append(java.net.URLEncoder.encode(value, StandardCharsets.UTF_8));
    }
}
