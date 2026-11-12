package com.intermarche.e2e.rules;

import com.intermarche.backoffice.auth.DemoDataInitializer;
import com.intermarche.backoffice.rules.*;
import com.intermarche.backoffice.domain.Configuration;
import com.intermarche.backoffice.domain.store.*;
import com.intermarche.backoffice.graphql.ConfigurationResource;
import com.intermarche.e2e.domain.util.DomainUtils;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.InjectMock;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.ws.rs.ForbiddenException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@QuarkusTest
@io.quarkus.test.junit.TestProfile(StoreRulesTest.IsolatedDemoProfile.class)
@TestTransaction
@TestSecurity(user="100234",roles="MANAGER")
public class StoreRulesTest {
    /** The demo fixture needs an empty catalogue; unrelated workflow fixtures must not suppress it. */
    public static class IsolatedDemoProfile implements io.quarkus.test.junit.QuarkusTestProfile {
        public java.util.Map<String,String> getConfigOverrides() { return java.util.Map.of("quarkus.datasource.jdbc.url", "jdbc:h2:mem:store-rules-isolated;DB_CLOSE_DELAY=-1"); }
    }
    @Inject DemoDataInitializer seed;
    @Inject RuleDraftResource rules;
    @Inject ConfigurationResource configs;
    @Inject RuleSchemaRegistry schemas;
    @Inject ObjectMapper mapper;
    @Inject jakarta.persistence.EntityManager em;
    @InjectMock AnthropicMessagesClient ai;
    @Test void ownStoreOnly_andNoCrossStoreMutationOrGroupExpansion() throws Exception {
        seed.seedDemoData();Store own=Store.findByCode("LYO-042"),other=DomainUtils.createAndPersistStore("RULE-OTHER",48.0,2.0);
        assertEquals(1,rules.stores().size());
        assertEquals(own.id,rules.catalog(null).get("storeId"));
        assertThrows(ForbiddenException.class,() -> rules.catalog(other.id));
        assertThrows(SecurityException.class,() -> configs.configurationsByStore(other.id));
        Configuration foreign=new Configuration();foreign.name="Foreign";foreign.type="ORDER_HANDLER";foreign.specification="[]";foreign.stores.add(other);foreign.persistAndFlush();
        var input=new ConfigurationResource.ConfigurationRecord();input.specification="[]";
        assertThrows(Exception.class,() -> configs.updateConfiguration(foreign.id,input));
        assertThrows(Exception.class,() -> configs.deleteConfiguration(foreign.id));
        assertEquals("Foreign",foreign.name);
    }
    @Test void managerCreatesOwnValidatedRules_andCannotAssignAnotherStore() throws Exception {
        seed.seedDemoData();Store own=Store.findByCode("LYO-042");
        var input=new ConfigurationResource.ConfigurationRecord();input.name="Local";input.type="ORDER_HANDLER";input.priority=30;
        input.specification="[{\"type\":\"Packing\",\"validityDelayMinutes\":15,\"groupingWindowMinutes\":30,\"deadlineDelayMinutes\":60}]";
        var created=configs.createConfiguration(input);assertEquals(1,created.stores.size());assertEquals(own.id,created.stores.iterator().next().id);
        var invalid=new ConfigurationResource.ConfigurationRecord();invalid.type="ORDER_HANDLER";invalid.storeIds=List.of(99999L);invalid.specification="[]";
        assertThrows(Exception.class,() -> configs.createConfiguration(invalid));
    }
    @Test void seededRulesPassEngineValidationAndPrioritiesAreSerializedInExecutionOrder() throws Exception {
        seed.seedDemoData();Store own=Store.findByCode("LYO-042");
        for(String category:List.of("FULFILLER","ORDER_HANDLER")) for(Configuration config:Configuration.findConfigurationsForStore(own,category)) schemas.validateSpecification(category,config.specification,List.of(own));
        String sorted=schemas.normalize("FULFILLER","[{\"type\":\"Default\",\"priority\":1},{\"type\":\"Default\",\"priority\":20}]");
        assertEquals(20,mapper.readTree(sorted).get(0).path("priority").asInt());
    }
    @Test void savingPackingDraftRevalidatesExistingGroupingAfterPersistenceContextIsCleared() throws Exception {
        seed.seedDemoData();
        Configuration parent=Configuration.find("name","Manutention standard").firstResult();
        Long id=parent.id;
        var rulesArray=(com.fasterxml.jackson.databind.node.ArrayNode)mapper.readTree(parent.specification);
        rulesArray.add(mapper.readTree("{\"type\":\"Packing\",\"name\":\"Emballage rapide\",\"priority\":40,\"validityDelayMinutes\":15,\"groupingWindowMinutes\":30,\"deadlineDelayMinutes\":60}"));
        var input=new ConfigurationResource.ConfigurationRecord();input.specification=mapper.writeValueAsString(rulesArray);
        em.flush();em.clear();
        var saved=configs.updateConfiguration(id,input);
        em.flush();em.clear();
        Configuration persisted=Configuration.findById(id);
        var result=mapper.readTree(persisted.specification);
        assertEquals(3,result.size());assertEquals("Emballage rapide",result.get(0).path("name").asText());
        assertEquals(40,result.get(0).path("priority").asInt());
    }
    @Test void schemaRejectsUnknownFieldsAndForeignBuildings() throws Exception {
        seed.seedDemoData();Store own=Store.findByCode("LYO-042");
        assertThrows(IllegalArgumentException.class,() -> schemas.validate("ORDER_HANDLER",mapper.readTree("{\"type\":\"Packing\",\"invented\":1}"),List.of(own)));
        assertThrows(IllegalArgumentException.class,() -> schemas.validate("FULFILLER",mapper.readTree("{\"type\":\"Default\",\"buildingCodes\":[\"FOREIGN\"]}"),List.of(own)));
    }
    @Test void aiOnlyDraftsAndValidatesBeforeOfferingProposal() throws Exception {
        seed.seedDemoData();when(ai.isConfigured()).thenReturn(true);
        when(ai.complete(anyString(),anyString())).thenReturn("{\"status\":\"ok\",\"explanation\":\"Répartition locale\",\"rule\":{\"type\":\"Default\",\"name\":\"Local\",\"priority\":10,\"buildingCodes\":[\"LYO-042-SHOP\"]}}");
        long before=Configuration.count();var request=new RuleDraftResource.DraftRequest();request.category="FULFILLER";request.prompt="Répartir dans le magasin";
        assertEquals("ok",rules.draft(request).get("status"));assertEquals(before,Configuration.count());
        when(ai.complete(anyString(),anyString())).thenReturn("{\"status\":\"ok\",\"rule\":{\"type\":\"Invented\"}}");
        assertEquals("error",rules.draft(request).get("status"));assertEquals(before,Configuration.count());
    }
    @Test void aiOperationalPolicyRemainsADraftAndCannotUseTheMissionConfigurationEndpoint() throws Exception {
        seed.seedDemoData();when(ai.isConfigured()).thenReturn(true);
        when(ai.complete(anyString(),anyString())).thenReturn("{\"status\":\"ok\",\"rule\":{\"type\":\"ParcelConstraints\",\"requireWeight\":true,\"requireDimensions\":false,\"requireContentConfirmation\":true,\"requireScan\":true}}");
        long before=Configuration.count();var input=new RuleDraftResource.DraftRequest();input.category="OPERATIONAL_POLICY";input.prompt="Exiger le scan et le poids des colis";
        assertEquals("ok",rules.draft(input).get("status"));assertEquals(before,Configuration.count());
        assertThrows(IllegalArgumentException.class,()->schemas.validateSpecification("OPERATIONAL_POLICY","[{\"type\":\"ParcelConstraints\",\"requireWeight\":true,\"requireDimensions\":false,\"requireContentConfirmation\":true,\"requireScan\":true}]",List.of(Store.findByCode("LYO-042"))));
    }
    /** Checks that each user has a quota of drafts of its own, refilled when the window passes. */
    @Test void draftQuotaIsPerUserAndRefillsWithTheWindow() {
        var clock=new java.util.concurrent.atomic.AtomicReference<>(java.time.Instant.parse("2026-10-30T10:00:00Z"));
        var quota=new RuleDraftQuota(2,60,new java.time.Clock(){
            /** @return UTC */ @Override public java.time.ZoneId getZone(){return java.time.ZoneOffset.UTC;}
            /** @param zone ignored @return this clock */ @Override public java.time.Clock withZone(java.time.ZoneId zone){return this;}
            /** @return the controlled instant */ @Override public java.time.Instant instant(){return clock.get();}
        });
        assertTrue(quota.allow("alice"));assertTrue(quota.allow("alice"));assertFalse(quota.allow("alice"));
        assertTrue(quota.allow("bob"));assertTrue(quota.allow(null));
        clock.set(clock.get().plusSeconds(3601));
        assertTrue(quota.allow("alice"));
    }
    /** Checks that a manager over the draft quota gets a 429 without the assistant being called. */
    @Test void draftOverQuotaIsRefusedBeforeCallingTheAssistant() throws Exception {
        seed.seedDemoData();when(ai.isConfigured()).thenReturn(true);
        var field=RuleDraftResource.class.getDeclaredField("quota");field.setAccessible(true);
        var original=field.get(rules);
        try {
            field.set(rules,new RuleDraftQuota(0,60,java.time.Clock.systemUTC()));
            var request=new RuleDraftResource.DraftRequest();request.category="FULFILLER";request.prompt="Répartir dans le magasin";
            var refused=assertThrows(jakarta.ws.rs.WebApplicationException.class,()->rules.draft(request));
            assertEquals(429,refused.getResponse().getStatus());
            verify(ai,never()).complete(anyString(),anyString());
        } finally { field.set(rules,original); }
    }
}
