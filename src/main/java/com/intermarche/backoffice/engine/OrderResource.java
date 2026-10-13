package com.intermarche.backoffice.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intermarche.backoffice.domain.Event;
import com.intermarche.backoffice.domain.order.OrderRequest;
import com.intermarche.backoffice.domain.store.Store;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * REST Endpoint to receive retail orders.
 * <p>
 * Validates payload against an embedded JSON Schema and persists it as an {@link OrderRequest}.
 * Emits highly detailed {@link Event} instances to track acceptance, rejection, or processing failures.
 * In case of rejection, the event description includes the raw payload to facilitate anomaly investigation.
 * <p>
 * Access is restricted to the 'SERVICE_CALLER' role.
 */
@Path("/orders")
@ApplicationScoped
@RunOnVirtualThread
public class OrderResource {

    static final Logger LOGGER = Logger.getLogger(OrderResource.class);

    // --------------------------------------------------
    // Embedded JSON Schema
    // --------------------------------------------------
    static final String RETAIL_ORDER_SCHEMA = """
            {
              "$schema": "http://json-schema.org/draft-07/schema#",
              "title": "RetailOrder",
              "description": "Specification for a retail food e-commerce order.",
              "type": "object",
              "properties": {
                "orderHeader": {
                  "type": "object",
                  "properties": {
                    "orderId": { "type": "string" },
                    "createdAt": { "type": "string", "format": "date-time" },
                    "channel": { "type": "string" },
                    "status": { "type": "string" },
                    "store": { "$ref": "#/definitions/StoreReference" }
                  },
                  "required": ["orderId", "createdAt", "store"]
                },
                "customer": {
                  "type": "object",
                  "properties": {
                    "customerId": { "type": "string" },
                    "firstName": { "type": "string" },
                    "lastName": { "type": "string" },
                    "email": { "type": "string", "format": "email" },
                    "phone": { "type": "string" },
                    "loyaltyCardId": { "type": "string" },
                    "address": { "$ref": "#/definitions/Address" }
                  },
                  "required": ["customerId", "firstName", "lastName", "address"]
                },
                "fulfillment": {
                  "type": "object",
                  "properties": {
                    "type": { "type": "string", "enum": ["HOME_DELIVERY", "CLICK_AND_COLLECT", "DRIVE"] },
                    "slot": {
                      "type": "object",
                      "properties": {
                        "start": { "type": "string", "format": "date-time" },
                        "end": { "type": "string", "format": "date-time" }
                      }
                    }
                  }
                },
                "packaging": {
                  "type": "object",
                  "properties": {
                    "description": { "type": "string" },
                    "supports": {
                      "type": "array",
                      "items": {
                        "type": "object",
                        "properties": {
                          "type": { "type": "string" },
                          "name": { "type": "string" },
                          "quantity": { "type": "integer" },
                          "purpose": { "type": "string" },
                          "unitDepositFee": { "type": "number" },
                          "totalDepositFee": { "type": "number" }
                        }
                      }
                    },
                    "summary": {
                      "type": "object",
                      "properties": {
                        "totalDepositFee": { "type": "number" },
                        "instructions": { "type": "string" }
                      }
                    }
                  }
                },
                "lines": {
                  "type": "array",
                  "items": { "$ref": "#/definitions/OrderLine" }
                },
                "summary": {
                  "type": "object",
                  "properties": {
                    "totalItems": { "type": "integer" },
                    "totalPriceExclTax": { "type": "number" },
                    "totalTax": { "type": "number" },
                    "totalPriceInclTax": { "type": "number" },
                    "logisticsTotals": { "$ref": "#/definitions/LogisticsSummary" }
                  }
                }
              },
              "required": ["orderHeader", "customer", "fulfillment", "lines", "summary"],
              "definitions": {
                "StoreReference": {
                  "type": "object",
                  "properties": {
                    "id": { "type": "integer" },
                    "code": { "type": "string" },
                    "name": { "type": "string" },
                    "address": { "$ref": "#/definitions/Address" }
                  }
                },
                "Address": {
                  "type": "object",
                  "properties": {
                    "streetLine1": { "type": "string" },
                    "streetLine2": { "type": "string" },
                    "postalCode": { "type": "string" },
                    "city": { "type": "string" },
                    "country": { "type": "string" },
                    "latitude": { "type": "number" },
                    "longitude": { "type": "number" },
                    "deliveryInstructions": { "type": "string" }
                  }
                },
                "OrderLine": {
                  "type": "object",
                  "properties": {
                    "lineNumber": { "type": "integer" },
                    "product": { "$ref": "#/definitions/ProductReference" },
                    "requestedQuantity": { "type": "number" },
                    "pricing": { "$ref": "#/definitions/Pricing" },
                    "offer": { "$ref": "#/definitions/Offer" },
                    "instructions": { "type": "object" },
                    "logistics": { "$ref": "#/definitions/Logistics" }
                  }
                },
                "ProductReference": {
                  "type": "object",
                  "properties": {
                    "ean": { "type": "string" },
                    "name": { "type": "string" },
                    "productType": { "type": "string", "enum": ["UNIT", "WEIGHT", "VOLUME"] },
                    "referenceWeight": { "type": "number" },
                    "referenceVolume": { "type": "number" },
                    "unitName": { "type": "string" }
                  }
                },
                "Pricing": {
                  "type": "object",
                  "properties": {
                    "basePrice": { "type": "number" },
                    "unitPrice": { "type": "number" },
                    "totalPrice": { "type": "number" },
                    "discountAmount": { "type": "number" }
                  }
                },
                "Offer": {
                  "type": "object",
                  "properties": {
                    "code": { "type": ["string", "null"] },
                    "type": { "type": "string" },
                    "description": { "type": "string" },
                    "funding": { 
                        "type": "string", 
                        "enum": ["STORE", "BRAND", "JOINT", "LOYALTY_PROGRAM", "STATE_SUBSIDIZED"] 
                    },
                    "mechanics": { "type": "object" },
                    "validity": { "type": "string" }
                  }
                },
                "Logistics": {
                  "type": "object",
                  "properties": {
                    "weight": { "type": "number" },
                    "volume": { "type": "number" },
                    "fragile": { "type": "boolean" },
                    "temperatureZone": { "type": "string", "enum": ["AMBIENT", "FRESH", "FROZEN", "HOT"] }
                  }
                },
                "LogisticsSummary": {
                  "type": "object",
                  "properties": {
                    "totalWeight": { "type": "number" },
                    "totalVolume": { "type": "number" },
                    "requiresTemperatureControl": { "type": "boolean" },
                    "temperatureZones": { "type": "array", "items": { "type": "string" } },
                    "containsFragileItems": { "type": "boolean" }
                  }
                }
              }
            }
            """;

