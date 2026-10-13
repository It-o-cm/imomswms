package com.intermarche.backoffice.client.importers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;

import static com.intermarche.backoffice.Authenticate.authenticate;

/**
 * Client to import Aisles via CSV.
 * <p>
 * Sends a CSV payload to the {@code /aisles/import} endpoint.
 * Links aisles to existing Buildings.
 * <p>
 * <b>Topology Overview:</b>
 * The CSV data defines a store topology with linear aisles, intersections (hubs),
 * and shortcuts (diagonals/loops).
 * <p>
 * <div style="font-family: 'Courier New', Courier, monospace; background-color: #f9f9f9; padding: 15px; border: 1px solid #ddd; border-radius: 4px;">
 * <pre>
 *                                      [ ENTRANCE ]
 *                                           |
 *                                           |
 *                                          (A01)
 *                                           |
 *                                           |
 *                                   +-------[ HUB CENTRAL ]-------+
 *                                   |     (Main Intersection)    |
 *                                   |             |               |
 *                             +-----+-----+       |       +-------+-----+
 *                             |           |       |       |             |
 *                           (A03)       (B01)   (E01)   (C01)         (B02)
 *                             |           |       |       |             |
 *                          [End_A3]    [End_B] [FRESH] [End_C]      [End_B2]
 *                                        |       ^       ^
 *                                        |       |       |
 *                                        +---(X01)------+    (Diagonal Shortcut)
 *                                                    |
 *                                                    |
 *                                                 (LOOP_BACK)
 *                                                    |
 *                                                    +-------------------> [ ENTRANCE ]
 *                                                                           (Loop Back)
 *
 *                                        (Path continues from HUB CENTRAL)
 *                                                  |
 *                                                 (A02)
 *                                                  |
 *                                                  |
 *                                            +-----[ HUB BACK ]-----+
 *                                            |   (Back Intersection)|
 *                                            |             |          |
 *                                          (D01)         (F01)      (D02)
 *                                            |             |          |
 *                                         [End_D]      [FROZEN]    [End_D2]
 * </pre>
 * </div>
 * <p>
 * <b>CSV Format:</b>
 * Code|BuildingCode|LengthMeters|AisleType|ConnectedToHead|ConnectedToTail
 * <p>
 * <b>Note on Connections:</b>
 * Since the main CSV delimiter is the pipe (|), multiple connections within
 * the {@code connectedToHead} or {@code connectedToTail} columns must be
 * separated by a comma (,).
 * Example: {@code HUB_CENTRAL|...|...|...|A01,A03,B01|A02}
 *
 * @param args Command line arguments (not used).
 */
public class AisleImporterClient {

    private static final String BASE_URL = "http://localhost:8080";
    private static final String IMPORT_URL = BASE_URL + "/aisles/import";

    /**
     * Main method to execute the import process.
     * <p>
     * Prepares a CSV string containing sample Aisle data and sends it
     * via POST request.
     */
    public static void main(String[] args) {
        // Target Building: BLD-LILLE-01 (from BuildingImporterClient)

        // Structure: {code|buildingCode|lengthMeters|aisleType|connectedToHead|connectedToTail}
        // IMPORTANT: Multiple connections are separated by COMMA (,)

        String csvData = """
                code|buildingCode|lengthMeters|aisleType|connectedToHead|connectedToTail
                ENTRANCE|BLD-LILLE-01|5.0|ENTRANCE||A01
                A01|BLD-LILLE-01|25.0|LINEAR|ENTRANCE|HUB_CENTRAL
                HUB_CENTRAL|BLD-LILLE-01|10.0|INTERSECTION|A01,A03,B01,B02,C01,E01|A02
                A02|BLD-LILLE-01|30.0|LINEAR|HUB_CENTRAL|HUB_BACK
                HUB_BACK|BLD-LILLE-01|10.0|INTERSECTION|A02|D01,D02,F01
                A03|BLD-LILLE-01|12.0|LINEAR|HUB_CENTRAL|END_A3
                B01|BLD-LILLE-01|20.0|LINEAR|HUB_CENTRAL|END_B
                B02|BLD-LILLE-01|15.0|LINEAR|HUB_CENTRAL|END_B2
                C01|BLD-LILLE-01|20.0|LINEAR|HUB_CENTRAL|END_C
                D01|BLD-LILLE-01|15.0|LINEAR|HUB_BACK|END_D
                D02|BLD-LILLE-01|15.0|LINEAR|HUB_BACK|END_D2
                E01|BLD-LILLE-01|10.0|LINEAR|HUB_CENTRAL|FRESH_AREA
                F01|BLD-LILLE-01|10.0|LINEAR|HUB_BACK|FROZEN_AREA
                FRESH_AREA|BLD-LILLE-01|4.0|ZONE|E01|
                FROZEN_AREA|BLD-LILLE-01|4.0|ZONE|F01|
                X01|BLD-LILLE-01|5.0|DIAGONAL|END_B|END_C
                LOOP_BACK|BLD-LILLE-01|50.0|CIRCULAR|END_B|ENTRANCE
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