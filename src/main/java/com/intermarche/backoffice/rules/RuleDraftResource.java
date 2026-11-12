package com.intermarche.backoffice.rules;

import com.fasterxml.jackson.databind.*;
import com.intermarche.backoffice.domain.store.Store;
import com.intermarche.backoffice.domain.store.Building;
import com.intermarche.backoffice.domain.store.WarehouseBuilding;
import com.intermarche.backoffice.graphql.GraphQLTrait;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.*;

@Path("/rules")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"ADMIN","MANAGER"})
@RunOnVirtualThread
public class RuleDraftResource implements GraphQLTrait {
    @Inject SecurityIdentity identity;
    @Inject RuleSchemaRegistry registry;
    @Inject AnthropicMessagesClient client;
    @Inject ObjectMapper mapper;
    @Inject com.intermarche.backoffice.engine.StoreActivityEngine activityEngine;
    /** Limits the drafts asked by each user. */
    @Inject RuleDraftQuota quota;
    /**
     * Returns the security identity of the current user.
     *
     * @return the identity
     */
    public SecurityIdentity getIdentity() {return identity;}
    /**
     * Resolves the store addressed by a request: the store of a manager (any other identifier is refused), or the
     * store chosen by an administrator.
     *
     * @param id requested store identifier, optional for a manager
     * @return the store
     * @throws ForbiddenException if a manager addresses another store or is not linked to a store
     * @throws BadRequestException if an administrator does not choose an existing store
     */
    private Store scoped(Long id) {
        Store own=getStoreContext();
        if(!identity.hasRole("ADMIN")) {
            if(own==null || (id!=null && !own.id.equals(id))) throw new ForbiddenException("Magasin hors de votre périmètre");
            return own;
        }
        Store store=id==null?null:Store.findById(id);
        if(store==null) throw new BadRequestException("Choisissez un magasin");return store;
    }
    /**
     * Describes a store for rule drafting: its buildings with their areas and aisles, its buffers and up to 200
     * products placed in its locations.
     *
     * @param store the store
     * @return the description, by key
     */
    private Map<String,Object> storeContext(Store store) {
        List<com.intermarche.backoffice.domain.store.Buffer> buffers=com.intermarche.backoffice.domain.store.Buffer.list("select b from Buffer b where b.building.id in (select building.id from Store s join s.buildings building where s.id = ?1)",store.id);
        return Map.of("buildings",store.buildings.stream().map(b -> Map.of("code",b.buildingCode,"type",org.hibernate.Hibernate.unproxy(b,Building.class) instanceof WarehouseBuilding?"WAREHOUSE":"STORE",
                "areas",b.locations.stream().map(l -> l.area).filter(Objects::nonNull).distinct().sorted().toList(),
                "aisles",b.locations.stream().map(l -> l.aisle).filter(Objects::nonNull).distinct().sorted().toList())).toList(),
            "buffers",buffers.stream().map(b -> b.bufferCode).sorted().toList(),
            "products",store.buildings.stream().flatMap(b -> b.locations.stream()).filter(l -> l.product!=null)
                .map(l -> Map.of("ean",l.product.ean,"name",l.product.name)).distinct().limit(200).toList());
    }
    /**
     * Returns what the rule editor needs for a store: its identity, whether the AI assistant is available, the rule
     * definitions and the buildings.
     *
     * @param storeId store identifier, optional for a manager
     * @return the catalog, by key
     */
    @GET @Path("/catalog") @Transactional
    public Map<String,Object> catalog(@QueryParam("storeId") Long storeId) {
        Store store=scoped(storeId);
        return Map.of("storeId",store.id,"storeCode",store.code,"aiAvailable",client.isConfigured(),"definitions",registry.definitions(),
            "buildings",storeContext(store).get("buildings"));
    }
    /**
     * Lists the stores whose rules the user may edit: every store for an administrator, their own store for a manager.
     *
     * @return the stores with their identifier, code and name
     */
    @GET @Path("/stores") @Transactional
    public List<Map<String,Object>> stores() {
        List<Store> values=identity.hasRole("ADMIN") ? Store.listAll() : List.of(scoped(null));
        return values.stream().map(s -> Map.<String,Object>of("id",s.id,"code",s.code,"name",s.name)).toList();
    }
    /**
     * Lists the rule-based configurations of a store: order routing, handling and supervision thresholds.
     *
     * @param id store identifier, required for an administrator
     * @return the configurations with their type, priority, specification, scope and whether the user may edit them
     */
    @GET @Path("/configurations") @Transactional
    public List<Map<String,Object>> configurations(@QueryParam("storeId") Long id) {
        Store store=scoped(id);
        List<com.intermarche.backoffice.domain.Configuration> values=new ArrayList<>();
        values.addAll(com.intermarche.backoffice.domain.Configuration.findConfigurationsForStore(store,"FULFILLER"));
        values.addAll(com.intermarche.backoffice.domain.Configuration.findConfigurationsForStore(store,"ORDER_HANDLER"));
        values.addAll(com.intermarche.backoffice.domain.Configuration.findConfigurationsForStore(store,com.intermarche.backoffice.engine.supervision.SupervisionSettings.CONFIGURATION));
        return values.stream().filter(c -> RuleSchemaRegistry.CATEGORIES.contains(c.type))
            .map(c -> Map.<String,Object>of("id",c.id,"name",c.name,"type",c.type,"priority",c.priority,"specification",c.specification,
                "editable",identity.hasRole("ADMIN") || (c.stores.size()==1 && c.stores.stream().anyMatch(s -> s.id.equals(store.id)) && c.storeGroups.isEmpty()),
                "stores",c.stores.stream().map(s -> Map.of("id",s.id)).toList(),"storeGroups",c.storeGroups.stream().map(g -> Map.of("id",g.id)).toList())).toList();
    }
    public static class ApplyRequest {public Long storeId;}
    /**
     * Evaluates the rules of a store immediately and reports the handling orders this evaluation created.
     *
     * @param request the store to evaluate
     * @return the created orders and a message
     * @throws BadRequestException if no request is given
     * @throws WebApplicationException (409) if the evaluation fails
     */
    @POST @Path("/apply") @Transactional
    public Map<String,Object> apply(ApplyRequest request) {
        if(request==null)throw new BadRequestException("Magasin requis");
        Store store=org.hibernate.Hibernate.unproxy(scoped(request.storeId),Store.class);
        var before=com.intermarche.backoffice.domain.wms.BaseHandlingOrder.<com.intermarche.backoffice.domain.wms.BaseHandlingOrder>list("store",store).stream().map(o -> o.id).collect(java.util.stream.Collectors.toSet());
        try{activityEngine.process(store);}catch(Exception e){org.jboss.logging.Logger.getLogger(RuleDraftResource.class).error("Rule evaluation failed",e);throw new WebApplicationException("Impossible d’appliquer les règles du magasin. Consultez les événements de manutention.",409);}
        var created=com.intermarche.backoffice.domain.wms.BaseHandlingOrder.<com.intermarche.backoffice.domain.wms.BaseHandlingOrder>list("store",store).stream().filter(o -> !before.contains(o.id)).map(o -> Map.of("id",o.id,"code",o.code)).toList();
        return Map.of("createdOrders",created,"message",created.isEmpty()?"Règles évaluées : aucun nouvel ordre nécessaire.":created.size()+" ordre(s) de manutention créé(s).");
    }
    public static class DraftRequest {public Long storeId;public String category;public String prompt;}
    /**
     * Asks the AI assistant to translate a description into a draft rule of the routing or handling category, validated
     * against the rule schemas of the store; the draft is returned, never saved. One retry is made with the validation
     * error when the first draft is invalid.
     *
     * @param request store, category and description of the rule
     * @return the draft rule with an explanation, or the reason why the rule is not supported
     * @throws BadRequestException if the request, the category or the description is invalid
     * @throws ServiceUnavailableException if the AI assistant is not configured
     * @throws WebApplicationException (429) if the user asked for too many drafts recently
     * @throws WebApplicationException (502) if the assistant fails to produce a draft
     */
    @POST @Path("/draft")
    public Map<String,Object> draft(DraftRequest request) {
        if(request==null) throw new BadRequestException("Description requise");
        Store store=scoped(request.storeId);
        if(!Set.of("FULFILLER","ORDER_HANDLER","OPERATIONAL_POLICY").contains(request.category==null?"":request.category)) throw new BadRequestException("Catégorie inconnue");
        if(request.prompt==null || request.prompt.isBlank() || request.prompt.length()>6000) throw new BadRequestException("Décrivez la règle en 1 à 6000 caractères");
        if(!client.isConfigured()) throw new ServiceUnavailableException("L’IA n’est pas configurée : renseignez ai.anthropic.api-key côté serveur");
        if(!quota.allow(identity.getPrincipal().getName())) throw new WebApplicationException(jakarta.ws.rs.core.Response.status(429).entity(Map.of("error","Trop de brouillons demandés : réessayez dans une heure")).type("application/json").build());
        String system="Tu traduis une demande en une règle de logistique magasin. Réponds uniquement en JSON. "
            +"Contrat : {\"status\":\"ok\",\"explanation\":\"explication française\",\"rule\":{\"type\":...,\"name\":...,\"priority\":10,...champs du schéma}} "
            +"ou {\"status\":\"impossible\",\"message\":\"raison et informations manquantes\"}. Aucun paramètre inventé. "
            +"Si la demande porte sur une fonctionnalité absente des schémas, réponds impossible. Pour le réachalandage, utilise Replenishment. Tous ses paramètres sont configurables ; si un paramètre n’est pas précisé, propose une valeur prudente et explique tes hypothèses. Ne prétends jamais avoir enregistré ou créé la règle : tu remplis uniquement un brouillon. "
            +"Ne donne jamais de code exécutable. Le magasin autorisé est "+store.code+". Bâtiments disponibles : "
            +storeContext(store)+". Une politique OPERATIONAL_POLICY est une contrainte d’exécution, jamais un déclencheur de mission. Elle doit être simulée puis activée explicitement par un responsable. Schémas autorisés : "+registry.definitions().stream().filter(d -> d.category().equals(request.category)).toList();
        String prompt=request.prompt;
        for(int attempt=0;attempt<2;attempt++) {
            try {
                String reply=client.complete(system,prompt).trim().replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
                JsonNode result=mapper.readTree(reply);
                if("impossible".equals(result.path("status").asText())) return Map.of("status","impossible","message",result.path("message").asText("Règle non prise en charge"));
                if(!"ok".equals(result.path("status").asText())) throw new IllegalArgumentException("Contrat de réponse non respecté");
                registry.validate(request.category,result.get("rule"),List.of(store));
                return Map.of("status","ok","rule",result.get("rule"),"explanation",result.path("explanation").asText("Brouillon à vérifier avant enregistrement"));
            } catch(IllegalArgumentException e) {prompt=request.prompt+"\nCorrige cette erreur : "+e.getMessage();}
            catch(Exception e) {throw new WebApplicationException("L’IA n’a pas pu produire le brouillon. Réessayez.",502);}
        }
        return Map.of("status","error","message","La proposition IA ne respecte pas les règles du moteur. Précisez votre demande.");
    }
}
