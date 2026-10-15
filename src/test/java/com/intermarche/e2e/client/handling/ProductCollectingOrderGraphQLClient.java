package com.intermarche.e2e.client.handling;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;

import static com.intermarche.e2e.Authenticate.authenticate;
import com.intermarche.backoffice.domain.order.Order;
import com.intermarche.backoffice.domain.order.Product;
import com.intermarche.e2e.Authenticate;

/**
 * GraphQL Client to fetch a specific Product Collecting Order by its ID.
 * <p>
 * This client demonstrates how to retrieve the Product Collecting Order aggregate associated with
 * a specific Order ID.
 * <p>
 * It queries the structure including nested Store, Customer, Building, the list of targeted Products, and assigned Lots.
 * Uses Jackson to pretty-print the JSON response.
 */
public class ProductCollectingOrderGraphQLClient {

    // GraphQL Endpoint
    private static final String BASE_URL = "http://localhost:8080";
    private static final String GRAPHQL_URL = BASE_URL + "/graphql";

    public static void main(String[] args) {
        // 1. Define the GraphQL Query
        // We query for a specific Product Collecting Order by ID (e.g., ID 5).
        // We request: id, status, time slots, store info, customer info, building, the list of products to collect, and lots.
        String graphqlQuery = """
                {
                  "query": "query { productCollectingOrder(id: 4) { id status requestedStart requestedEnd store { id code name } customer { id firstName lastName } building { id buildingCode } products { id ean name } lots { id status } } }"
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
            System.out.println("Sending GraphQL query to fetch Product Collecting Order with ID 5...");
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
                System.err.println("Error retrieving product collecting order: " + response.body());
            }

        } catch (Exception e) {
            System.err.println("Connection Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}