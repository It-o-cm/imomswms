package com.intermarche.e2e.client.graphql;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static com.intermarche.e2e.Authenticate.authenticate;
import com.intermarche.e2e.Authenticate;

/**
 * Simple HTTP client to test Employee creation via GraphQL.
 * <p>
 * This class demonstrates how to programmatically send GraphQL mutations
 * to create a list of employees using Java 11+ HttpClient.
 */
public class EmployeeGraphQLClient {

    /**
     * The base URL of the backend application.
     */
    private static final String BASE_URL = "http://localhost:8080";

    /**
     * The specific endpoint for GraphQL queries and mutations.
     */
    private static final String GRAPHQL_URL = BASE_URL + "/graphql";

    /**
     * Main method to execute employee creation.
     * <p>
     * Prepares a list of sample employees and sends a GraphQL mutation
     * for each one using the {@link #GRAPHQL_URL}.
     * <p>
     * The password is sent in plain text; the backend handles the hashing.
     *
     * @param args Command line arguments (not used).
     */
    public static void main(String[] args) {
        // 1. Prepare Data
        // Structure: {BadgeId, StoreId, FirstName, LastName, Email, Role, Password, Active}
        // Note: StoreId '1' is assumed to exist (default generated ID).
        // Note: Password is now plain text (backend will hash it).
        String[][] employees = {
                {"EMP001", "1", "Alice", "Martin", "alice.martin@intermarche.local", "MANAGER", "password", "true"},
                {"EMP002", "1", "Bob", "Dubois", "bob.dubois@intermarche.local", "PICKER", "password", "true"},
                {"EMP003", "1", "Charlie", "Dupont", "charlie.dupont@intermarche.local", "CASHIER", "password", "true"},
                {"EMP004", "1", "David", "Leroy", "david.leroy@intermarche.local", "PICKER", "password", "true"},
                {"EMP005", "1", "Emma", "Bernard", "emma.bernard@intermarche.local", "ADMIN", "password", "true"},
                {"EMP006", "1", "Frank", "Moreau", "frank.moreau@intermarche.local", "CASHIER", "password", "true"}
        };

        /**
         * HTTP Client configured with a connection timeout.
         */
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        /**
         * The GraphQL Mutation Template.
         * <p>
         * Uses variables for security and readability.
         */
        String mutationTemplate = """
                mutation CreateEmployee($input: EmployeeRecordInput!) {
                  createEmployee(input: $input) {
                    id
                    badgeId
                    firstName
                    lastName
                    email
                  }
                }
                """;

        // Remove newlines from query so it fits in a JSON string on one line
        String compactMutation = mutationTemplate.replace("\n", "\\n");

        // 2. Loop through employees and send mutations
        for (String[] employee : employees) {
            try {
                /**
                 * Builds the JSON variables payload.
                 * <p>
                 * Maps the array data to the GraphQL input structure.
                 */
                String variablesJson = String.format(
                        """
                        {
                          "input": {
                            "badgeId": "%s",
                            "storeId": %s,
                            "firstName": "%s",
                            "lastName": "%s",
                            "email": "%s",
                            "role": "%s",
                            "password": "%s",
                            "active": %s
                          }
                        }
                        """,
                        employee[0], // BadgeId
                        employee[1], // StoreId
                        employee[2], // FirstName
                        employee[3], // LastName
                        employee[4], // Email (New)
                        employee[5], // Role
                        employee[6], // Password (Plain text)
                        employee[7]  // Active
                );

                // Remove newlines from variables JSON as well to make it compact
                String compactVariables = variablesJson.replace("\n", "");

                /**
                 * Constructs the full JSON payload for the HTTP request.
                 */
                String jsonPayload = String.format(
                        """
                        {
                          "query": "%s",
                          "variables": %s
                        }
                        """,
                        compactMutation, compactVariables
                );

                /**
                 * Creates the HTTP POST request.
                 * <p>
                 * Includes the Bearer token obtained via the {@code authenticate} utility.
                 */
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(GRAPHQL_URL))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + authenticate("admin", "admin"))
                        .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                        .build();

                /**
                 * Sends the request and receives the response.
                 */
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                // Print Result
                if (response.statusCode() == 200) {
                    System.out.println("Success for " + employee[0] + ": " + response.body());
                } else {
                    System.err.println("Error for " + employee[0] + " (" + response.statusCode() + "): " + response.body());
                }

            } catch (Exception e) {
                System.err.println("Exception for " + employee[0] + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
    }
}