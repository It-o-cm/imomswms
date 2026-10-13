package com.intermarche.backoffice.client.importers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static com.intermarche.backoffice.Authenticate.authenticate;

/**
 * Client to import Employees via CSV.
 * <p>
 * Sends a CSV file containing employee data to the backend import endpoint.
 * The backend automatically hashes plain text passwords.
 */
public class EmployeeImporterClient {

    /**
     * The base URL of the backend application.
     */
    private static final String BASE_URL = "http://localhost:8080";

    /**
     * The specific endpoint URL for the CSV import.
     */
    private static final String IMPORT_URL = BASE_URL + "/employees/import";

    /**
     * Main method to execute the employee import.
     * <p>
     * Prepares a CSV dataset with 8 columns (including Email) and sends it
     * to the server using a Bearer token for authentication.
     *
     * @param args Command line arguments (not used).
     */
    public static void main(String[] args) {
        // Structure: BadgeId|StoreCode|FirstName|LastName|Email|Role|Password|Active
        // Note: Password is sent in plain text ("password"). The backend will hash it.
        // Note: Store codes 0101 and 0102 must exist in the database.
        String csvData = """
                BadgeId|StoreCode|FirstName|LastName|Email|Role|Password|Active
                EMP001|0101|Alice|Martin|hdarmet@gmail.com|MANAGER|password|true
                EMP002|0101|Bob|Dubois|bob.dubois@intermarche.local|PICKER|password|true
                EMP003|0101|Charlie|Dupont|charlie.dupont@intermarche.local|CASHIER|password|true
                EMP004|0101|David|Leroy|david.leroy@intermarche.local|PICKER|password|true
                EMP005|0102|Emma|Bernard|emma.bernard@intermarche.local|ADMIN|password|true
                EMP006|0102|Frank|Moreau|frank.moreau@intermarche.local|CASHIER|password|true
                """;

        /**
         * HTTP Client configured with a connection timeout.
         */
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        /**
         * Creates the HTTP POST request.
         * <p>
         * Sets the Content-Type to text/plain as required by the CSV endpoint.
         * Includes a Bearer token obtained via the static authenticate method.
         */
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(IMPORT_URL))
                .header("Content-Type", "text/plain")
                .header("Authorization", "Bearer " + authenticate("admin", "admin"))
                .POST(HttpRequest.BodyPublishers.ofString(csvData))
                .build();

        try {
            /**
             * Sends the request and captures the response.
             */
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