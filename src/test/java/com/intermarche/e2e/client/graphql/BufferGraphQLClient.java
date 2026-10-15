package com.intermarche.e2e.client.graphql;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;

import static com.intermarche.e2e.Authenticate.authenticate;
import com.intermarche.backoffice.domain.store.Building;
import com.intermarche.e2e.Authenticate;

/**
 * Simple HTTP client to test Buffer creation via GraphQL.
 * <p>
 * This class demonstrates how to programmatically send GraphQL mutations
 * to create a list of buffers using Java 11+ HttpClient.
 */
public class BufferGraphQLClient {

    // Configuration
    private static final String BASE_URL = "http://localhost:8080";
    private static final String GRAPHQL_URL = BASE_URL + "/graphql";

    /**
     * Main method to execute buffer creation.
     * <p>
     * Prepares a list of sample buffers and sends a GraphQL mutation
     * for each one using the {@link #GRAPHQL_URL}.
     *
     * @param args Command line arguments (not used).
     */
    public static void main(String[] args) {
        // 1. Prepare Data
        // Structure: {BuildingId, Purpose, AmbientCapacity, ChilledCapacity, FrozenCapacity, MaxWeightKg, LocationDescription, Active}
        // Note: BuildingId '1', '2', etc. are assumed to exist (default generated IDs).
        String[][] buffers = {
                {"1", "STAGING", "50", "20", "10", "500.0", "Quai Nord", "true"},
                {"2", "RECEIPT", "100", "40", "20", "1000.0", "Quai Réception", "true"},
                {"1", "PROCESSING", "30", "10", "5", "600.0", "Zone Préparation A", "true"}
        };

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        // The GraphQL Mutation Template
        String mutationTemplate = """
                mutation CreateBuffer($input: BufferRecordInput!) {
                  createBuffer(input: $input) {
                    id
                    purpose
                    active
                    ambientCapacity
                  }
                }
                """;

        // Remove newlines from query so it fits in a JSON string on one line
        String compactMutation = mutationTemplate.replace("\n", "\\n");

        // 2. Loop through buffers and send mutations
        for (String[] buffer : buffers) {
            try {
                // Using Text Block to build JSON variables clearly
                // Note: buildingId, capacities, and maxWeightKg are Numbers (no quotes).
                // Purpose, locationDescription are Strings. Active is Boolean (no quotes).
                String variablesJson = String.format(
                        """
                        {
                          "input": {
                            "buildingId": %s,
                            "purpose": "%s",
                            "ambientCapacity": %s,
                            "chilledCapacity": %s,
                            "frozenCapacity": %s,
                            "maxWeightKg": %s,
                            "locationDescription": "%s",
                            "active": %s
                          }
                        }
                        """,
                        buffer[0], // BuildingId (Number)
                        buffer[1], // Purpose (String)
                        buffer[2], // AmbientCapacity (Number)
                        buffer[3], // ChilledCapacity (Number)
                        buffer[4], // FrozenCapacity (Number)
                        buffer[5], // MaxWeightKg (Number)
                        buffer[6], // LocationDescription (String)
                        buffer[7]  // Active (Boolean)
                );

                // Remove newlines from variables JSON as well to make it compact
                String compactVariables = variablesJson.replace("\n", "");

                // Build of full JSON payload using Text Block
                String jsonPayload = String.format(
                        """
                        {
                          "query": "%s",
                          "variables": %s
                        }
                        """,
                        compactMutation, compactVariables
                );

                // Create Request
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(GRAPHQL_URL))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + authenticate("admin", "admin"))
                        .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                        .build();

                // Send Request
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                // Print Result
                if (response.statusCode() == 200) {
                    System.out.println("Success for Building " + buffer[0] + ": " + response.body());
                } else {
                    System.err.println("Error for Building " + buffer[0] + " (" + response.statusCode() + "): " + response.body());
                }

            } catch (Exception e) {
                System.err.println("Exception for Building " + buffer[0] + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

}