//responsible for forcing a user's currently logged-in sessions to expire.
package com.sliit.eventsphere.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
//Makes this class a Spring service that can be injected into other classes
public class ActiveSessionService {

    private static final Logger log = LoggerFactory.getLogger(ActiveSessionService.class);

    //containing information about active user sessions.
    private final SessionRegistry sessionRegistry;

    public ActiveSessionService(SessionRegistry sessionRegistry) {
        this.sessionRegistry = sessionRegistry;
    }

    /**
     * Expires every web session belonging to this user id.
     *
     * @return how many sessions were ended
     */
    public int invalidateSessionsFor(Long userId) {
        if (userId == null) {
            return 0;
        }
        int expired = 0;
        //Gets all users currently known to the session registry.
        for (Object principal : sessionRegistry.getAllPrincipals()) {

            //Checks whether the principal is  application's UserPrincipal
            if (!(principal instanceof UserPrincipal userPrincipal)) {
                continue;
            }

            //Finds the sessions belonging to the requested user.
            if (!userId.equals(userPrincipal.getId())) {
                continue;
            }
            // includeExpiredSessions = false: already-expired ones need no
            // further action.
            List<SessionInformation> sessions =
                    sessionRegistry.getAllSessions(principal, false);
            for (SessionInformation session : sessions) {
               //Immediately expires each active session.
                session.expireNow();
                expired++;
            }
        }
        if (expired > 0) {
            log.info("Expired {} active session(s) for user {} after an account change", expired, userId);
        }
        return expired;
    }
}
