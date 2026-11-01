package com.daf360.payroll.modules.admin.service;

import com.daf360.payroll.modules.admin.dto.SaveEngineParamSetRequest;
import com.daf360.payroll.modules.admin.dto.SaveEngineRubriqueRequest;
import com.daf360.payroll.modules.payroll.entity.PayrollCountry;
import com.daf360.payroll.modules.payroll.entity.PayrollParamSet;
import com.daf360.payroll.modules.payroll.entity.PayrollRubriqueDef;
import com.daf360.payroll.modules.payroll.repository.PayrollCountryRepository;
import com.daf360.payroll.modules.payroll.repository.PayrollParamSetRepository;
import com.daf360.payroll.modules.payroll.repository.PayrollRubriqueDefRepository;
import com.daf360.payroll.modules.ref.service.UserContextService;
import com.daf360.payroll.security.PaysScopeContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.expression.ParseException;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Écritures de l'administration paie sur la configuration du MOTEUR de paie — celle que le
 * calcul réel charge (jeu de paramètres ACTIF du pays + ses rubriques) :
 *
 *  - versions du jeu de paramètres du moteur (`payroll_parameter_sets`) : nouvelle version
 *    en brouillon (copie de l'active), modification des paramètres tant qu'elle est en
 *    brouillon ; la suite (soumission, approbations, activation) reste le circuit existant
 *    de {@code ParameterSetWorkflowService}, qui vérifie à la soumission que les clés
 *    utilisées par les rubriques existent ;
 *  - rubriques du moteur (`payroll_rubriques`) : création et modification, jamais de
 *    suppression — une rubrique retirée est désactivée, pour garder l'historique des calculs.
 *
 * Les pays viennent de `payroll_countries` ; le périmètre pays de l'appelant est vérifié
 * ici pour les appels adressés par identifiant (l'intercepteur ne lit que `paysId`).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EngineConfigService {

    /** Modes de calcul connus du moteur (un calculateur par mode, cf. CalculationDispatcher). */
    public static final Set<String> MODES = Set.of(
            "TAUX_PCT", "MONTANT_FIXE", "BAREME_PROGRESSIF", "ANNUALISE_BAREME", "FORMULE", "NEWTON_RAPHSON");

    /** Modes qui appliquent un taux ou un barème à un montant de base. */
    private static final Set<String> ASSIETTE_MODES = Set.of("TAUX_PCT", "BAREME_PROGRESSIF", "ANNUALISE_BAREME");

    /**
     * Variables toujours présentes au calcul (ContextLoader, SystemVariableAggregator,
     * charges injectées par l'orchestrateur, SELF du calcul inverse).
     */
    private static final Set<String> SYSTEM_VARIABLES = Set.of(
            "JOURS_OUVRES", "MOIS", "ANNEE",
            "STRATE_1", "STRATE_2", "STRATE_3", "STRATE_4", "STRATE_5",
            "BRUT", "AVANTAGES", "CHARGES_SAL", "NET_IMPOSABLE", "NET_A_PAYER", "SELF");

    /** Noms en majuscules d'une formule, hors appels de méthode (T(…), .max(…)). */
    private static final Pattern FORMULA_NAME = Pattern.compile("(?<![\\w.])([A-Z][A-Z0-9_]*)\\b(?!\\s*\\()");

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SpelExpressionParser SPEL = new SpelExpressionParser();

    private final PayrollCountryRepository     countryRepo;
    private final PayrollParamSetRepository    paramSetRepo;
    private final PayrollRubriqueDefRepository rubriqueRepo;
    private final UserContextService           userContext;

    // ── Jeux de paramètres du moteur ─────────────────────────────────────────

    /**
     * Nouvelle version en brouillon pour un pays : copie des paramètres de la version active
     * (sinon de la plus récente, sinon `{}`). Un seul brouillon à la fois par pays (409).
     */
    @Transactional
    public PayrollParamSet createDraft(Long paysId, String createdBy) {
        PayrollCountry country = countryOf(paysId);
        assertInScope(country.getPaysId());
        List<PayrollParamSet> versions = paramSetRepo.findByCountryIdOrderByVersionNumberDesc(country.getId());
        versions.stream().filter(v -> "DRAFT".equals(v.getStatus())).findFirst().ifPresent(d -> {
            throw new IllegalStateException("Un brouillon existe déjà pour ce pays (version " + d.getVersionNumber() + ")");
        });
        PayrollParamSet source = versions.stream().filter(v -> "ACTIVE".equals(v.getStatus())).findFirst()
                .orElse(versions.isEmpty() ? null : versions.get(0));

        PayrollParamSet draft = new PayrollParamSet();
        draft.setCountryId(country.getId());
        draft.setVersionNumber(versions.stream().mapToInt(PayrollParamSet::getVersionNumber).max().orElse(0) + 1);
        draft.setStatus("DRAFT");
        draft.setEffectiveDate(LocalDate.now());
        draft.setParameters(source != null && source.getParameters() != null ? source.getParameters() : "{}");
        draft.setCreatedBy(createdBy);
        return paramSetRepo.save(draft);
    }

    /** Modifie les paramètres (et la date d'effet) d'une version en brouillon (409 sinon). */
    @Transactional
    public PayrollParamSet updateDraft(Long id, SaveEngineParamSetRequest req) {
        PayrollParamSet ps = paramSetRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Jeu de paramètres du moteur introuvable : " + id));
        assertInScope(countryById(ps.getCountryId()).getPaysId());
        if (!"DRAFT".equals(ps.getStatus()))
            throw new IllegalStateException("Seule une version en brouillon est modifiable (statut : " + ps.getStatus() + ")");
        ps.setParameters(normaliseParameters(req.parameters()));
        if (req.effectiveDate() != null) ps.setEffectiveDate(req.effectiveDate());
        return paramSetRepo.save(ps);
    }

    /**
     * Contrôle et normalise le JSON des paramètres : un objet dont chaque valeur est un
     * nombre, un texte, un booléen, ou un barème — tableau de tranches {@code {min, max?, rate}}
     * numériques, {@code max} ≥ {@code min} — le format que lisent les calculateurs à barème.
     */
    static String normaliseParameters(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("Paramètres obligatoires (objet JSON)");
        JsonNode root;
        try {
            root = JSON.readTree(raw);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Paramètres : JSON invalide");
        }
        if (root == null || !root.isObject()) throw new IllegalArgumentException("Paramètres : un objet JSON est attendu");
        Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> f = fields.next();
            String key = f.getKey();
            if (!key.matches("[A-Za-z0-9_.]{1,50}"))
                throw new IllegalArgumentException("Nom de paramètre invalide : « " + key + " » (lettres, chiffres, _ et . ; 50 max)");
            JsonNode v = f.getValue();
            if (v.isArray()) validateBareme(key, v);
            else if (!(v.isNumber() || v.isTextual() || v.isBoolean()))
                throw new IllegalArgumentException("Paramètre « " + key + " » : nombre, texte ou barème attendu");
        }
        try {
            return JSON.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Paramètres : JSON invalide");
        }
    }

    private static void validateBareme(String key, JsonNode brackets) {
        if (brackets.isEmpty()) throw new IllegalArgumentException("Barème « " + key + " » : au moins une tranche");
        for (JsonNode b : brackets) {
            if (!b.isObject() || !b.path("min").isNumber() || !b.path("rate").isNumber())
                throw new IllegalArgumentException("Barème « " + key + " » : chaque tranche doit avoir « min » et « rate » numériques");
            JsonNode max = b.get("max");
            if (max != null && !max.isNull()) {
                if (!max.isNumber()) throw new IllegalArgumentException("Barème « " + key + " » : « max » doit être un nombre ou null");
                if (max.decimalValue().compareTo(b.get("min").decimalValue()) < 0)
                    throw new IllegalArgumentException("Barème « " + key + " » : « max » inférieur à « min »");
            }
        }
    }

    // ── Rubriques du moteur ──────────────────────────────────────────────────

    /** Toutes les rubriques du pays, actives ou non (vide si le pays n'est pas configuré). */
    public List<PayrollRubriqueDef> listRubriques(Long paysId) {
        return countryRepo.findByPaysId(paysId)
                .map(c -> rubriqueRepo.findByCountryIdOrderByDisplayOrderAscCodeAsc(c.getId()))
                .orElse(List.of());
    }

    @Transactional
    public PayrollRubriqueDef createRubrique(Long paysId, SaveEngineRubriqueRequest req) {
        PayrollCountry country = countryOf(paysId);
        assertInScope(country.getPaysId());
        String code = req.code() == null ? "" : req.code().trim().toUpperCase();
        if (!code.matches("[A-Z0-9_]{1,20}"))
            throw new IllegalArgumentException("Code invalide : lettres majuscules, chiffres et _ (20 max)");
        if (rubriqueRepo.findByCountryIdAndCode(country.getId(), code).isPresent())
            throw new IllegalStateException("La rubrique " + code + " existe déjà pour ce pays");
        PayrollRubriqueDef r = new PayrollRubriqueDef();
        r.setCountryId(country.getId());
        r.setCode(code);
        apply(r, req);
        return rubriqueRepo.save(r);
    }

    /** Modifie une rubrique — son code ne change pas (les formules y font référence). */
    @Transactional
    public PayrollRubriqueDef updateRubrique(Long id, SaveEngineRubriqueRequest req) {
        PayrollRubriqueDef r = rubriqueRepo.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Rubrique du moteur introuvable : " + id));
        assertInScope(countryById(r.getCountryId()).getPaysId());
        apply(r, req);
        return rubriqueRepo.save(r);
    }

    private void apply(PayrollRubriqueDef r, SaveEngineRubriqueRequest req) {
        String label = trim(req.labelFr());
        if (label == null) throw new IllegalArgumentException("Libellé (français) obligatoire");
        if (label.length() > 200) throw new IllegalArgumentException("Libellé trop long (200 max)");
        int strate = req.strate() == null ? 0 : req.strate();
        if (strate < 1 || strate > 5) throw new IllegalArgumentException("Strate invalide : 1 à 5");
        String nature = trim(req.nature());
        if (nature == null || nature.length() > 20) throw new IllegalArgumentException("Nature obligatoire (20 caractères max)");
        String mode = trim(req.modeCalcul());
        if (mode == null || !MODES.contains(mode)) throw new IllegalArgumentException("Mode de calcul inconnu : " + req.modeCalcul());

        String taux = key(req.paramKeyTaux(), "taux");
        String plafond = key(req.paramKeyPlafond(), "plafond");
        String bareme = key(req.paramKeyBareme(), "barème");
        String formula = trim(req.formulaExpression());
        switch (mode) {
            case "TAUX_PCT", "MONTANT_FIXE" -> require(taux, "Mode " + mode + " : clé de paramètre « taux / montant » obligatoire");
            case "BAREME_PROGRESSIF", "ANNUALISE_BAREME" -> require(bareme, "Mode " + mode + " : clé de barème obligatoire");
            case "FORMULE" -> require(formula, "Mode FORMULE : formule obligatoire");
            case "NEWTON_RAPHSON" -> {
                require(formula, "Mode NEWTON_RAPHSON : formule obligatoire");
                require(taux, "Mode NEWTON_RAPHSON : clé de la valeur cible obligatoire");
            }
            default -> { }
        }
        String assiette = trim(req.assietteCode());
        if (assiette != null && assiette.length() > 20) throw new IllegalArgumentException("Code d'assiette trop long (20 max)");
        // Sans montant de base, un pourcentage ou un barème vaut toujours 0 au calcul.
        if (ASSIETTE_MODES.contains(mode)) require(assiette, "Mode " + mode + " : montant de base obligatoire");
        if (formula != null) {
            if (formula.length() > 1000) throw new IllegalArgumentException("Formule trop longue (1000 max)");
            try {
                SPEL.parseExpression(formula);
            } catch (ParseException e) {
                throw new IllegalArgumentException("Formule invalide : " + e.getMessage());
            }
        }
        if (assiette != null || formula != null) {
            // Un nom inconnu n'est pas remplacé au calcul : la ligne vaudrait 0 sans message.
            Set<String> known = knownVariables(r);
            if (assiette != null && !isKnown(assiette, known))
                throw new IllegalArgumentException("Montant de base inconnu : " + assiette);
            if (formula != null) {
                Set<String> unknown = new TreeSet<>();
                Matcher m = FORMULA_NAME.matcher(formula);
                while (m.find()) if (!isKnown(m.group(1), known)) unknown.add(m.group(1));
                if (!unknown.isEmpty())
                    throw new IllegalArgumentException("Code inconnu dans la formule : " + String.join(", ", unknown));
            }
        }
        String contracts = trim(req.contractTypeFilter());
        if (contracts != null && contracts.length() > 200) throw new IllegalArgumentException("Filtre de contrats trop long (200 max)");
        String periodicite = trim(req.periodicite());
        if (periodicite != null && periodicite.length() > 20) throw new IllegalArgumentException("Périodicité trop longue (20 max)");
        int order = req.displayOrder() == null ? 0 : req.displayOrder();
        if (order < 0) throw new IllegalArgumentException("Ordre d'affichage négatif");

        r.setLabelFr(label);
        r.setLabelEn(trim(req.labelEn()));
        r.setStrate(strate);
        r.setNature(nature.toUpperCase());
        r.setModeCalcul(mode);
        r.setAssietteCode(assiette);
        r.setParamKeyTaux(taux);
        r.setParamKeyPlafond(plafond);
        r.setParamKeyBareme(bareme);
        r.setFormulaExpression(formula);
        r.setContractTypeFilter(contracts);
        r.setPeriodicite(periodicite == null ? "MENSUEL" : periodicite.toUpperCase());
        r.setProrataApplicable(Boolean.TRUE.equals(req.prorataApplicable()));
        r.setDisplayOrder(order);
        r.setActive(req.active() == null || req.active());
    }

    // ── Utilitaires ──────────────────────────────────────────────────────────

    /**
     * Clés de paramètres utilisées par les rubriques actives d'un pays et absentes d'un jeu
     * de paramètres — sert au contrôle à la soumission (un jeu activé sans ces clés ferait
     * échouer le calcul).
     */
    public Set<String> missingKeys(Long countryId, String parameters) {
        Set<String> present = new TreeSet<>();
        try {
            JsonNode root = JSON.readTree(parameters == null ? "{}" : parameters);
            if (root != null && root.isObject()) root.fieldNames().forEachRemaining(present::add);
        } catch (JsonProcessingException e) {
            // JSON illisible : toutes les clés manquent.
        }
        Set<String> missing = new TreeSet<>();
        for (PayrollRubriqueDef r : rubriqueRepo.findByCountryIdAndActiveTrue(countryId)) {
            for (String k : new String[] { r.getParamKeyTaux(), r.getParamKeyPlafond(), r.getParamKeyBareme() }) {
                if (k != null && !k.isBlank() && !present.contains(k)) missing.add(k);
            }
        }
        return missing;
    }

    /** Noms qu'un montant de base ou une formule peut utiliser : variables du calcul et codes des rubriques du pays. */
    private Set<String> knownVariables(PayrollRubriqueDef r) {
        Set<String> known = new HashSet<>(SYSTEM_VARIABLES);
        if (r.getCode() != null) known.add(r.getCode());
        if (r.getCountryId() != null)
            rubriqueRepo.findByCountryIdOrderByDisplayOrderAscCodeAsc(r.getCountryId())
                    .forEach(other -> known.add(other.getCode()));
        return known;
    }

    /** Les cotisations des Règles de paie arrivent au calcul sous {CODE}_EE et {CODE}_ER. */
    private static boolean isKnown(String name, Set<String> known) {
        return known.contains(name) || name.endsWith("_EE") || name.endsWith("_ER");
    }

    private PayrollCountry countryOf(Long paysId) {
        if (paysId == null) throw new IllegalArgumentException("Pays obligatoire");
        return countryRepo.findByPaysId(paysId)
                .orElseThrow(() -> new IllegalArgumentException("Ce pays n'est pas configuré dans le moteur de paie (carte « Pays de paie »)"));
    }

    private PayrollCountry countryById(Long countryId) {
        return countryRepo.findById(countryId)
                .orElseThrow(() -> new NoSuchElementException("Pays de paie introuvable : " + countryId));
    }

    private void assertInScope(Long paysId) {
        PaysScopeContext.PaysScope scope = userContext.getCurrentUserPaysScope();
        if (scope != null && !scope.all() && !scope.allows(paysId))
            throw new AccessDeniedException("Ce pays est hors de votre périmètre");
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String key(String raw, String what) {
        String k = trim(raw);
        if (k != null && !k.matches("[A-Za-z0-9_.]{1,50}"))
            throw new IllegalArgumentException("Clé de paramètre « " + what + " » invalide : " + k);
        return k;
    }

    private static void require(String value, String message) {
        if (value == null) throw new IllegalArgumentException(message);
    }
}
