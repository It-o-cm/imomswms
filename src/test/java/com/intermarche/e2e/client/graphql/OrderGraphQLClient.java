package com.intermarche.e2e.client.graphql;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;

import static com.intermarche.e2e.Authenticate.authenticate;
import com.intermarche.backoffice.domain.order.Order;
import com.intermarche.e2e.Authenticate;

/**
 * GraphQL Client to fetch a specific Order by its business ID.
 * <p>
 * Demonstrates how to query nested structures (Customer, Address, Lines)
 * using a raw HTTP POST request to the GraphQL endpoint.
 * Uses Jackson to pretty-print the JSON response.
 */
public class OrderGraphQLClient {

    // GraphQL Endpoint
    private static final String BASE_URL = "http://localhost:8080";
    private static final String GRAPHQL_URL = BASE_URL + "/graphql";

    public static void main(String[] args) {
        // 1. Define the GraphQL Query
        // We query for the specific orderId "ORD-2023-1024-8971" created in the JMS example.
        // We request nested fields: customer -> address, and lines -> product.
        String graphqlQuery = """
                {
                  "query": "query { orderByOrderId(orderId: \\"ORD-2023-1024-8971\\") { orderId status createdAt customer { firstName lastName email address { streetLine1 city postalCode country } } lines { lineNumber requestedQuantity product { name ean } } } }"
                }
                """;

        // 3. Build the HTTP Request
        // Note: Content-Type must be application/json for GraphQL
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
            System.out.println("Sending GraphQL query to fetch Order ORD-2023-1024-8971...");
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
                System.err.println("Error retrieving order: " + response.body());
            }

        } catch (Exception e) {
            System.err.println("Connection Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}