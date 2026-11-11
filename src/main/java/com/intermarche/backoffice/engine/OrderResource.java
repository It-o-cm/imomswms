package com.intermarche.backoffice.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
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

import java.util.HashSet;
import java.util.List;
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

    /**
     * Security identity of the calling integration account.
     */
    @Inject
    io.quarkus.security.identity.SecurityIdentity identity;

    /**
     * Tells whether the caller may submit orders for a store: administrators for any store, an integration account
     * for the store it is granted.
     *
     * @param store the store addressed by the order
     * @return true when allowed
     */
    boolean mayOrderFor(Store store) {
        if (identity.hasRole("ADMIN")) return true;
        try {
            return com.intermarche.backoffice.graphql.GraphQLTrait.serviceStore(identity.getPrincipal().getName()).id.equals(store.id);
        } catch (SecurityException e) {
            return false;
        }
    }

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
                },
                "payment": { "$ref": "#/definitions/Payment" },
                "catering": { "$ref": "#/definitions/CateringRoot" }
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
                    "logistics": { "$ref": "#/definitions/Logistics" },
                    "catering": { "$ref": "#/definitions/CateringLine" }
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
                    "temperatureZone": { "type": "string", "enum": ["AMBIENT", "CHILLED", "FROZEN", "HOUSEHOLD", "FRESH"] }
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
                },
                "Payment": {
                  "type": "object",
                  "properties": {
                    "method": { "type": "string", "enum": ["ONLINE", "AT_PICKUP"] },
                    "state": { "type": "string", "enum": ["DUE", "COLLECTED"] },
                    "amount": { "type": "number", "minimum": 0 }
                  }
                },
                "CateringRoot": {
                  "type": "object",
                  "properties": {
                    "contractVersion": { "type": "string" },
                    "reception": {
                      "type": "object",
                      "properties": {
                        "date": { "type": "string", "format": "date" },
                        "guests": { "type": "integer", "minimum": 1, "maximum": 1000 }
                      }
                    },
                    "deadlines": {
                      "type": "object",
                      "properties": {
                        "change": { "type": "string", "format": "date-time" },
                        "cancel": { "type": "string", "format": "date-time" }
                      }
                    },
                    "pickupPoint": { "type": "string", "maxLength": 200 }
                  },
                  "required": ["contractVersion"]
                },
                "CateringLine": {
                  "type": "object",
                  "properties": {
                    "lineId": { "type": "string" },
                    "variant": {
                      "type": "object",
                      "properties": {
                        "id": { "type": "string", "pattern": "^[A-Za-z0-9-]{1,20}$" },
                        "label": { "type": "string", "maxLength": 60 },
                        "persons": { "type": "integer" },
                        "pieces": { "type": "integer" },
                        "parts": { "type": "integer" },
                        "weightGrams": { "type": "number" }
                      },
                      "required": ["id"]
                    },
                    "configurationKey": { "type": "string", "pattern": "^[0-9a-fA-F]{64}$" },
                    "configuration": { "type": "object" },
                    "choiceLabels": { "type": "array", "maxItems": 40, "items": { "type": "string", "maxLength": 60 } },
                    "allergens": { "type": "string", "maxLength": 4000 },
                    "ingredients": { "type": "string", "maxLength": 4000 },
                    "storage": { "type": "string", "maxLength": 1000 },
                    "servingAdvice": { "type": "string", "maxLength": 1000 }
                  },
                  "required": ["lineId", "variant"]
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
            // (never fatal: the store is unknown here, and Event.store is mandatory)
            publishEventSafely(
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
        if (store != null && !mayOrderFor(store)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity("{\"error\": \"Store not granted to this integration account.\"}")
                    .build();
        }

        // 3. Validate Schema
        Response validationResponse = validateSchema(rootNode, rawPayload, fallbackTarget, store);
        if (validationResponse != null) {
            return validationResponse;
        }

        // 3b. Validate catering lines and at-pickup payment (I1, I3, § 4.1)
        Response cateringResponse = validateCateringAndPayment(rootNode, rawPayload, fallbackTarget, store);
        if (cateringResponse != null) {
            return cateringResponse;
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
            publishEventSafely(
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
        // (JsonNode.asText() never returns null: a missing node yields "")
        if (orderNumber.trim().isEmpty() || storeCode.trim().isEmpty()) {
            LOGGER.warnf("Missing mandatory fields in payload: orderId=[%s], storeCode=[%s]", orderNumber, storeCode);

            // Emit Rejection Event in an independent transaction
            publishEventSafely(
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

        // 2b. Idempotency by (storeCode, orderId) — rule I4
        List<OrderRequest> existing = OrderRequest.list("storeCode = ?1 and orderNumber = ?2", storeCode, orderNumber);
        if (!existing.isEmpty()) {
            String fingerprint = canonicalFingerprint(rawPayload);
            boolean identical = existing.stream().anyMatch(r -> fingerprint.equals(canonicalFingerprint(r.specification)));
            if (identical) {
                // Identical replay: accept without creating a second OrderRequest.
                return Response.accepted()
                        .entity("{\"message\": \"Order already submitted (idempotent replay).\"}")
                        .build();
            }
            // Different payload under the same order id.
            publishEventSafely(
                    "Commande Rejetée (Déjà Soumise)",
                    "Une commande différente existe déjà sous cet identifiant (storeCode=" + storeCode + ", orderId=" + orderNumber + ").\n\n--- PAYLOAD BRUT REÇU ---\n" + rawPayload,
                    Event.UrgencyLevel.MEDIUM,
                    store,
                    fallbackTarget,
                    orderNumber
            );
            return Response.status(Response.Status.CONFLICT)
                    .entity("{\"error\": \"ORDER_ALREADY_SUBMITTED\"}")
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
            publishEventSafely(
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
     * Emits an anomaly event in an independent transaction without ever failing the request.
     * <p>
     * Event emission must never change the HTTP outcome: when the event cannot be persisted
     * (e.g. the store is unknown while {@code Event.store} is mandatory), the error is logged
     * and swallowed so the endpoint can still return its intended response.
     *
     * @param label            The event label.
     * @param description      The event description.
     * @param urgencyLevel     The event urgency level.
     * @param store            The associated store (may be null).
     * @param targetEntity     The entity instance triggering the event.
     * @param targetEntityCode The functional business code of the entity triggering the event.
     */
    private void publishEventSafely(String label, String description, Event.UrgencyLevel urgencyLevel,
                                    Store store, Object targetEntity, String targetEntityCode) {
        try {
            Event.publishInNewTx(label, description, urgencyLevel, store, targetEntity, targetEntityCode);
        } catch (RuntimeException e) {
            LOGGER.error("Failed to publish anomaly event '" + label + "'", e);
        }
    }

    /**
     * Validates the semantic rules of the catering lines and the at-pickup payment that the
     * JSON Schema cannot express (rules I1, I3 and § 4.1).
     * <p>
     * A line is a catering line if and only if it carries a {@code catering} object (I1). Every
     * catering line requires the root {@code catering} block, a unique {@code lineId}, and a
     * {@code configuration} that is either a composition or a cake (I3). An {@code AT_PICKUP}
     * payment requires {@code state} and {@code amount} (§ 4.1).
     *
     * @param root       the parsed payload.
     * @param rawPayload the raw payload, included in the rejection event.
     * @param target     the fallback target for the event.
     * @param store      the associated store (may be null).
     * @return a 400 response if a rule is violated, or null when all rules pass.
     */
    private Response validateCateringAndPayment(JsonNode root, String rawPayload, OrderRequest target, Store store) {
        JsonNode payment = root.path("payment");
        if (payment.isObject() && "AT_PICKUP".equals(payment.path("method").asText())) {
            if (!payment.hasNonNull("state") || !payment.hasNonNull("amount")) {
                return cateringReject(rawPayload, target, store, "PAYMENT_INCOMPLETE",
                        "payment.state et payment.amount sont obligatoires lorsque method = AT_PICKUP");
            }
        }
        boolean hasCateringLine = false;
        Set<String> lineIds = new HashSet<>();
        for (JsonNode line : root.path("lines")) {
            JsonNode catering = line.path("catering");
            if (!catering.isObject()) {
                continue;
            }
            hasCateringLine = true;
            String lineId = catering.path("lineId").asText();
            if (!lineIds.add(lineId)) {
                return cateringReject(rawPayload, target, store, "CATERING_LINE_INVALID",
                        "lineId en double : " + lineId);
            }
            if (!catering.hasNonNull("configurationKey") || catering.path("configurationKey").asText().isBlank()) {
                return cateringReject(rawPayload, target, store, "CATERING_LINE_INVALID",
                        "configurationKey absente (lineId " + lineId + ")");
            }
            JsonNode configuration = catering.path("configuration");
            if (configuration.isObject()) {
                boolean composition = configuration.path("choices").isObject();
                boolean cake = configuration.hasNonNull("shapeId");
                if (!composition && !cake) {
                    return cateringReject(rawPayload, target, store, "CATERING_LINE_INVALID",
                            "configuration n'est ni une composition ni un gâteau (lineId " + lineId + ")");
                }
            }
        }
        if (hasCateringLine && !root.path("catering").isObject()) {
            return cateringReject(rawPayload, target, store, "CATERING_LINE_INVALID",
                    "une ligne traiteur exige le bloc catering racine");
        }
        return null;
    }

    /**
     * Emits a rejection event and builds a 400 response for an invalid catering or payment block.
     *
     * @param rawPayload the raw payload.
     * @param target     the event target.
     * @param store      the associated store (may be null).
     * @param code       the business error code.
     * @param detail     the human-readable detail.
     * @return a 400 response carrying the code and detail.
     */
    private Response cateringReject(String rawPayload, OrderRequest target, Store store, String code, String detail) {
        publishEventSafely(
                "Commande Rejetée (Schéma Invalide)",
                "Bloc traiteur ou paiement invalide : " + detail + "\n\n--- PAYLOAD BRUT REÇU ---\n" + rawPayload,
                Event.UrgencyLevel.MEDIUM,
                store,
                target,
                target.orderNumber
        );
        return Response.status(Response.Status.BAD_REQUEST)
                .entity("{\"error\": \"" + code + "\", \"details\": \"" + detail.replace("\"", "\\\"") + "\"}")
                .build();
    }

    /**
     * Computes a key-order-independent SHA-256 fingerprint of a JSON payload (rule I4).
     * <p>
     * The payload is parsed, re-serialized with map entries sorted by key, then hashed, so that
     * two payloads that differ only by JSON key order produce the same fingerprint.
     *
     * @param payload the raw JSON payload.
     * @return the hexadecimal fingerprint, or the raw payload if it cannot be parsed.
     */
    private String canonicalFingerprint(String payload) {
        try {
            Object tree = objectMapper.treeToValue(objectMapper.readTree(payload), Object.class);
            String canonical = objectMapper.writer()
                    .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsString(tree);
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return payload == null ? "" : payload;
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
        // Event.targetEntityCode is @NotBlank: a blank business code must fall back to a placeholder
        // (callers pass JsonNode.asText() results, which are never null)
        OrderRequest fallback = new OrderRequest(
                orderNumber.isBlank() ? "INCONNU" : orderNumber,
                storeCode.isBlank() ? "INCONNU" : storeCode,
                ""
        );
        fallback.id = -1L; // Dummy ID to satisfy Event.targetEntityId @NotNull constraint
        return fallback;
    }

}