package com.sliit.eventsphere.entity;

/**
 * Where an announcement is in its approval lifecycle.
 *
 * Deliberately the same shape as {@link EventStatus}, because it is the
 * same idea: something a person composes is not visible to anybody until
 * an Administrator has approved it.
 *
 * <pre>
 *   DRAFT ──submit──▶ PENDING_APPROVAL ──approve──▶ PUBLISHED
 *     ▲                      │
 *     │                    reject
 *     └──────edit───── REJECTED
 * </pre>
 *
 * An Administrator writing their own announcement goes DRAFT → PUBLISHED
 * directly. There is no state for "approved but not yet sent": approving
 * IS sending, in one transaction, so an approved announcement that nobody
 * received cannot exist.
 */
public enum AnnouncementStatus {

    /** Being written. Visible only to its author and to administrators. */
    DRAFT,

    /** Submitted, waiting in the administrator's queue. No longer editable. */
    PENDING_APPROVAL,

    /** Approved and delivered. Final - never edited, never deleted. */
    PUBLISHED,

    /** Turned down with a reason. The author may edit it and submit again. */
    REJECTED
}
