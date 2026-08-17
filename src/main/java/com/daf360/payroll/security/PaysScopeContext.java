package com.daf360.payroll.security;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The countries the caller may see, carried per request from the JWT's `paysScopeAll` /
 * `paysIds` claims (minted by the portal from the role's pays_scope_mode — V74).
 *
 * Deliberately a byte-for-byte twin of the facturation-service class of the same name, for
 * the same reason the two JwtAuthFilters are kept aligned: the services share no module, so
 * the only way the rule stays identical is by keeping the code identical. Change one, change
 * the other.
 *
 * ABSENT is not the same as empty. A token minted before V74 carries no scope claims, and the
 * caller must then fall back to UsersRef.pays_id — the pre-V74 rule — rather than being read
 * as "sees nothing" (locking legacy sessions out) or as unrestricted (opening every country).
 */
public final class PaysScopeContext {

    private PaysScopeContext() {}

    /** @param all true = every country; paysIds is then irrelevant. */
    public record PaysScope(boolean all, Set<Long> paysIds) {

        public static PaysScope unrestricted() {
            return new PaysScope(true, Set.of());
        }

        public static PaysScope of(Set<Long> paysIds) {
            return new PaysScope(false, Set.copyOf(paysIds));
        }

        public boolean allows(long paysId) {
            return all || paysIds.contains(paysId);
        }
    }

    private static final ThreadLocal<PaysScope> HOLDER = new ThreadLocal<>();

    public static void set(PaysScope scope) { HOLDER.set(scope); }
    public static PaysScope get()           { return HOLDER.get(); }
    public static void clear()              { HOLDER.remove(); }

    /**
     * Builds a scope from raw JWT claim values, or null when the token carries no scope at
     * all (pre-V74). Jackson deserialises the JSON array as Integers, not Longs, so elements
     * go through Number rather than a Long cast.
     */
    public static PaysScope fromClaims(Object paysScopeAll, Object paysIds) {
        if (Boolean.TRUE.equals(paysScopeAll)) {
            return PaysScope.unrestricted();
        }
        if (!(paysIds instanceof List<?> raw)) {
            return paysScopeAll == null ? null : PaysScope.unrestricted();
        }
        Set<Long> ids = new LinkedHashSet<>();
        for (Object o : raw) {
            if (o instanceof Number n) ids.add(n.longValue());
        }
        // Explicit `false` with an empty list is a misconfigured role: treated as unknown so
        // the caller falls back to their own country instead of losing all access.
        return ids.isEmpty() ? null : PaysScope.of(ids);
    }
}
