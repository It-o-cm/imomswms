package com.intermarche.backoffice.client.graphql;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;

import static com.intermarche.backoffice.Authenticate.authenticate;

/**
 * GraphQL Client for creating Configuration entities.
 * <p>
 * Demonstrates how to perform mutations involving nested JSON strings
 * and entity relationships (Lists of Stores/Groups).
 */
public class ConfigurationGraphQLClient {

    private static final String BASE_URL = "http://localhost:8080";
    private static final String GRAPHQL_URL = BASE_URL + "/graphql";

    public static void main(String[] args) {
        // Format: {StoreIds JSON, GroupIds JSON, Name, Type, IsActive, SpecificationJSON}
        // Note: We assume Store IDs 1 and 2 exist, and Group ID 1 exists in the database.
        // JSON arrays are represented as strings here for the String.format injection.
        String[][] configs = {
                {"[1, 2]", "[]", "Winter Hours", "FULFILLMENT", "true", "{\"mon\":\"9-18\"}"},
                {"[]", "[1]", "Summer Hours", "FULFILLMENT", "false", "{\"mon\":\"8-20\"}"},
                {"[3]", "[1]", "Standard Packing", "PACKING", "true", "{\"boxType\":\"Small\"}"}
        };

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        // The GraphQL Mutation Template
        String mutationTemplate = """
                mutation CreateConfiguration($input: ConfigurationRecordInput!) {
                  createConfiguration(input: $input) {
                    id
                    name
                    stores { name }
                    storeGroups { name }
                  }
                }
                """;

        // Remove newlines from the query to make it a valid JSON string value
        String compactMutation = mutationTemplate.replace("\n", "\\n");

        // Loop through configs and send mutations
        for (String[] config : configs) {
            try {
                // Build the variables JSON manually.
                // CRITICAL: The 'specification' field is a String in the entity, containing JSON data.
                // We must escape double quotes within the string for the outer JSON payload to be valid.
                String rawSpec = config[5];
                String escapedSpec = rawSpec.replace("\"", "\\\"");

                // Constructing the JSON payload.
                // We inject storeIds and storeGroupIds as raw JSON arrays (e.g., "[1, 2]").
                String variablesJson = String.format(
                        """
                        {
                          "input": {
                            "name": "%s",
                            "type": "%s",
                            "isActive": %s,
                            "specification": "%s",
                            "storeIds": %s,
                            "storeGroupIds": %s
                          }
                        }
                        """,
                        config[2], // name
                        config[3], // type
                        config[4], // isActive
                        escapedSpec,
                        config[0], // storeIds JSON array
                        config[1]  // storeGroupIds JSON array
                );

                // Clean up newlines for a compact payload
                String compactVariables = variablesJson.replace("\n", "");

                // Build the full JSON payload
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
                    System.out.println("Success for " + config[2]);
                } else {
                    System.err.println("Error for " + config[2] + ": " + response.body());
                }

            } catch (Exception e) {
                System.err.println("Exception for " + config[2] + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

}