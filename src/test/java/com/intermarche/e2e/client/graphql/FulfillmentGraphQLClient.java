package com.intermarche.e2e.client.graphql;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static com.intermarche.e2e.Authenticate.authenticate;
import com.intermarche.e2e.Authenticate;

/**
 * GraphQL Client to fetch a specific Fulfillment by its linked Order business ID.
 * <p>
 * This client demonstrates how to retrieve the Fulfillment aggregate associated with
 * a specific Order ID ("ORD-2023-1024-8971").
 * <p>
 * It queries the structure including nested Building (in Chunk) and Location (in Line).
 * Uses Jackson to pretty-print the JSON response.
 */
public class FulfillmentGraphQLClient {

    // GraphQL Endpoint
    private static final String BASE_URL = "http://localhost:8080";
    private static final String GRAPHQL_URL = BASE_URL + "/graphql";

    public static void main(String[] args) {
        // 1. Define the GraphQL Query
        // We query for the fulfillment linked to orderId "ORD-2023-1024-8971".
        // UPDATED: Added 'building' to chunks and 'location' to lines.
        String graphqlQuery = """
        {
          "query": "query { \
            fulfillmentByOrderId(orderId: \\"ORD-2023-1024-8971\\") { \
              id \
              reservationRecord { status leasing } \
              order { orderId } \
              chunks { \
                type \
                deliveryDate \
                building { id buildingCode address { city } } \
                lines { \
                  lineNumber \
                  requestedQuantity \
                  product { name ean } \
                  location { \
                    id \
                    locationCode \
                    area \
                    aisle \
                  } \
                } \
              } \
            } \
          }"
        }
        """;

        /*
         ... on ShelfLocation {
              area
              aisle
            } \
         */
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
            System.out.println("Sending GraphQL query to fetch Fulfillment for Order ORD-2023-1024-8971...");
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
                System.err.println("Error retrieving fulfillment: " + response.body());
            }

        } catch (Exception e) {
            System.err.println("Connection Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}