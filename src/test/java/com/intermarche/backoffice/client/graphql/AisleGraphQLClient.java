package com.intermarche.backoffice.client.graphql;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static com.intermarche.backoffice.Authenticate.authenticate;

/**
 * Simple HTTP client to test Aisle creation via GraphQL.
 * <p>
 * This class demonstrates how to programmatically send GraphQL mutations
 * to create a list of aisles using Java 11+ HttpClient.
 */
public class AisleGraphQLClient {

    // Configuration
    private static final String BASE_URL = "http://localhost:8080";
    private static final String GRAPHQL_URL = BASE_URL + "/graphql";

    /**
     * Main method to execute aisle creation.
     * <p>
     * Prepares a list of sample aisles and sends a GraphQL mutation
     * for each one using the {@link #GRAPHQL_URL}.
     *
     * @param args Command line arguments (not used).
     */
    public static void main(String[] args) {
        // 1. Prepare Data
        // Structure: {Code, BuildingId, LengthMeters, AisleType, ConnectedToHead, ConnectedToTail}
        // Note: BuildingId '1' is assumed to exist (default generated ID).
        String[][] aisles = {
                {"A01", "1", "25.50", "LINEAR", "ENTRANCE", "A02"},
                {"A02", "1", "30.00", "LINEAR", "A01", "A03"},
                {"A03", "1", "30.00", "LINEAR", "A02", "A04"},
                {"B01", "1", "25.50", "LINEAR", "A01", "B02"},
                {"B02", "1", "25.50", "LINEAR", "B01", "EXIT"}
        };

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        // The GraphQL Mutation Template
        String mutationTemplate = """
                mutation CreateAisle($input: AisleRecordInput!) {
                  createAisle(input: $input) {
                    id
                    code
                    lengthMeters
                    aisleType
                    connectedToHead
                    connectedToTail
                  }
                }
                """;

        // Remove newlines from query so it fits in a JSON string on one line
        String compactMutation = mutationTemplate.replace("\n", "\\n");

        // 2. Loop through aisles and send mutations
        for (String[] aisle : aisles) {
            try {
                // Using Text Block to build JSON variables clearly
                // Note: buildingId and lengthMeters are Numbers (Long/Double), so no quotes around %s.
                // Strings (code, type, connections) are quoted.
                String variablesJson = String.format(
                        """
                        {
                          "input": {
                            "code": "%s",
                            "buildingId": %s,
                            "lengthMeters": %s,
                            "aisleType": "%s",
                            "connectedToHead": "%s",
                            "connectedToTail": "%s"
                          }
                        }
                        """,
                        aisle[0], // Code
                        aisle[1], // BuildingId (Number)
                        aisle[2], // LengthMeters (Number)
                        aisle[3], // AisleType (String)
                        aisle[4], // ConnectedToHead (String)
                        aisle[5]  // ConnectedToTail (String)
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
                    System.out.println("Success for " + aisle[0] + ": " + response.body());
                } else {
                    System.err.println("Error for " + aisle[0] + " (" + response.statusCode() + "): " + response.body());
                }

            } catch (Exception e) {
                System.err.println("Exception for " + aisle[0] + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

}