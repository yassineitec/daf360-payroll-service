package com.daf360.payroll.web;

import com.daf360.payroll.engine.ConvergenceException;
import com.daf360.payroll.engine.CyclicDependencyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(CyclicDependencyException.class)
    public ProblemDetail handleCyclicDependency(CyclicDependencyException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(422);
        pd.setDetail(ex.getMessage());
        pd.setProperty("cycleCodes", ex.getCycleCodes());
        return pd;
    }

    @ExceptionHandler(ConvergenceException.class)
    public ProblemDetail handleConvergence(ConvergenceException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(422);
        pd.setDetail("Le calcul n'a pas convergé après " + ex.getIterations()
                + " itérations. Vérifiez les paramètres de paie ou augmentez le nombre d'itérations.");
        return pd;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleBadRequest(IllegalArgumentException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        pd.setDetail(ex.getMessage());
        return pd;
    }

    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail handleConflict(IllegalStateException ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.CONFLICT);
        pd.setDetail(ex.getMessage());
        return pd;
    }

    /**
     * 403.
     *
     * Le log dit QUI a été refusé et avec QUELLES autorités : un « Access Denied » nu ne
     * permet pas de distinguer les deux causes réelles, qui appellent des correctifs
     * opposés — jeton rejeté (mauvais `JWT_SECRET`, principal anonyme) contre jeton
     * valide dont la revendication `permissions` ne porte aucun code `PAYROLL_*` (droit
     * non accordé en base, ou accordé APRÈS l'ouverture de session : les permissions sont
     * figées dans le jeton à la connexion).
     *
     * Les codes sont journalisés tels quels : ce sont des noms de droits, pas des données
     * personnelles, et c'est précisément la liste qu'il faut comparer à `@PreAuthorize`.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleForbidden(AccessDeniedException ex) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            log.warn("403 — aucun principal authentifié : le jeton est absent, expiré ou "
                   + "rejeté (vérifier que app.jwt-secret est identique à celui du portail).");
        } else {
            String held = auth.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .filter(a -> a.startsWith("PAYROLL_"))
                    .sorted()
                    .collect(Collectors.joining(", "));
            log.warn("403 — utilisateur {} authentifié mais sans droit suffisant. "
                   + "Codes PAYROLL_* portés par le jeton : [{}]. "
                   + "S'il est vide : accorder les droits (RolePermissions) PUIS se reconnecter, "
                   + "la revendication est figée à la connexion.",
                    auth.getName(), held.isEmpty() ? "aucun" : held);
        }

        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        pd.setDetail("Accès refusé. Vous n'avez pas les droits nécessaires pour effectuer cette action.");
        return pd;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGeneric(Exception ex) {
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        pd.setDetail("Une erreur interne s'est produite. Veuillez réessayer ou contacter le support.");
        return pd;
    }
}
