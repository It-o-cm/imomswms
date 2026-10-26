package com.intermarche.e2e.client.importers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;

import static com.intermarche.e2e.Authenticate.authenticate;
import com.intermarche.backoffice.domain.order.Customer;
import com.intermarche.e2e.Authenticate;

/**
 * Client to import Buffers via CSV.
 * <p>
 * Sends a CSV payload to the {@code /buffers/import} endpoint.
 * Defines Buffer capacities for existing Buildings using the Building code.
 * <p>
 * Prioritizes Buildings belonging to Store 0101 for the sample data.
 */
public class BufferImporterClient {

    private static final String BASE_URL = "http://localhost:8040";
    private static final String IMPORT_URL = BASE_URL + "/buffers/import";

    /**
     * Main method to execute the import process.
     * <p>
     * Prepares a CSV string containing sample Buffer data and sends it
     * via POST request.
     * <p>
     * CSV Structure: BuildingCode|Purpose|AmbientCapacity|ChilledCapacity|FrozenCapacity|MaxWeightKg|LocationDescription|Active
     *
     * @param args Command line arguments (not used).
     */
    public static void main(String[] args) {
        // Structure: {buildingCode|purpose|ambientCapacity|chilledCapacity|frozenCapacity|maxWeightKg|locationDescription|active}
        // We prioritize the Lille Store (0101) buildings: BLD-LILLE-01 (Store) and BLD-LILLE-WH (Warehouse).
        String csvData = """
                buildingCode|bufferCode|purpose|ambientCapacity|chilledCapacity|frozenCapacity|maxWeightKg|locationDescription|active
                LILLE-S|BLD-LILLE-01|STAGING|50|20|10|500.0|Drive Quai North|true
                LILLE-R|BLD-LILLE-WH|RECEIPT|100|40|20|1000.0|Dock 4|true
                LILLE-P|BLD-LILLE-WH|PROCESSING|30|10|5|600.0|Prep Zone A|true
                BDX-S|BLD-BDX-01|STAGING|40|15|5|400.0|Parking Zone A|true
                LYON-S|BLD-LYON-01|STAGING|40|15|5|400.0|Rear Dock|true
                PARIS-S|BLD-PARIS-01|STAGING|30|10|5|300.0|Courtyard|true
                PARIS-R|BLD-PARIS-WH|RECEIPT|120|50|25|1200.0|Main Receiving Dock|true
                STG-S|BLD-STG-01|STAGING|35|12|5|380.0|Customer Collection Point|true
                """;

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(IMPORT_URL))
                .header("Content-Type", "text/plain")
                .header("Authorization", "Bearer " + authenticate("admin", "admin"))
                .POST(HttpRequest.BodyPublishers.ofString(csvData))
                .build();

        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            System.out.println("Status Code: " + response.statusCode());
            System.out.println("Server Response: " + response.body());

            if (response.statusCode() == 200) {
                System.out.println("Import successful!");
            } else {
                System.err.println("Error during processing.");
            }

        } catch (Exception e) {
            System.err.println("Connection Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}