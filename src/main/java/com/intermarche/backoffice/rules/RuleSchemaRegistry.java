package com.intermarche.backoffice.rules;

import com.fasterxml.jackson.databind.*;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.engine.handling.*;
import com.intermarche.backoffice.engine.fulfillers.*;
import com.networknt.schema.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.*;

/** Describes and validates the same rules consumed by the Java engines. */
@ApplicationScoped
public class RuleSchemaRegistry {
    @Inject ObjectMapper mapper;
    @Inject ProductCollectingOrderManagerFactory products;
    @Inject ZoneCollectingOrderManagerFactory zones;
    @Inject PackingOrderManagerFactory packing;
    @Inject ReplenishmentOrderManagerFactory replenishment;
    @Inject StockProtectionOrderManagerFactory protection;
    @Inject GroupingOrderManagerFactory grouping;
    @Inject DefaultFulfillerFactory defaults;
    @Inject ProductGroupingFulfillerFactory productGroups;
    @Inject ZoneGroupingFulfillerFactory zoneGroups;
    public record Definition(String type,String category,String label,String schema) {}
    /** Configuration types made of rules described by this registry. */
    public static final Set<String> CATEGORIES=Set.of("FULFILLER","ORDER_HANDLER",com.intermarche.backoffice.engine.supervision.SupervisionSettings.CONFIGURATION);
    /**
     * Lists the rule types of the rule-based configurations, with their category, label and JSON schema.
     *
     * @return the definitions of the rule types
     */
    public List<Definition> definitions() { var result=new ArrayList<Definition>(List.of(
        new Definition("ProductCollecting","ORDER_HANDLER","Collecte par produit",ProductCollectingOrderManagerFactory.COLLECTING_MANAGER_SCHEMA),
        new Definition("ZoneCollecting","ORDER_HANDLER","Collecte par zone",ZoneCollectingOrderManagerFactory.COLLECTING_MANAGER_SCHEMA),
        new Definition("StockProtection","ORDER_HANDLER","Mise en sécurité des stocks",StockProtectionOrderManagerFactory.schema()),
        new Definition("Replenishment","ORDER_HANDLER","Réachalandage intelligent",ReplenishmentOrderManagerFactory.schema()),
        new Definition("Packing","ORDER_HANDLER","Emballage",PackingOrderManagerFactory.PACKING_MANAGER_SCHEMA),
        new Definition("Grouping","ORDER_HANDLER","Regroupement",GroupingOrderManagerFactory.GROUPING_MANAGER_SCHEMA),
        new Definition("Default","FULFILLER","Répartition par bâtiment",DefaultFulfillerFactory.DEFAULT_FULFILLER_SCHEMA),
        new Definition("ProductGrouping","FULFILLER","Répartition par famille",ProductGroupingFulfillerFactory.PRODUCT_GROUPING_SCHEMA),
        new Definition("ZoneGrouping","FULFILLER","Répartition par zone",ZoneGroupingFulfillerFactory.ZONE_CONFIG_SCHEMA),
        new Definition(com.intermarche.backoffice.engine.supervision.SupervisionSettings.RULE,com.intermarche.backoffice.engine.supervision.SupervisionSettings.CONFIGURATION,"Seuils de la supervision",com.intermarche.backoffice.engine.supervision.SupervisionSettings.schema())));result.addAll(com.intermarche.backoffice.engine.policy.OperationalPolicySchemas.definitions());return List.copyOf(result); }
    /**
     * Validates one rule of a rule-based configuration against the schema of its type and the stores it applies to.
     *
     * @param category configuration type of the rule
     * @param rule rule to validate
     * @param stores stores the configuration applies to
     */
    public void validate(String category,JsonNode rule,Collection<Store> stores) {
        if(rule==null || !rule.isObject()) throw new IllegalArgumentException("Une règle doit être un objet JSON");
        Definition definition=definitions().stream().filter(d -> d.category.equals(category) && d.type.equals(rule.path("type").asText())).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Type de règle non pris en charge"));
        try {
            JsonNode schema=mapper.readTree(definition.schema);
            var fields=rule.fieldNames();
            while(fields.hasNext()) {String field=fields.next();if(!Set.of("type","id","priority","name").contains(field) && !schema.path("properties").has(field)) throw new IllegalArgumentException("Paramètre non pris en charge : "+field);}
            var errors=JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7).getSchema(schema).validate(rule);
            if(!errors.isEmpty()) throw new IllegalArgumentException(errors.toString());
            if(rule.has("priority") && !rule.get("priority").isIntegralNumber()) throw new IllegalArgumentException("La priorité doit être un entier");
            switch(definition.type) {
                case "ProductCollecting" -> products.build(rule); case "ZoneCollecting" -> zones.build(rule);
                case "StockProtection" -> protection.build(rule);
                case "Replenishment" -> replenishment.build(rule);
                case "Packing" -> packing.build(rule); case "Grouping" -> grouping.build(rule);
                case "Default" -> defaults.build(rule); case "ProductGrouping" -> productGroups.build(rule); case "ZoneGrouping" -> zoneGroups.build(rule);
                case "ActivitySupervision" -> com.intermarche.backoffice.engine.supervision.SupervisionSettings.validate(rule);
            }
            if(rule.has("eans")) for(JsonNode ean:rule.get("eans")) if(com.intermarche.backoffice.domain.order.Product.findByEan(ean.asText())==null) throw new IllegalArgumentException("Produit inconnu : "+ean.asText());
            if(rule.has("families")) for(JsonNode family:rule.get("families")) if(com.intermarche.backoffice.domain.order.ProductFamily.findByCode(family.asText())==null) throw new IllegalArgumentException("Famille inconnue : "+family.asText());
            for(Store entry:stores) {
                Store store=org.hibernate.Hibernate.unproxy(entry,Store.class);
                if("StockProtection".equals(definition.type)) com.intermarche.backoffice.engine.stock.StockProtectionService.target(store,rule);
                if("Replenishment".equals(definition.type)) {
                    com.intermarche.backoffice.engine.replenishment.AutomaticReplenishmentService.ownedBuilding(store,rule.path("sourceBuildingCode").asText(),true);
                    com.intermarche.backoffice.engine.replenishment.AutomaticReplenishmentService.ownedBuilding(store,rule.path("destinationBuildingCode").asText(),false);
                }
                List<String> buildingCodes=new ArrayList<>();
                if(rule.hasNonNull("buildingCode")) buildingCodes.add(rule.get("buildingCode").asText());
                if(rule.has("buildingCodes")) rule.get("buildingCodes").forEach(b -> buildingCodes.add(b.asText()));
                for(String code:buildingCodes) if(Store.count("select count(s) from Store s join s.buildings b where s.id = ?1 and b.buildingCode = ?2",store.id,code)==0) throw new IllegalArgumentException("Bâtiment hors du magasin : "+code);
                if(rule.hasNonNull("targetBufferCode")) {
                    long ownedBuffers=Buffer.count("select count(b) from Buffer b where b.bufferCode = ?1 and b.building.id in (select owned.id from Store s join s.buildings owned where s.id = ?2)",rule.get("targetBufferCode").asText(),store.id);
                    if(ownedBuffers==0) throw new IllegalArgumentException("Buffer hors du magasin");
                }
            }
        } catch(IllegalArgumentException e) {throw e;} catch(Exception e) {org.jboss.logging.Logger.getLogger(RuleSchemaRegistry.class).warn("Rule validation failed for type " + rule.path("type").asText(),e);throw new IllegalStateException("Impossible de vérifier la règle « " + rule.path("name").asText(rule.path("type").asText()) + " » : erreur technique de validation",e);}
    }
    /**
     * Sorts the rules of a rule-based configuration by decreasing priority; other configurations are returned unchanged.
     *
     * @param category configuration type
     * @param specification JSON specification
     * @return the normalized specification
     */
    public String normalize(String category,String specification) {
        if(!CATEGORIES.contains(category)) return specification;
        try {
            JsonNode rules=mapper.readTree(specification);
            if(!rules.isArray()) throw new IllegalArgumentException("Une liste de règles est attendue");
            List<JsonNode> sorted=new ArrayList<>();rules.forEach(sorted::add);
            sorted.sort(Comparator.comparingInt((JsonNode n) -> n.path("priority").asInt(0)).reversed());
            return mapper.writeValueAsString(sorted);
        } catch(IllegalArgumentException e) {throw e;} catch(Exception e) {throw new IllegalArgumentException("JSON invalide",e);}
    }
    /**
     * Validates every rule of a rule-based configuration; other configurations are not checked here.
     *
     * @param category configuration type
     * @param specification JSON specification
     * @param stores stores the configuration applies to
     */
    public void validateSpecification(String category,String specification,Collection<Store> stores) {
        if(com.intermarche.backoffice.engine.policy.OperationalPolicySchemas.CATEGORY.equals(category))throw new IllegalArgumentException("Activez les politiques après simulation depuis leur formulaire dédié");
        if(!CATEGORIES.contains(category)) return;
        try {
            JsonNode rules=mapper.readTree(specification);
            if(!rules.isArray() || rules.size()>100) throw new IllegalArgumentException("La configuration doit contenir un tableau de 100 règles maximum");
            for(JsonNode rule:rules) validate(category,rule,stores);
        } catch(IllegalArgumentException e) {throw e;} catch(Exception e) {throw new IllegalArgumentException("JSON invalide",e);}
    }
}
