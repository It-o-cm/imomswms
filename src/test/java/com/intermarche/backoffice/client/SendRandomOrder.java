package com.intermarche.backoffice.client;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static com.intermarche.backoffice.Authenticate.authenticate;

/**
 * Standalone client to submit a random order to Store 0101.
 * <p>
 * Generates random customer details and order lines based on a predefined product catalog.
 * Ensures that each product appears only once per order.
 */
public class SendRandomOrder {

    private static final String ORDER_URL = "http://localhost:8080/orders";
    private static final String STORE_CODE = "0101";
    private static final String STORE_NAME = "Intermarché Test 1";

    // Sample data pools for randomization
    private static final String[] FIRST_NAMES = {"Jean", "Marie", "Pierre", "Sophie", "Luc", "Camille", "Thomas", "Emma", "Hugo", "Lea"};
    private static final String[] LAST_NAMES = {"Martin", "Bernard", "Dubois", "Thomas", "Robert", "Richard", "Petit", "Durand", "Leroy", "Moreau"};
    private static final String[] STREETS = {"Rue de la Paix", "Avenue des Champs", "Rue Nationale", "Boulevard Victor Hugo", "Rue de la Gare"};
    private static final String[] CITIES = {"Lille", "Ronchin", "Marcq-en-Baroeul", "Lambersart", "Villeneuve-d'Ascq"};
    private static final String[] POSTAL_CODES = {"59000", "59350", "59800", "59130"};

    // Product catalog matching ProductImporterClient and LocationImporterClient data
    // Format: EAN, Name, Price, Weight, Volume, Zone
    private static final String[][] PRODUCT_CATALOG = {
            // --- Chaud / Traiteur (HOT) ---
            {"3560070049010", "Roasted Farm Chicken", "12.00", "1.200", "2.500", "HOT"},
            {"3300000000036", "Dinde Rotie", "11.00", "1.500", "3.000", "HOT"},
            {"3300000000042", "Welsh Rarebit (Prepared)", "9.50", "0.450", "0.500", "HOT"},
            {"3300000000046", "Poulet Rôti Fermier", "14.50", "1.300", "2.800", "HOT"},

            // --- Frais / Réfrigéré (FRESH) ---
            {"3300000000001", "Pommes Golden", "2.50", "1.000", "2.000", "FRESH"},
            {"3300000000017", "Endives (Chicory)", "3.20", "0.500", "1.000", "FRESH"},
            {"3300000000018", "Tomates Cerises", "2.80", "0.500", "0.800", "FRESH"},
            {"3300000000008", "Jambon Blanc 4 tranches", "1.90", "0.120", "0.200", "FRESH"},
            {"3300000000009", "Beurre Demi-Sel 250g", "2.10", "0.250", "0.300", "FRESH"},
            {"3300000000021", "Saumon Fumé", "6.50", "0.200", "0.250", "FRESH"},
            {"3300000000041", "Maroilles Cheese", "5.50", "0.250", "0.300", "FRESH"},
            {"3300000000043", "Merveux Cheese", "4.80", "0.200", "0.250", "FRESH"},
            {"3300000000047", "Fromage de Chevre", "3.50", "0.150", "0.200", "FRESH"},
            {"3300000000019", "Oeufs Bio (6)", "2.80", "0.360", "0.500", "FRESH"},
            {"3300000000048", "Quiche Lorraine", "4.50", "0.350", "0.450", "FRESH"},

            // --- Surgelé (FROZEN) ---
            {"3068320112305", "Vanilla Ice Cream", "5.20", "0.500", "1.000", "FROZEN"},
            {"3300000000035", "Petits Pois Surgelés", "4.00", "0.500", "0.800", "FROZEN"},
            {"3300000000044", "Glace Chocolat", "5.50", "0.500", "1.000", "FROZEN"},
            {"3300000000049", "Pizza Reine Surgelée", "4.20", "0.400", "0.800", "FROZEN"},

            // --- Épicerie / Ambiant (AMBIENT) ---
            {"3017620422003", "Chocapic Cereal", "3.45", "0.800", "4.000", "AMBIENT"},
            {"3229820125000", "Cristaline Spring Water", "0.65", "1.500", "1.500", "AMBIENT"},
            {"3334345566778", "Red Bordeaux Wine", "8.50", "0.750", "0.750", "AMBIENT"},
            {"3000000000010", "Lavazza Qualità Oro", "6.50", "0.250", "3.000", "AMBIENT"},
            {"3000000000027", "Nutella Pot 825g", "4.90", "0.825", "1.000", "AMBIENT"},
            {"3300000000003", "Baguette Tradition", "0.95", "0.250", "0.400", "AMBIENT"},
            {"3300000000002", "Lait Demi-Ecrémé 1L", "1.10", "1.000", "1.000", "AMBIENT"},
            {"3300000000005", "Pâtes Penne 500g", "0.95", "0.500", "1.000", "AMBIENT"},
            {"3300000000006", "Huile d'Olive 1L", "7.20", "0.920", "1.000", "AMBIENT"},
            {"3300000000007", "Eau Minérale 1.5L", "0.70", "1.500", "1.500", "AMBIENT"},
            {"3300000000011", "Coca-Cola 1.5L", "1.80", "1.500", "1.500", "AMBIENT"},
            {"3300000000012", "Orangina 1.25L", "1.90", "1.250", "1.250", "AMBIENT"},
            {"3300000000013", "Biscuits Chocolat", "2.00", "0.200", "0.400", "AMBIENT"},
            {"3300000000014", "Chips Classiques 150g", "1.50", "0.150", "0.300", "AMBIENT"},
            {"3300000000040", "Ch'ti Blonde Beer", "3.50", "0.500", "0.500", "AMBIENT"},
            {"3300000000045", "Miel d'Acacia 500g", "6.00", "0.500", "0.700", "AMBIENT"},
            {"3300000000050", "Pain d'Epices", "4.20", "0.300", "0.600", "AMBIENT"},
            {"3300000000051", "Bêtises de Cambrai", "3.00", "0.150", "0.200", "AMBIENT"},
            {"3300000000052", "Riz Basmati 1kg", "2.30", "1.000", "1.500", "AMBIENT"},
            {"3300000000053", "Sauce Tomate 400g", "1.50", "0.400", "0.450", "AMBIENT"}
    };

