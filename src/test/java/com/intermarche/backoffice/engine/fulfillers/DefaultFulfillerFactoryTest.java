package com.intermarche.backoffice.engine.fulfillers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DefaultFulfillerFactory}.
 * Tests schema validation and configuration parsing without database access.
 */
class DefaultFulfillerFactoryTest {

    private final DefaultFulfillerFactory factory = new DefaultFulfillerFactory();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void getName_shouldReturnDefault() {
        assertEquals("Default", factory.getName());
    }

    @Test
    void build_shouldCreateFulfiller_whenSpecIsEmptyObject() throws Exception {
        JsonNode spec = mapper.readTree("{}");
        BaseFulfillerFactory.BaseFulfiller fulfiller = factory.build(spec);

        assertNotNull(fulfiller);
        assertInstanceOf(DefaultFulfillerFactory.DefaultFulfiller.class, fulfiller);
    }

    @Test
    void build_shouldCreateFulfiller_whenSpecHasBuildingCodes() throws Exception {
        JsonNode spec = mapper.readTree("""
                {"buildingCodes": ["WH01", "STORE01"]}
                """);
        BaseFulfillerFactory.BaseFulfiller fulfiller = factory.build(spec);

        assertNotNull(fulfiller);
        assertInstanceOf(DefaultFulfillerFactory.DefaultFulfiller.class, fulfiller);
    }

    @Test
    void build_shouldCreateFulfiller_whenSpecHasName() throws Exception {
        JsonNode spec = mapper.readTree("""
                {"name": "My Default Fulfiller"}
                """);
        BaseFulfillerFactory.BaseFulfiller fulfiller = factory.build(spec);

        assertNotNull(fulfiller);
    }

    @Test
    void build_shouldThrow_whenSpecIsArray() throws Exception {
        JsonNode spec = mapper.readTree("[]");
        assertThrows(IllegalArgumentException.class, () -> factory.build(spec));
    }

    @Test
    void build_shouldThrow_whenSpecIsString() throws Exception {
        JsonNode spec = mapper.readTree("\"hello\"");
        assertThrows(IllegalArgumentException.class, () -> factory.build(spec));
    }

    @Test
    void build_shouldThrow_whenSpecIsNull() {
        assertThrows(Exception.class, () -> factory.build(null));
    }

    @Test
    void build_shouldCreateFulfiller_whenBuildingCodesIsEmptyArray() throws Exception {
        JsonNode spec = mapper.readTree("""
                {"buildingCodes": []}
                """);
        BaseFulfillerFactory.BaseFulfiller fulfiller = factory.build(spec);

        assertNotNull(fulfiller);
    }

    @Test
    void parseConfiguration_buildingCodesNotArray_returnsEmptyList() throws Exception {
        // The JSON schema rejects a non-array buildingCodes before parseConfiguration
        // runs, so this branch is only reachable by calling the parser directly.
        DefaultFulfillerFactory.DefaultFulfillerConfig config =
                factory.parseConfiguration(mapper.readTree("{\"buildingCodes\": \"WH01\"}"));

        assertNotNull(config);
        assertTrue(config.buildingCodes().isEmpty());
    }
}