    /**
     * Jackson ObjectMapper for parsing JSON payloads.
     * Injected by the container and used for both schema validation and extracting fields.
     */
    @Inject
    ObjectMapper objectMapper;

    /**
     * The compiled JSON Schema instance used for validating incoming orders.
     * Initialized once at startup from the embedded constant string.
     */
    JsonSchema jsonSchema;

    /**
     * Initializes the JSON Schema validator from the embedded constant string.
     * <p>
     * This method is called once upon bean instantiation.
     */
    @PostConstruct
    void initSchema() {
        try {
            JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
            this.jsonSchema = factory.getSchema(RETAIL_ORDER_SCHEMA);
            LOGGER.info("JSON Schema initialized successfully from embedded constant.");
        } catch (Exception e) {
            LOGGER.error("Failed to initialize JSON Schema", e);
            throw new RuntimeException("Failed to initialize JSON Schema", e);
        }
    }

    /**
     * Receives a Retail Order payload, validates it against the JSON Schema, and persists it as an OrderRequest.
     * <p>
     * <b>Security:</b> Only users with the 'SERVICE_CALLER' role can access this endpoint.
     * <p>
     * <b>Validation & Anomaly Tracking:</b>
     * If the payload is malformed or invalid, the endpoint rejects the request (HTTP 400/500) but emits an
     * {@link Event} in an independent transaction. This event captures the raw payload and specific parsing
     * or validation error messages to facilitate immediate debugging by technical support.
     * <p>
     * <b>Process:</b>
     * 1. Parse JSON payload (catches raw formatting errors like missing brackets).
     * 2. Safely extract 'orderId' and 'store.code' for event tracing (even if subsequent steps fail).
     * 3. Validate against the structural JSON Schema.
     * 4. Extract and validate mandatory business fields (orderId, storeCode).
     * 5. Persist a new {@link OrderRequest} entity with status PENDING and emit a Success event.
     *
     * @param rawPayload The raw JSON string received from the client.
     * @return 202 Accepted if validation passes and entity is persisted.
     *         400 Bad Request if the JSON is malformed, schema validation fails, or required fields are missing.
     *         500 Internal Server Error if persistence fails.
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed("SERVICE_CALLER")
    @Transactional
    public Response submitOrder(String rawPayload) {
        LOGGER.info("Received order submission from authorized service");

        // 1. Parse JSON
        JsonNode rootNode;
        try {
            rootNode = objectMapper.readTree(rawPayload);
        } catch (Exception e) {
            LOGGER.error("Invalid JSON payload", e);

            // Emit explicit anomaly event in an independent transaction to guarantee persistence
            Event.publishInNewTx(
                    "Format JSON Invalide Intercepté",
                    "Le payload reçu n'est pas un JSON valide (erreur de syntaxe, guillemets manquants, etc.). Cause technique: " + e.getMessage() + "\n\n--- PAYLOAD BRUT REÇU ---\n" + rawPayload,
                    Event.UrgencyLevel.HIGH,
                    null,
                    createFallbackTarget("JSON_INVALID", "UNKNOWN"),
                    "JSON_INVALID"
            );

            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\": \"Malformed JSON payload.\"}")
                    .build();
        }

        // 2. Safely extract basic identifiers for Event tracing (in case subsequent steps fail)
        String orderNumber = rootNode.path("orderHeader").path("orderId").asText("INCONNU");
        String storeCode = rootNode.path("orderHeader").path("store").path("code").asText("INCONNU");
        Store store = storeCode.equals("INCONNU") ? null : Store.findByCode(storeCode);
        OrderRequest fallbackTarget = createFallbackTarget(orderNumber, storeCode);

        // 3. Validate Schema
        Response validationResponse = validateSchema(rootNode, rawPayload, fallbackTarget, store);
        if (validationResponse != null) {
            return validationResponse;
        }

        // 4. Process and Persist Entity (Isolated Logic)
        Response processingResponse = processOrderRequest(rootNode, rawPayload, fallbackTarget, store);
        if (processingResponse != null) {
            return processingResponse;
        }

        return Response.accepted()
                .entity("{\"message\": \"Order accepted for processing.\"}")
                .build();
    }

    /**
     * Validates the parsed JSON payload against the embedded JSON Schema.
     * <p>
     * If validation fails, emits an event in a new transaction. The event description explicitly lists
     * all schema violations and appends the raw payload so the exact point of failure can be identified.
     *
     * @param rootNode       The parsed JsonNode.
     * @param rawPayload    The original raw JSON string, included in the event for debugging.
     * @param targetEntity   A fallback entity instance to link to the event if validation fails.
     * @param store          The store associated with the request.
     * @return A Response with status 400 if validation fails, or null if validation passes.
     */
    private Response validateSchema(JsonNode rootNode, String rawPayload, OrderRequest targetEntity, Store store) {
        Set<ValidationMessage> errors = jsonSchema.validate(rootNode);
        if (!errors.isEmpty()) {
            // Format errors clearly for the event description (one per line)
            String formattedErrors = errors.stream()
                    .map(ValidationMessage::getMessage)
                    .map(msg -> " - " + msg)
                    .collect(Collectors.joining("\n"));

            LOGGER.warn("Schema validation failed: " + formattedErrors);

            // Emit Rejection Event in an independent transaction
            Event.publishInNewTx(
                    "Commande Rejetée (Schéma Invalide)",
                    "Le JSON est syntaxiquement correct mais ne respecte pas le schéma RetailOrder attendu.\n\nDétails des erreurs de validation:\n" + formattedErrors + "\n\n--- PAYLOAD BRUT REÇU ---\n" + rawPayload,
                    Event.UrgencyLevel.MEDIUM,
                    store,
                    targetEntity,
                    targetEntity.orderNumber
            );

            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\": \"Schema validation failed\", \"details\": \"" + formattedErrors.replace("\"", "\\\"") + "\"}")
                    .build();
        }
        return null;
    }

    /**
     * Extracts mandatory fields from the JSON payload and persists the OrderRequest entity.
     * <p>
     * Handles validation of business keys (orderId, storeCode) and database persistence errors.
     * Emits appropriate events for Success or Processing Failure, including raw payloads on failure.
     *
     * @param rootNode       The validated JSON payload as a Tree node.
     * @param rawPayload    The raw JSON string to be stored in the specification field.
     * @param fallbackTarget A fallback entity to use for events if persistence fails.
     * @param store          The store associated with the request.
     * @return A {@link Response} object with an error status if processing fails.
     *         Returns {@code null} if the entity is successfully persisted.
     */
    private Response processOrderRequest(JsonNode rootNode, String rawPayload, OrderRequest fallbackTarget, Store store) {
        // 1. Extract fields required for the Entity
        String orderNumber = rootNode.path("orderHeader").path("orderId").asText();
        String storeCode = rootNode.path("orderHeader").path("store").path("code").asText();

        // 2. Validate mandatory fields
        if (orderNumber == null || orderNumber.trim().isEmpty() || storeCode == null || storeCode.trim().isEmpty()) {
            LOGGER.warnf("Missing mandatory fields in payload: orderId=[%s], storeCode=[%s]", orderNumber, storeCode);

            // Emit Rejection Event in an independent transaction
            Event.publishInNewTx(
                    "Commande Rejetée (Données Manquantes)",
                    "Le schéma est valide, mais les champs métier obligatoires sont absents ou vides. " +
                            "Valeurs extraites -> orderId: '" + orderNumber + "', storeCode: '" + storeCode + "'.\n\n--- PAYLOAD BRUT REÇU ---\n" + rawPayload,
                    Event.UrgencyLevel.MEDIUM,
                    store,
                    fallbackTarget,
                    fallbackTarget.orderNumber
            );

            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\": \"Missing mandatory fields: orderHeader.orderId or orderHeader.store.code\"}")
                    .build();
        }

        // 3. Create and Persist Entity
        try {
            OrderRequest orderRequest = new OrderRequest(orderNumber, storeCode, rawPayload);
            orderRequest.persist();
            LOGGER.infof("OrderRequest created with ID %d for order %s", orderRequest.id, orderNumber);

            // Emit Success Event in the CURRENT transaction (committed with the order request)
            Event.publishInCurrentTx(
                    "Commande Acceptée",
                    "Commande validée avec succès (JSON + Schéma + Données) et mise en file d'attente de traitement.",
                    Event.UrgencyLevel.LOW,
                    store,
                    orderRequest,
                    orderNumber
            );

            return null; // Success
        } catch (Exception e) {
            LOGGER.error("Failed to persist OrderRequest", e);

            // Emit Error Event in an INDEPENDENT transaction (current TX is rolling back)
            Event.publishInNewTx(
                    "Erreur Critique de Persistance",
                    "La commande était valide, mais une erreur technique est survenue lors de l'insertion en base de données. " +
                            "Message technique: " + e.getMessage() + "\n\n--- PAYLOAD BRUT REÇU ---\n" + rawPayload,
                    Event.UrgencyLevel.HIGH,
                    store,
                    fallbackTarget,
                    orderNumber
            );

            return Response.serverError()
                    .entity("{\"error\": \"Failed to save order request.\"}")
                    .build();
        }
    }

    /**
     * Creates a fallback {@link OrderRequest} instance to be used as a target for {@link Event} creation
     * when the actual entity fails to validate or persist.
     * <p>
     * Because the {@link Event} entity requires a non-null technical ID for its target, and a rejected
     * order never gets persisted (thus has no generated ID), we assign a dummy ID of {@code -1L}.
     * The functional identifier (orderNumber) is still accurately preserved for traceability in the Event UI.
     *
     * @param orderNumber The functional order ID extracted from the payload.
     * @param storeCode   The store code extracted from the payload.
     * @return A non-persisted OrderRequest with a dummy technical ID.
     */
    private OrderRequest createFallbackTarget(String orderNumber, String storeCode) {
        OrderRequest fallback = new OrderRequest(
                orderNumber != null ? orderNumber : "INCONNU",
                storeCode != null ? storeCode : "INCONNU",
                ""
        );
        fallback.id = -1L; // Dummy ID to satisfy Event.targetEntityId @NotNull constraint
        return fallback;
    }

}