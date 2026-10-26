package com.intermarche.e2e;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Authenticate {
    private static final String AUTH_URL = "http://localhost:8040/auth/login";
    public static String authenticate(String username, String password) {
        // On utilise String.format pour insérer les variables Java dans la chaîne JSON
        String loginPayload = String.format("""
            {
                "username": "%s",
                "password": "%s"
            }
            """, username, password);

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(AUTH_URL))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(loginPayload))
                .build();
        System.out.println("Authenticating user '" + username + "' to get JWT...");
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                String token = extractTokenFromJson(response.body());
                System.out.println("Authentication successful. Token received.");
                return token;
            } else {
                System.err.println("Authentication failed. Status: " + response.statusCode());
                System.err.println("Response: " + response.body());
                return null;
            }
        }
        catch (Exception e) {
            System.err.println("Error during authentication: " + e.getMessage());
            throw new RuntimeException("Authentication failed: " + e.getMessage());
        }
    }

    /**
     * Helper method to extract the token string from the JSON response using Regex.
     *
     * @param json the JSON response body.
     * @return the token string.
     */
    private static String extractTokenFromJson(String json) {
        // Simple regex to find "token":"<value>"
        Pattern pattern = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"");
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            return matcher.group(1);
        }
        throw new RuntimeException("Token not found in response: " + json);
    }
}
