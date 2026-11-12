package com.intermarche.backoffice.engine.supervision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.backoffice.domain.Configuration;
import com.intermarche.backoffice.domain.store.Store;

import java.util.*;

/**
 * Horizons and business thresholds of the activity supervision, configurable per store with the rule configurations
 * (configuration type {@value #CONFIGURATION}, rule type {@value #RULE}). A missing configuration or field uses the
 * documented default value.
 *
 * @param horizonHours default forecast horizon of the tile
 * @param maxHorizonHours longest horizon a manager may choose
 * @param overdueLookbackHours age beyond which an overdue commitment is no longer loaded (it is counted in the limits)
 * @param planningLeadMinutes an order to prepare without preparation order is a risk only this close to its deadline
 * @param shippingLeadMinutes prepared totes still outside a buffer are a risk only this close to the deadline
 * @param urgentMinutes a risk whose deadline is this close becomes high
 * @param criticalOrderCount number of customer commitments that makes a proven problem critical
 * @param bufferSaturationPercent occupancy from which a buffer receiving planned deposits is near saturation
 * @param immobilizationHours prepared totes kept this long after the deadline of their order are immobilized
 * @param capacitySlotMinutes duration of the workload and capacity slots
 * @param staleStockMinutes age beyond which a pending stock availability evaluation is considered stale
 * @param source configuration name, or the mention of the default values
 * @param warnings ignored invalid values
 */
