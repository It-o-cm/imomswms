package com.intermarche.backoffice.client.graphql;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;

import static com.intermarche.backoffice.Authenticate.authenticate;

/**
 * GraphQL Client to fetch specific OrderRequests by their business Number.
 * <p>
 * Demonstrates how to query the OrderRequest entity via raw HTTP POST.
 * Fetches the reservation details (status, leasing) and the raw JSON specification.
 */
public class OrderRequestGraphQLClient {

    // GraphQL Endpoint
    private static final String BASE_URL = "http://localhost:8080";
    private static final String GRAPHQL_URL = BASE_URL + "/graphql";

    public static void main(String[] args) {
        // 1. Define the GraphQL Query
        // Query 'ordersByNumber' to retrieve the history of requests for this number.
        // Request: ID, order number, store code, the embedded 'reservationRecord'
        // and the 'specification' (raw JSON).
        // NOTE: The ReservationRecord structure now focuses on Status and Leasing,
        // removing specific worker context fields like workerId/hostName.
        String graphqlQuery = """
                {
                  "query": "query { ordersByNumber(orderNumber: \\"ORD-2023-1024-8971\\") { id orderNumber storeCode specification reservationRecord { status leasing } } }"
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
            System.out.println("Sending GraphQL query to fetch OrderRequest ORD-2023-1024-8971...");
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
                System.err.println("Error retrieving order request: " + response.body());
            }

        } catch (Exception e) {
            System.err.println("Connection Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}