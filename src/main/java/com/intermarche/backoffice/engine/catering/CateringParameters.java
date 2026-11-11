package com.intermarche.backoffice.engine.catering;

import com.fasterxml.jackson.databind.JsonNode;
import com.intermarche.backoffice.domain.catering.CateringItem;
import com.intermarche.backoffice.domain.catering.FabricationStation;
import com.intermarche.backoffice.domain.catering.SourcingMode;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.store.TemperatureZone;
import com.intermarche.backoffice.engine.fieldops.OperationalPolicyVersions;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;

/**
 * Single resolution service for catering parameters (§ 6, P2).
 * <p>
 * The most specific value wins. Enseigne (E) is the {@code CateringConstraints}
 * schema default; Store (M) is the store's active version of that policy; for
 * scopes {@code E, M, P} and {@code E, M, A} the article ({@link CateringItem})
 * or station ({@link FabricationStation}) value, when set, overrides the policy
 * value. The policy value itself already applies the E then M precedence via
 * {@link OperationalPolicyVersions#active(Store, String)}.
 */
@ApplicationScoped
public class CateringParameters {

    /** Policy type handled by this resolver. */
    public static final String KIND = "CateringConstraints";

    /**
     * Returns the active {@code CateringConstraints} value for a store, or the
     * enseigne default when the store has no active version.
     *
     * @param store the store.
     * @return the resolved policy value as a JSON object.
     */
    public JsonNode policy(Store store) {
        return OperationalPolicyVersions.active(store, KIND);
    }

    // --------------------------------------------------
    // Enseigne / Store parameters (E, M)
    // --------------------------------------------------

    /**
     * Whether catering is enabled for the store.
     *
     * @param store the store.
     * @return the {@code enabled} flag.
     */
    public boolean enabled(Store store) {
        return policy(store).path("enabled").asBoolean(true);
    }

    /**
     * Whether an unknown EAN is auto-referenced (I5).
     *
     * @param store the store.
     * @return the {@code autoReferenceUnknownItems} flag.
     */
    public boolean autoReferenceUnknownItems(Store store) {
        return policy(store).path("autoReferenceUnknownItems").asBoolean(true);
    }

    /**
     * Default obtention mode for an unknown or stationless article.
     *
     * @param store the store.
     * @return the {@code defaultSourcingMode}.
     */
    public SourcingMode defaultSourcingMode(Store store) {
        return SourcingMode.valueOf(policy(store).path("defaultSourcingMode").asText("FABRICATED"));
    }

    /**
     * Default temperature zone for an auto-created product (I5).
     *
     * @param store the store.
     * @return the {@code defaultTemperatureZone}.
     */
    public TemperatureZone defaultTemperatureZone(Store store) {
        return TemperatureZone.valueOf(policy(store).path("defaultTemperatureZone").asText("CHILLED"));
    }

    /**
     * Whether a base recipe is required to plan a unit (F4).
     *
     * @param store the store.
     * @return the {@code recipeRequired} flag.
     */
    public boolean recipeRequired(Store store) {
        return policy(store).path("recipeRequired").asBoolean(false);
    }

    /**
     * Consumption margin in hours used when no reception date is transmitted (F5).
     *
     * @param store the store.
     * @return the {@code consumptionMarginHours}.
     */
    public int consumptionMarginHours(Store store) {
        return policy(store).path("consumptionMarginHours").asInt(6);
    }

    /**
     * Default labour seconds for a unit without a recipe (F7).
     *
     * @param store the store.
     * @return the {@code defaultLaborSeconds}.
     */
    public int defaultLaborSeconds(Store store) {
        return policy(store).path("defaultLaborSeconds").asInt(600);
    }

    /**
     * Whether units of different orders sharing a line key are batched together (F6).
     *
     * @param store the store.
     * @return the {@code batchAcrossOrders} flag.
     */
    public boolean batchAcrossOrders(Store store) {
        return policy(store).path("batchAcrossOrders").asBoolean(true);
    }

    /**
     * Proposal horizon in days (F8).
     *
     * @param store the store.
     * @return the {@code proposalHorizonDays}.
     */
    public int proposalHorizonDays(Store store) {
        return policy(store).path("proposalHorizonDays").asInt(14);
    }

    /**
     * Whether the server validates proposals automatically (F8).
     *
     * @param store the store.
     * @return the {@code autoValidateProposals} flag.
     */
    public boolean autoValidateProposals(Store store) {
        return policy(store).path("autoValidateProposals").asBoolean(false);
    }

    /**
     * Planning lead hours for the unplanned supervision situation (F22).
     *
     * @param store the store.
     * @return the {@code planningLeadHours}.
     */
    public int planningLeadHours(Store store) {
        return policy(store).path("planningLeadHours").asInt(24);
    }

    /**
     * Component shortage policy: {@code BLOCK} or {@code WARN} (F9).
     *
     * @param store the store.
     * @return the {@code componentShortagePolicy}.
     */
    public String componentShortagePolicy(Store store) {
        return policy(store).path("componentShortagePolicy").asText("WARN");
    }