public record SupervisionSettings(int horizonHours, int maxHorizonHours, int overdueLookbackHours, int planningLeadMinutes,
                                  int shippingLeadMinutes, int urgentMinutes, int criticalOrderCount, int bufferSaturationPercent,
                                  int immobilizationHours, int capacitySlotMinutes, int staleStockMinutes, String source,
                                  List<String> warnings) {

    /** Parent configuration type handled by the rules tile. */
    public static final String CONFIGURATION = "SUPERVISION";
    /** Rule type inside the configuration. */
    public static final String RULE = "ActivitySupervision";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Field, label, minimum, maximum and default value of each parameter. */
    private record Field(String name, String label, int min, int max, int fallback) {}

    private static final List<Field> FIELDS = List.of(
            new Field("horizonHours", "Horizon de prévision par défaut (h)", 1, 168, 24),
            new Field("maxHorizonHours", "Horizon maximal (h)", 1, 168, 72),
            new Field("overdueLookbackHours", "Ancienneté maximale des échéances dépassées (h)", 1, 720, 168),
            new Field("planningLeadMinutes", "Délai d’alerte des préparations non planifiées (min)", 15, 2880, 240),
            new Field("shippingLeadMinutes", "Délai d’alerte avant départ (min)", 5, 1440, 60),
            new Field("urgentMinutes", "Échéance urgente (min)", 5, 1440, 60),
            new Field("criticalOrderCount", "Commandes rendant un problème critique", 1, 1000, 3),
            new Field("bufferSaturationPercent", "Seuil de saturation des buffers (%)", 50, 100, 90),
            new Field("immobilizationHours", "Immobilisation des bacs après échéance (h)", 1, 168, 12),
            new Field("capacitySlotMinutes", "Tranche de charge et capacité (min)", 15, 240, 60),
            new Field("staleStockMinutes", "Diagnostic de stock périmé après (min)", 1, 1440, 15));

    /**
     * Returns the JSON schema of the rule, used by the rules registry and the rule editor.
     *
     * @return the JSON schema
     */
    public static String schema() {
        var properties = new LinkedHashMap<String, Object>();
        properties.put("type", Map.of("const", RULE));
        properties.put("name", Map.of("type", "string"));
        properties.put("priority", Map.of("type", "integer"));
        for (var f : FIELDS)
            properties.put(f.name(), Map.of("type", "integer", "minimum", f.min(), "maximum", f.max(), "description", f.label() + " · défaut " + f.fallback()));
        try {
            return MAPPER.writeValueAsString(Map.of("type", "object", "required", List.of("type"), "properties", properties));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Returns the documented default values.
     *
     * @return the defaults
     */
    public static SupervisionSettings defaults() {
        return parse(null, "Valeurs par défaut", new ArrayList<>(), false);
    }

    /**
     * Validates a rule exactly as the supervision will read it.
     *
     * @param rule the rule JSON
     * @throws IllegalArgumentException when a value is out of range or inconsistent
     */
    public static void validate(JsonNode rule) {
        parse(rule, "validation", new ArrayList<>(), true);
    }

    /**
     * Loads the settings of a store: the rule of the highest-priority supervision configuration of the store or of
     * its groups, otherwise the defaults. Invalid values fall back to their default with a warning.
     *
     * @param store the store
     * @return the settings
     */
    public static SupervisionSettings load(Store store) {
        var warnings = new ArrayList<String>();
        var configurations = new ArrayList<>(Configuration.findConfigurationsForStore(store, CONFIGURATION));
        if (configurations.isEmpty()) return parse(null, "Valeurs par défaut", warnings, false);
        configurations.sort(Comparator.comparingInt((Configuration c) -> -c.priority).thenComparing(c -> c.id));
        if (configurations.size() > 1 && configurations.get(0).priority == configurations.get(1).priority)
            warnings.add("Plusieurs configurations de supervision ont la même priorité : « " + configurations.get(0).name + " » est utilisée.");
        var configuration = configurations.get(0);
        JsonNode rule = null;
        try {
            JsonNode spec = MAPPER.readTree(configuration.specification);
            var rules = new ArrayList<JsonNode>();
            if (spec.isArray()) spec.forEach(r -> { if (RULE.equals(r.path("type").asText())) rules.add(r); });
            else if (spec.isObject()) rules.add(spec);
            rules.sort(Comparator.comparingInt((JsonNode r) -> -r.path("priority").asInt(0)));
            if (!rules.isEmpty()) rule = rules.get(0);
        } catch (Exception e) {
            warnings.add("Configuration « " + configuration.name + " » illisible : valeurs par défaut utilisées.");
        }
        if (rule == null) return parse(null, "Valeurs par défaut (« " + configuration.name + " » sans seuils de supervision)", warnings, false);
        return parse(rule, "Configuration « " + configuration.name + " »", warnings, false);
    }

    /**
     * Reads the parameters of a rule.
     *
     * @param rule the rule, null for the defaults
     * @param source description of the origin
     * @param warnings receives the ignored values
     * @param strict whether an invalid value is an error instead of a warning
     * @return the settings
     */
    private static SupervisionSettings parse(JsonNode rule, String source, List<String> warnings, boolean strict) {
        var values = new HashMap<String, Integer>();
        for (var f : FIELDS) {
            int value = f.fallback();
            JsonNode node = rule == null ? null : rule.get(f.name());
            if (node != null && !node.isNull()) {
                if (!node.isIntegralNumber() || node.asLong() < f.min() || node.asLong() > f.max()) {
                    String message = f.label() + " : valeur entière entre " + f.min() + " et " + f.max() + " attendue.";
                    if (strict) throw new IllegalArgumentException(message);
                    warnings.add(message + " Valeur par défaut " + f.fallback() + " utilisée.");
                } else value = node.asInt();
            }
            values.put(f.name(), value);
        }
        if (values.get("horizonHours") > values.get("maxHorizonHours")) {
            String message = "L’horizon par défaut dépasse l’horizon maximal.";
            if (strict) throw new IllegalArgumentException(message);
            warnings.add(message + " L’horizon maximal est utilisé.");
            values.put("horizonHours", values.get("maxHorizonHours"));
        }
        return new SupervisionSettings(values.get("horizonHours"), values.get("maxHorizonHours"), values.get("overdueLookbackHours"),
                values.get("planningLeadMinutes"), values.get("shippingLeadMinutes"), values.get("urgentMinutes"), values.get("criticalOrderCount"),
                values.get("bufferSaturationPercent"), values.get("immobilizationHours"), values.get("capacitySlotMinutes"),
                values.get("staleStockMinutes"), source, List.copyOf(warnings));
    }
}
