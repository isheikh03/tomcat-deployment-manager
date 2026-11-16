package com.tomcatmanager.support;

import com.tomcatmanager.access.Access;
import com.tomcatmanager.access.AccessScope;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Map;
import java.util.Set;

/**
 * For web-layer tests of role rules: every signed-in user has access to all teams and instances, so only
 * SecurityConfig's role rules decide. Team and instance restrictions are tested separately.
 */
@TestConfiguration
public class AllAccess {
    @Bean
    Access access() {
        return new Access(null, null, null, null, null) {
            @Override public AccessScope current() {
                Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                return AccessScope.everything(auth == null ? null : auth.getName());
            }
            @Override public AccessScope forUser(String username) { return AccessScope.everything(username); }
            @Override public Map<Long, Set<Long>> teamsByUser() { return Map.of(); }
        };
    }
}
