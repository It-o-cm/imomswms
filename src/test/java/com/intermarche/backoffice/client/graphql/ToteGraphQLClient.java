package com.intermarche.backoffice.client.graphql;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;

import static com.intermarche.backoffice.Authenticate.authenticate;

/**
 * Simple HTTP client to test Tote creation via GraphQL.
 * <p>
 * This class demonstrates how to programmatically send GraphQL mutations
 * to create a list of totes using Java 11+ HttpClient.
 */
public class ToteGraphQLClient {

    // Configuration
    private static final String BASE_URL = "http://localhost:8080";
    private static final String GRAPHQL_URL = BASE_URL + "/graphql";

    /**
     * Main method to execute tote creation.
     * <p>
     * Prepares a list of sample totes and sends a GraphQL mutation
     * for each one using the {@link #GRAPHQL_URL}.
     *
     * @param args Command line arguments (not used).
     */
    public static void main(String[] args) {
        // 1. Prepare Data
        // Structure: {Code, StoreId, Volume, ToteType}
        // Note: StoreId '1' is assumed to exist (default generated ID).
        // Note: Volume and ToteType are passed as Strings (as expected by ToteRecordInput).
        String[][] totes = {
                {"TOT-STD-001", "1", "45.500", "STANDARD"},
                {"TOT-STD-002", "1", "45.500", "STANDARD"},
                {"TOT-STD-003", "1", "60.000", "STANDARD"},
                {"TOT-ISO-001", "1", "35.000", "INSULATED"},
                {"TOT-ISO-002", "1", "35.000", "INSULATED"},
                {"TOT-ISO-003", "1", "35.000", "INSULATED"}
        };

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        // The GraphQL Mutation Template
        String mutationTemplate = """
                mutation CreateTote($input: ToteRecordInput!) {
                  createTote(input: $input) {
                    id
                    code
                    volume
                    toteType
                  }
                }
                """;

        // Remove newlines from query so it fits in a JSON string on one line
        String compactMutation = mutationTemplate.replace("\n", "\\n");

        // 2. Loop through totes and send mutations
        for (String[] tote : totes) {
            try {
                // Using Text Block to build JSON variables clearly
                // Note: storeId is a Number (Long), so no quotes around %s. Volume and ToteType are Strings.
                String variablesJson = String.format(
                        """
                        {
                          "input": {
                            "code": "%s",
                            "storeId": %s,
                            "volume": "%s",
                            "toteType": "%s"
                          }
                        }
                        """,
                        tote[0], // Code
                        tote[1], // StoreId (Number)
                        tote[2], // Volume (String)
                        tote[3]  // ToteType (String)
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
                    System.out.println("Success for " + tote[0] + ": " + response.body());
                } else {
                    System.err.println("Error for " + tote[0] + " (" + response.statusCode() + "): " + response.body());
                }

            } catch (Exception e) {
                System.err.println("Exception for " + tote[0] + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

}