    private static final Random RANDOM = new Random();

    public static void main(String[] args) {
        try {
            String token = authenticate("service", "service");
            if (token == null) {
                System.err.println("Failed to authenticate. Exiting.");
                return;
            }

            String jsonPayload = generateRandomOrderJson();
            submitOrder(token, jsonPayload);
        } catch (Exception e) {
            System.err.println("Technical error during random order submission: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Generates a JSON string representing a valid random order.
     * Ensures unique products per order.
     */
    private static String generateRandomOrderJson() {
        StringBuilder sb = new StringBuilder();

        // 1. Header
        String orderId = "ORD-RAND-" + System.currentTimeMillis();
        String createdAt = OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT);

        // Customer Randomization
        String firstName = FIRST_NAMES[RANDOM.nextInt(FIRST_NAMES.length)];
        String lastName = LAST_NAMES[RANDOM.nextInt(LAST_NAMES.length)];
        String email = firstName.toLowerCase() + "." + lastName.toLowerCase() + "@example.com";
        String phone = "+336" + String.format("%08d", RANDOM.nextInt(100000000));

        // Address Randomization
        String street = (RANDOM.nextInt(100) + 1) + " " + STREETS[RANDOM.nextInt(STREETS.length)];
        String city = CITIES[RANDOM.nextInt(CITIES.length)];
        String postalCode = POSTAL_CODES[RANDOM.nextInt(POSTAL_CODES.length)];

        // --- DELIVERY SLOT LOGIC (15 MIN SLOTS) ---
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).minusDays(1).minusHours(6);

        OffsetDateTime minStartTime = now.plusHours(6);
        OffsetDateTime maxStartTime = now.plusHours(10);

        int minute = minStartTime.getMinute();
        int remainder = minute % 15;
        if (remainder != 0) {
            minStartTime = minStartTime.plusMinutes(15 - remainder);
        }
        minStartTime = minStartTime.withSecond(0).withNano(0);

        long minutesBetween = Duration.between(minStartTime, maxStartTime).toMinutes();
        long slotsAvailable = minutesBetween / 15;
        long randomSlotIndex = (slotsAvailable > 0) ? (long) (Math.random() * (slotsAvailable + 1)) : 0;

        OffsetDateTime startTime = minStartTime.plusMinutes(randomSlotIndex * 15);
        OffsetDateTime endTime = startTime.plusMinutes(15);

        String slotStart = startTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        String slotEnd = endTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        sb.append("{\n");

        // Order Header
        sb.append("  \"orderHeader\": {\n");
        sb.append("    \"orderId\": \"").append(orderId).append("\",\n");
        sb.append("    \"createdAt\": \"").append(createdAt).append("\",\n");
        sb.append("    \"channel\": \"MOBILE_APP\",\n");
        sb.append("    \"status\": \"VALIDATED\",\n");
        sb.append("    \"store\": {\n");
        sb.append("      \"id\": 1,\n");
        sb.append("      \"code\": \"").append(STORE_CODE).append("\",\n");
        sb.append("      \"name\": \"").append(STORE_NAME).append("\",\n");
        sb.append("      \"address\": { \"city\": \"Lille\", \"country\": \"France\" }\n");
        sb.append("    }\n");
        sb.append("  },\n");

        // Customer
        sb.append("  \"customer\": {\n");
        sb.append("    \"customerId\": \"CUS-").append(UUID.randomUUID().toString().substring(0, 8)).append("\",\n");
        sb.append("    \"firstName\": \"").append(firstName).append("\",\n");
        sb.append("    \"lastName\": \"").append(lastName).append("\",\n");
        sb.append("    \"email\": \"").append(email).append("\",\n");
        sb.append("    \"phone\": \"").append(phone).append("\",\n");
        sb.append("    \"address\": {\n");
        sb.append("      \"streetLine1\": \"").append(street).append("\",\n");
        sb.append("      \"postalCode\": \"").append(postalCode).append("\",\n");
        sb.append("      \"city\": \"").append(city).append("\",\n");
        sb.append("      \"country\": \"France\"\n");
        sb.append("    }\n");
        sb.append("  },\n");

        // Fulfillment
        sb.append("  \"fulfillment\": {\n");
        sb.append("    \"type\": \"HOME_DELIVERY\",\n");
        sb.append("    \"slot\": {\n");
        sb.append("      \"start\": \"").append(slotStart).append("\",\n");
        sb.append("      \"end\": \"").append(slotEnd).append("\"\n");
        sb.append("    }\n");
        sb.append("  },\n");

        // --- LINES GENERATION (UNIQUE PRODUCTS) ---
        int lineCount = RANDOM.nextInt(10) + 5; // 5 to 14 lines

        // Create a list of all products to pick from
        List<String[]> availableProducts = new ArrayList<>();
        for (String[] product : PRODUCT_CATALOG) {
            availableProducts.add(product);
        }

        // Shuffle the list to randomize selection
        Collections.shuffle(availableProducts);

        // Ensure we don't ask for more lines than products available
        lineCount = Math.min(lineCount, availableProducts.size());

        List<String> lineJsons = new ArrayList<>();

        double totalPrice = 0.0;
        double totalWeight = 0.0;
        double totalVolume = 0.0;
        int totalItems = 0;
        boolean hasTempControl = false;
        List<String> zones = new ArrayList<>();

        // Iterate through the shuffled list (guarantees uniqueness)
        for (int i = 0; i < lineCount; i++) {
            String[] product = availableProducts.get(i); // Pick unique product

            double quantity = 1.0 + RANDOM.nextInt(3); // 1 to 3 items

            String ean = product[0];
            String name = product[1];
            double unitPrice = Double.parseDouble(product[2]);
            double unitWeight = Double.parseDouble(product[3]);
            double unitVolume = Double.parseDouble(product[4]);
            String zone = product[5];

            double linePrice = (double) Math.round(unitPrice * quantity * 100) / 100;
            double lineWeight = unitWeight * quantity;
            double lineVolume = unitVolume * quantity;

            totalPrice += linePrice;
            totalWeight += lineWeight;
            totalVolume += lineVolume;
            totalItems += (int) quantity;

            if (!zones.contains(zone)) zones.add(zone);
            if ("FROZEN".equals(zone) || "HOT".equals(zone) || "FRESH".equals(zone)) hasTempControl = true;

            boolean fragile = RANDOM.nextBoolean();

            StringBuilder lineSb = new StringBuilder();
            lineSb.append("    {\n");
            lineSb.append("      \"lineNumber\": ").append(i + 1).append(",\n");
            lineSb.append("      \"product\": {\n");
            lineSb.append("        \"ean\": \"").append(ean).append("\",\n");
            lineSb.append("        \"name\": \"").append(name).append("\",\n");
            lineSb.append("        \"productType\": \"UNIT\"\n");
            lineSb.append("      },\n");
            lineSb.append("      \"requestedQuantity\": ").append(quantity).append(",\n");
            lineSb.append("      \"pricing\": {\n");
            lineSb.append("        \"basePrice\": ").append(unitPrice).append(",\n");
            lineSb.append("        \"unitPrice\": ").append(unitPrice).append(",\n");
            lineSb.append("        \"totalPrice\": ").append(linePrice).append("\n");
            lineSb.append("      },\n");
            lineSb.append("      \"logistics\": {\n");
            lineSb.append("        \"weight\": ").append(unitWeight).append(",\n");
            lineSb.append("        \"volume\": ").append(unitVolume).append(",\n");
            lineSb.append("        \"fragile\": ").append(fragile).append(",\n");
            lineSb.append("        \"temperatureZone\": \"").append(zone).append("\"\n");
            lineSb.append("      }\n");
            lineSb.append("    }");

            lineJsons.add(lineSb.toString());
        }

        sb.append("  \"lines\": [\n");
        sb.append(String.join(",\n", lineJsons));
        sb.append("\n  ],\n");

        // Summary
        sb.append("  \"summary\": {\n");
        sb.append("    \"totalItems\": ").append(totalItems).append(",\n");
        sb.append("    \"totalPriceExclTax\": ").append(totalPrice).append(",\n");
        sb.append("    \"totalTax\": ").append(Math.round(totalPrice * 0.1 * 100.0) / 100.0).append(",\n");
        sb.append("    \"totalPriceInclTax\": ").append(Math.round(totalPrice * 1.1 * 100.0) / 100.0).append(",\n");
        sb.append("    \"logisticsTotals\": {\n");
        sb.append("      \"totalWeight\": ").append(Math.round(totalWeight * 1000.0) / 1000.0).append(",\n");
        sb.append("      \"totalVolume\": ").append(Math.round(totalVolume * 1000.0) / 1000.0).append(",\n");
        sb.append("      \"requiresTemperatureControl\": ").append(hasTempControl).append(",\n");
        sb.append("      \"temperatureZones\": [\"").append(String.join("\", \"", zones)).append("\"]\n");
        sb.append("    }\n");
        sb.append("  }\n");

        sb.append("}");

        return sb.toString();
    }

    private static void submitOrder(String token, String jsonPayload) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        // Debug: System.out.println("Request Body: " + jsonPayload);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ORDER_URL))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                .build();

        System.out.println("Sending random order to " + ORDER_URL + "...");
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        System.out.println("Status Code: " + response.statusCode());
        System.out.println("Response Body: " + response.body());

        if (response.statusCode() == 202) {
            System.out.println("SUCCESS: Random Order accepted.");
        } else {
            System.err.println("FAILURE: Order rejected.");
        }
    }
}