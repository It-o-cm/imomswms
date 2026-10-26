package com.intermarche.e2e.client.graphql;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;

import static com.intermarche.e2e.Authenticate.authenticate;
import com.intermarche.backoffice.domain.store.Employee;
import com.intermarche.e2e.Authenticate;

/**
 * GraphQL Client to fetch a specific Employee by its ID.
 * <p>
 * This client demonstrates how to retrieve an Employee entity including
 * its associated Store details.
 * <p>
 * It queries the structure including ID, names, BadgeId, Role, Active status,
 * and the nested Store (Code, Name).
 * Uses Jackson to pretty-print the JSON response.
 */
public class ReadEmployeeGraphQLClient {

    // GraphQL Endpoint
    private static final String BASE_URL = "http://localhost:8040";
    private static final String GRAPHQL_URL = BASE_URL + "/graphql";

    public static void main(String[] args) {
        // 1. Define the GraphQL Query
        // We query for a specific Employee by ID (e.g., ID 1).
        // We request: id, names, badgeId, role, active status, and linked store details.
        String graphqlQuery = """
                {
                  "query": "query { employee(id: 1) { id firstName lastName badgeId role active store { id code name address { city } } } }"
                }
                """;

        // 3. Build the HTTP Request
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(GRAPHQL_URL))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + authenticate("admin", "admin"))
                .POST(HttpRequest.BodyPublishers.ofString(graphqlQuery))
                .build();

        // 4. Send Request and Handle Response
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        try {
            System.out.println("Sending GraphQL query to fetch Employee with ID 1...");
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            System.out.println("Status Code: " + response.statusCode());

            if (response.statusCode() == 200) {
                System.out.println("Response Body (JSON):");
                // Use Jackson to parse and pretty print the JSON
                ObjectMapper mapper = new ObjectMapper();
                Object jsonObject = mapper.readValue(response.body(), Object.class);
                String prettyJson = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonObject);
                System.out.println(prettyJson);
            } else {
                System.err.println("Error retrieving employee: " + response.body());
            }

        } catch (Exception e) {
            System.err.println("Connection Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}