    /**
     * Supplier order cutoff time (HH:MM), or null (F10).
     *
     * @param store the store.
     * @return the {@code supplierOrderCutoff}, or null.
     */
    public String supplierOrderCutoff(Store store) {
        JsonNode node = policy(store).path("supplierOrderCutoff");
        return node.isNull() || node.isMissingNode() ? null : node.asText();
    }

    /**
     * Label template code, or null (F14).
     *
     * @param store the store.
     * @return the {@code labelTemplate}, or null.
     */
    public String labelTemplate(Store store) {
        JsonNode node = policy(store).path("labelTemplate");
        return node.isNull() || node.isMissingNode() ? null : node.asText();
    }

    /**
     * Number of label copies per fabricated unit (F14).
     *
     * @param store the store.
     * @return the {@code labelCopies}.
     */
    public int labelCopies(Store store) {
        return policy(store).path("labelCopies").asInt(1);
    }

    /**
     * Whether a settlement acknowledgement is required at handover (F17).
     *
     * @param store the store.
     * @return the {@code handoverRequiresPaymentAck} flag.
     */
    public boolean handoverRequiresPaymentAck(Store store) {
        return policy(store).path("handoverRequiresPaymentAck").asBoolean(true);
    }

    /**
     * Amendment policy after fabrication start: {@code REFUSE} or {@code ACCEPT_DECREASE} (F18).
     *
     * @param store the store.
     * @return the {@code amendmentAfterStart}.
     */
    public String amendmentAfterStart(Store store) {
        return policy(store).path("amendmentAfterStart").asText("REFUSE");
    }

    /**
     * Cancellation policy after fabrication start: {@code REFUSE} or {@code ACCEPT_WITH_DISPOSITION} (F19).
     *
     * @param store the store.
     * @return the {@code cancelAfterStart}.
     */
    public String cancelAfterStart(Store store) {
        return policy(store).path("cancelAfterStart").asText("ACCEPT_WITH_DISPOSITION");
    }

    /**
     * Notification recipient roles (F23).
     *
     * @param store the store.
     * @return the {@code notificationRoles} list.
     */
    public List<String> notificationRoles(Store store) {
        return stringList(policy(store).path("notificationRoles"));
    }

    // --------------------------------------------------
    // Article-overridable parameters (E, M, P)
    // --------------------------------------------------

    /**
     * Shelf life in hours, article value overriding the policy (F5, F13).
     *
     * @param store the store.
     * @param item  the catering article, or null.
     * @return the resolved {@code shelfLifeHours}.
     */
    public int shelfLifeHours(Store store, CateringItem item) {
        if (item != null && item.shelfLifeHours != null) {
            return item.shelfLifeHours;
        }
        return policy(store).path("shelfLifeHours").asInt(48);
    }

    /**
     * Maximum advance in hours, article value overriding the policy (F5).
     *
     * @param store the store.
     * @param item  the catering article, or null.
     * @return the resolved {@code maxAdvanceHours}.
     */
    public int maxAdvanceHours(Store store, CateringItem item) {
        if (item != null && item.maxAdvanceHours != null) {
            return item.maxAdvanceHours;
        }
        return policy(store).path("maxAdvanceHours").asInt(24);
    }

    /**
     * Supplier lead days, article value overriding the policy (F10).
     *
     * @param store the store.
     * @param item  the catering article, or null.
     * @return the resolved {@code supplierLeadDays}.
     */
    public int supplierLeadDays(Store store, CateringItem item) {
        if (item != null && item.supplierLeadDays != null) {
            return item.supplierLeadDays;
        }
        return policy(store).path("supplierLeadDays").asInt(2);
    }

    /**
     * Allowed cancellation dispositions, article value overriding the policy (F19).
     *
     * @param store the store.
     * @param item  the catering article, or null.
     * @return the resolved disposition list.
     */
    public List<String> cancelDispositions(Store store, CateringItem item) {
        if (item != null && item.cancelDispositions != null && !item.cancelDispositions.isBlank()) {
            return csv(item.cancelDispositions);
        }
        return stringList(policy(store).path("cancelDispositions"));
    }

    // --------------------------------------------------
    // Station-overridable parameters (E, M, A)
    // --------------------------------------------------

    /**
     * Staging lead minutes, station value overriding the policy (F5).
     *
     * @param store   the store.
     * @param station the fabrication station, or null.
     * @return the resolved {@code stagingLeadMinutes}.
     */
    public int stagingLeadMinutes(Store store, FabricationStation station) {
        if (station != null && station.stagingLeadMinutes != null) {
            return station.stagingLeadMinutes;
        }
        return policy(store).path("stagingLeadMinutes").asInt(60);
    }

    // --------------------------------------------------
    // Helpers
    // --------------------------------------------------

    /**
     * Reads a JSON array of strings into a list.
     *
     * @param node the array node.
     * @return the list of string values (empty if the node is not an array).
     */
    private List<String> stringList(JsonNode node) {
        var result = new ArrayList<String>();
        if (node != null && node.isArray()) {
            node.forEach(n -> result.add(n.asText()));
        }
        return result;
    }

    /**
     * Splits a comma-separated value into a trimmed, non-empty list.
     *
     * @param value the comma-separated value.
     * @return the list of tokens.
     */
    private List<String> csv(String value) {
        var result = new ArrayList<String>();
        for (String token : value.split(",")) {
            String trimmed = token.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }
}
