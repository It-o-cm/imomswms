package com.intermarche.backoffice.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.intermarche.backoffice.domain.store.TemperatureZone;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Scanner;

import static com.intermarche.backoffice.Authenticate.authenticate;

/**
 * Interactive CLI client to manage Handling Orders, Picking Operations, and Grouping Operations.
 * <p>
 * This program provides a menu to list available orders, reserve orders for employees,
 * cancel reservations, manage totes/items for picking, and manage totes/delivery for grouping.
 * It supports calculating optimized picking routes and concluding executions.
 */
public class OrderClientRunner {

    private static final String BASE_URL = "http://localhost:8080/handling-orders";
    private static final HttpClient client = HttpClient.newHttpClient();
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final Scanner scanner = new Scanner(System.in);

    static {
        // Register the JavaTimeModule to handle LocalDateTime serialization/deserialization
        mapper.registerModule(new JavaTimeModule());
    }

    /**
     * Main entry point.
     * Starts an interactive loop with a menu.
     *
     * @param args command line arguments (not used).
     */
    public static void main(String[] args) {
        boolean running = true;

        while (running) {
            printMenu();
            String choice = scanner.nextLine();

            switch (choice) {
                case "1":
                    listAvailableOrders();
                    break;
                case "2":
                    reserveOrder();
                    break;
                case "3":
                    cancelReservation();
                    break;
                case "4":
                    pickingMenu();
                    break;
                case "5":
                    groupingMenu();
                    break;
                case "0":
                    running = false;
                    System.out.println("Exiting...");
                    break;
                default:
                    System.out.println("Invalid choice. Please try again.");
            }
            System.out.println(); // Add a spacer for readability
        }
        scanner.close();
    }

    /**
     * Prints the interactive menu options to the console.
     */
    private static void printMenu() {
        System.out.println("--------------------------------------------------");
        System.out.println("       Handling Order Client                     ");
        System.out.println("--------------------------------------------------");
        System.out.println("1. List Available Orders");
        System.out.println("2. Reserve an Order");
        System.out.println("3. Cancel Reservation");
        System.out.println("4. Picking Operations");
        System.out.println("5. Grouping Operations");
        System.out.println("0. Exit");
        System.out.print("Select an option: ");
    }

    // --------------------------------------------------
    // Existing Methods (List, Reserve, Cancel)
    // --------------------------------------------------

    /**
     * 1. Fetches and displays all available handling orders.
     */
    private static void listAvailableOrders() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/available"))
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                // Deserialize JSON into a List of DTOs
                List<HandlingOrderSummaryDTO> orders = mapper.readValue(response.body(), new TypeReference<List<HandlingOrderSummaryDTO>>() {});

                System.out.println("\n--- Available Orders (" + orders.size() + ") ---");
                if (orders.isEmpty()) {
                    System.out.println("No orders available at the moment.");
                } else {
                    // Print a formatted table
                    System.out.printf("%-5s | %-20s | %-10s | %-20s | %-20s%n", "ID", "Type", "Status", "Start Date", "End Date");
                    System.out.println("-----------------------------------------------------------------------------");
                    for (HandlingOrderSummaryDTO order : orders) {
                        System.out.printf("%-5s | %-20s | %-10s | %-20s | %-20s%n",
                                order.id,
                                order.type,
                                order.status,
                                order.requestedStart,
                                order.requestedEnd);
                    }
                }
            } else {
                System.err.println("Error fetching orders (" + response.statusCode() + "): " + response.body());
            }

        } catch (Exception e) {
            System.err.println("An error occurred while listing orders:");
            e.printStackTrace();
        }
    }

    /**
     * 2. Reserves a specific handling order for an employee.
     * <p>
     * Prompts the user for Order ID and Employee Badge ID.
     */
    private static void reserveOrder() {
        try {
            System.out.print("Enter Order ID to reserve: ");
            Long orderId = Long.parseLong(scanner.nextLine());

            System.out.print("Enter Employee Badge ID: ");
            String badgeId = scanner.nextLine();

            // Create the JSON request body
            String jsonBody = mapper.writeValueAsString(new ReserveRequest(badgeId));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/" + orderId + "/reserve"))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer " + authenticate("admin", "admin"))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 201) {
                System.out.println("Success: Order reserved successfully.");
                System.out.println("Execution Details: " + response.body());
            } else {
                System.err.println("Error reserving order (" + response.statusCode() + "): " + response.body());
            }

        } catch (NumberFormatException e) {
            System.err.println("Error: Invalid Order ID format. Please enter a numeric ID.");
        } catch (Exception e) {
            System.err.println("An error occurred while reserving the order:");
            e.printStackTrace();
        }
    }

    /**
     * 3. Cancels the reservation of a specific handling order.
     * <p>
     * Prompts the user for Order ID.
     */
    private static void cancelReservation() {
        try {
            System.out.print("Enter Order ID to cancel reservation: ");
            Long orderId = Long.parseLong(scanner.nextLine());

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/" + orderId + "/reserve"))
                    .DELETE()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 204) {
                System.out.println("Success: Reservation cancelled successfully.");
            } else {
                System.err.println("Error cancelling reservation (" + response.statusCode() + "): " + response.body());
            }

        } catch (NumberFormatException e) {
            System.err.println("Error: Invalid Order ID format. Please enter a numeric ID.");
        } catch (Exception e) {
            System.err.println("An error occurred while cancelling the reservation:");
            e.printStackTrace();
        }
    }

    // --------------------------------------------------
    // Picking Operations Methods
    // --------------------------------------------------

    /**
     * Displays the submenu for picking operations.
     * <p>
     * Loops internally until the user chooses to return to the main menu.
     */
    private static void pickingMenu() {
        boolean inPickingMenu = true;
        while (inPickingMenu) {
            System.out.println("\n--------------------------------------------------");
            System.out.println("       Picking Operations                         ");
            System.out.println("--------------------------------------------------");
            System.out.println("1. Add Tote to Execution");
            System.out.println("2. Remove Empty Tote");
            System.out.println("3. Add Item to Tote");
            System.out.println("4. Move Item");
            System.out.println("5. Remove Item from Tote");
            System.out.println("6. Cancel Picked Item");
            System.out.println("7. Conclude Execution");
            System.out.println("8. Get Picking Route");
            System.out.println("0. Back to Main Menu");
            System.out.print("Select an option: ");

            String choice = scanner.nextLine();
            switch (choice) {
                case "1":
                    addToteToExecution();
                    break;
                case "2":
                    removeEmptyTote();
                    break;
                case "3":
                    addItemToTote();
                    break;
                case "4":
                    moveItem();
                    break;
                case "5":
                    removeItemFromTote();
                    break;
                case "6":
                    cancelPickedItem();
                    break;
                case "7":
                    concludeExecution();
                    break;
                case "8":
                    getPickingRoute();
                    break;
                case "0":
                    inPickingMenu = false;
                    break;
                default:
                    System.out.println("Invalid choice. Please try again.");
            }
        }
    }

    /**
     * 4.1 Adds a tote to the picking execution.
     * <p>
     * Prompts the user for the Execution ID, Tote Code, and Usage.
     */
    private static void addToteToExecution() {
        try {
            System.out.print("Enter Execution ID: ");
            Long executionId = Long.parseLong(scanner.nextLine());

            System.out.print("Enter Tote Code: ");
            String toteCode = scanner.nextLine();

            System.out.print("Enter Usage (AMBIENT, CHILLED, FROZEN, HOUSEHOLD): ");
            String usageStr = scanner.nextLine();
            TemperatureZone usage = TemperatureZone.valueOf(usageStr);

            String jsonBody = mapper.writeValueAsString(new AddToteRequest(toteCode, usage));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/" + executionId + "/totes"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 201) {
                System.out.println("Success: Tote added.");
                System.out.println("Details: " + response.body());
            } else {
                System.err.println("Error adding tote (" + response.statusCode() + "): " + response.body());
            }
        } catch (IllegalArgumentException e) {
            System.err.println("Error: Invalid Usage. Must be AMBIENT, CHILLED, FROZEN, or HOUSEHOLD.");
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
        }
    }

    /**
     * 4.2 Removes an empty tote from the picking execution.
     * <p>
     * Prompts the user for Execution ID and Tote Code.
     * Sends a DELETE request.
     */
    private static void removeEmptyTote() {
        try {
            System.out.print("Enter Execution ID: ");
            Long executionId = Long.parseLong(scanner.nextLine());

            System.out.print("Enter Tote Code to remove: ");
            String toteCode = scanner.nextLine();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/" + executionId + "/totes/" + toteCode))
                    .DELETE()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 204) {
                System.out.println("Success: Tote removed.");
            } else {
                System.err.println("Error removing tote (" + response.statusCode() + "): " + response.body());
            }
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
        }
    }

    /**
     * 4.3 Adds an item (or part of it) to a specific tote.
     * <p>
     * Prompts the user for Execution ID, Tote Code, and Item ID.
     * Optionally prompts for a specific quantity (default is full quantity) and a product ID for substitution.
     */
    private static void addItemToTote() {
        try {
            System.out.print("Enter Execution ID: ");
            Long executionId = Long.parseLong(scanner.nextLine());

            System.out.print("Enter Target Tote Code: ");
            String toteCode = scanner.nextLine();

            System.out.print("Enter Picked Item ID: ");
            Long pickedItemId = Long.parseLong(scanner.nextLine());

            System.out.print("Quantity to move (optional - press Enter for all): ");
            String qtyStr = scanner.nextLine();
            String jsonBody;

            // Check if user provided quantity
            if (qtyStr.isEmpty()) {
                System.out.print("Enter Product ID to assign (optional - press Enter for original): ");
                String prodIdStr = scanner.nextLine();

                // Case: No Quantity, No Product ID
                if (prodIdStr.isEmpty()) {
                    jsonBody = mapper.writeValueAsString(new AddItemRequest(pickedItemId, null, null));
                }
                // Case: No Quantity, Yes Product ID
                else {
                    jsonBody = mapper.writeValueAsString(new AddItemRequest(pickedItemId, null, Long.parseLong(prodIdStr)));
                }
            }
            else {
                BigDecimal qty = new BigDecimal(qtyStr);
                System.out.print("Enter Product ID to assign (optional - press Enter for original): ");
                String prodIdStr = scanner.nextLine();

                // Case: Yes Quantity, No Product ID
                if (prodIdStr.isEmpty()) {
                    jsonBody = mapper.writeValueAsString(new AddItemRequest(pickedItemId, qty, null));
                }
                // Case: Yes Quantity, Yes Product ID
                else {
                    jsonBody = mapper.writeValueAsString(new AddItemRequest(pickedItemId, qty, Long.parseLong(prodIdStr)));
                }
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/" + executionId + "/totes/" + toteCode + "/items"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 204) {
                System.out.println("Success: Item added to tote.");
            } else {
                System.err.println("Error adding item (" + response.statusCode() + "): " + response.body());
            }
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
        }
    }

    /**
     * 4.4 Moves an item between totes.
     * <p>
     * Prompts the user for Execution ID, Source Tote Code (optional), Target Tote Code, and Item ID.
     */
    private static void moveItem() {
        try {
            System.out.print("Enter Execution ID: ");
            Long executionId = Long.parseLong(scanner.nextLine());

            System.out.print("Enter Source Tote Code (optional - press Enter if unassigned): ");
            String sourceToteCodeStr = scanner.nextLine();

            System.out.print("Enter Target Tote Code: ");
            String targetToteCode = scanner.nextLine();

            System.out.print("Enter Picked Item ID: ");
            Long pickedItemId = Long.parseLong(scanner.nextLine());

            String jsonBody;
            if (sourceToteCodeStr.isEmpty()) {
                jsonBody = mapper.writeValueAsString(new MoveItemRequest(null, targetToteCode, pickedItemId));
            } else {
                jsonBody = mapper.writeValueAsString(new MoveItemRequest(sourceToteCodeStr, targetToteCode, pickedItemId));
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/" + executionId + "/items/move"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 204) {
                System.out.println("Success: Item moved.");
            } else {
                System.err.println("Error moving item (" + response.statusCode() + "): " + response.body());
            }
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
        }
    }

    /**
     * 4.5 Removes an item from a tote (returns it to work list).
     * <p>
     * Prompts the user for Execution ID, Tote Code, and Item ID.
     * Sends a DELETE request.
     */
    private static void removeItemFromTote() {
        try {
            System.out.print("Enter Execution ID: ");
            Long executionId = Long.parseLong(scanner.nextLine());

            System.out.print("Enter Tote Code: ");
            String toteCode = scanner.nextLine();

            System.out.print("Enter Item ID to remove: ");
            Long itemId = Long.parseLong(scanner.nextLine());

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/" + executionId + "/totes/" + toteCode + "/items/" + itemId))
                    .DELETE()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 204) {
                System.out.println("Success: Item removed from tote.");
            } else {
                System.err.println("Error removing item (" + response.statusCode() + "): " + response.body());
            }
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
        }
    }

    /**
     * 4.6 Cancels a picked item.
     * <p>
     * Prompts the user for Execution ID and Item ID.
     */
    private static void cancelPickedItem() {
        try {
            System.out.print("Enter Execution ID: ");
            Long executionId = Long.parseLong(scanner.nextLine());

            System.out.print("Enter Picked Item ID to cancel: ");
            Long itemId = Long.parseLong(scanner.nextLine());

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/" + executionId + "/items/" + itemId + "/cancel"))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 204) {
                System.out.println("Success: Item cancelled.");
            } else {
                System.err.println("Error cancelling item (" + response.statusCode() + "): " + response.body());
            }
        } catch (NumberFormatException e) {
            System.err.println("Error: Invalid ID format. Please enter a numeric ID.");
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
        }
    }

    /**
     * 4.7 Concludes the picking execution.
     * <p>
     * Prompts the user for Execution ID and Buffer ID.
     */
    private static void concludeExecution() {
        try {
            System.out.print("Enter Execution ID: ");
            Long executionId = Long.parseLong(scanner.nextLine());

            System.out.print("Enter Buffer ID to assign: ");
            Long bufferId = Long.parseLong(scanner.nextLine());

            String jsonBody = mapper.writeValueAsString(new ConcludeRequest(bufferId));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/" + executionId + "/conclude"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 204) {
                System.out.println("Success: Execution concluded.");
            } else {
                System.err.println("Error concluding execution (" + response.statusCode() + "): " + response.body());
            }
        } catch (NumberFormatException e) {
            System.err.println("Error: Invalid ID format. Please enter a numeric ID.");
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
        }
    }

    /**
     * 4.8 Gets the optimized picking route.
     * <p>
     * Prompts the user for Execution ID.
     * Retrieves the ordered list of items and displays a table.
     */
    private static void getPickingRoute() {
        try {
            System.out.print("Enter Execution ID: ");
            Long executionId = Long.parseLong(scanner.nextLine());

            // Using default start node "START" as per controller
            String startNode = "START";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/" + executionId + "/route?startNode=" + startNode))
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                List<PickingStepDTO> steps = mapper.readValue(response.body(), new TypeReference<List<PickingStepDTO>>() {});

                System.out.println("\n--- Optimized Picking Route ---");
                if (steps.isEmpty()) {
                    System.out.println("No items to pick.");
                } else {
                    System.out.printf("%-6s | %-15s | %-10s | %-6s | %-6s | %-10s%n", "ItemID", "Product", "Aisle", "Unit", "Pos", "Qty");
                    System.out.println("-----------------------------------------------------------");
                    for (PickingStepDTO step : steps) {
                        System.out.printf("%-6s | %-15s | %-10s | %-6s | %-6s | %-10s%n",
                                step.pickedItemId,
                                step.productName,
                                step.aisle,
                                step.unit,
                                step.position,
                                step.quantityToPick);
                    }
                }
            } else {
                System.err.println("Error fetching route (" + response.statusCode() + "): " + response.body());
            }
        } catch (NumberFormatException e) {
            System.err.println("Error: Invalid Execution ID format.");
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
        }
    }

    // --------------------------------------------------
    // Grouping Operations Methods
    // --------------------------------------------------

    /**
     * Displays the submenu for grouping operations.
     */
    private static void groupingMenu() {
        boolean inGroupingMenu = true;
        while (inGroupingMenu) {
            System.out.println("\n--------------------------------------------------");
            System.out.println("       Grouping Operations                       ");
            System.out.println("--------------------------------------------------");
            System.out.println("1. Move Tote to Destination");
            System.out.println("2. Conclude Grouping");
            System.out.println("0. Back to Main Menu");
            System.out.print("Select an option: ");

            String choice = scanner.nextLine();
            switch (choice) {
                case "1":
                    moveToteToDestination();
                    break;
                case "2":
                    concludeGroupingExecution();
                    break;
                case "0":
                    inGroupingMenu = false;
                    break;
                default:
                    System.out.println("Invalid choice. Please try again.");
            }
        }
    }

    /**
     * 5.1 Moves a physical Tote to the destination buffer.
     * <p>
     * Prompts the user for Execution ID and Tote Code.
     * Sends a POST request to the move-to-destination endpoint.
     */
    private static void moveToteToDestination() {
        try {
            System.out.print("Enter Grouping Execution ID: ");
            Long executionId = Long.parseLong(scanner.nextLine());

            System.out.print("Enter Tote Code to move: ");
            String toteCode = scanner.nextLine();

            // No body required for this endpoint, parameters are in the path
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/" + executionId + "/totes/" + toteCode + "/move-to-destination"))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 204) {
                System.out.println("Success: Tote moved to destination.");
            } else {
                System.err.println("Error moving tote (" + response.statusCode() + "): " + response.body());
            }
        } catch (NumberFormatException e) {
            System.err.println("Error: Invalid Execution ID format.");
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
        }
    }

    /**
     * 5.2 Concludes the grouping execution.
     * <p>
     * Prompts the user for Execution ID.
     * Sends a POST request to the conclude-grouping endpoint.
     */
    private static void concludeGroupingExecution() {
        try {
            System.out.print("Enter Grouping Execution ID: ");
            Long executionId = Long.parseLong(scanner.nextLine());

            // No body required for this endpoint
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/" + executionId + "/conclude-grouping"))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 204) {
                System.out.println("Success: Grouping execution concluded.");
            } else {
                System.err.println("Error concluding grouping execution (" + response.statusCode() + "): " + response.body());
            }
        } catch (NumberFormatException e) {
            System.err.println("Error: Invalid Execution ID format.");
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------
    // Data Transfer Objects (DTOs)
    // ---------------------------------------------------------

    /**
     * DTO representing the summary of a Handling Order.
     */
    public static class HandlingOrderSummaryDTO {
        public Long id;
        public String type;
        public String status;
        public LocalDateTime requestedStart;
        public LocalDateTime requestedEnd;
    }

    /**
     * DTO representing the request to reserve an order.
     */
    public static class ReserveRequest {
        public String badgeId;

        public ReserveRequest(String badgeId) {
            this.badgeId = badgeId;
        }
    }

    /**
     * DTO for adding a tote to an execution.
     */
    public static class AddToteRequest {
        public String toteCode;
        public TemperatureZone usage;

        public AddToteRequest(String toteCode, TemperatureZone usage) {
            this.toteCode = toteCode;
            this.usage = usage;
        }
    }

    /**
     * DTO for adding an item to a tote.
     */
    public static class AddItemRequest {
        public Long pickedItemId;
        public BigDecimal quantity;
        public Long productId;

        public AddItemRequest(Long pickedItemId, BigDecimal quantity, Long productId) {
            this.pickedItemId = pickedItemId;
            this.quantity = quantity;
            this.productId = productId;
        }
    }

    /**
     * DTO for moving an item between totes.
     */
    public static class MoveItemRequest {
        public String sourceToteCode;
        public String targetToteCode;
        public Long pickedItemId;

        public MoveItemRequest(String sourceToteCode, String targetToteCode, Long pickedItemId) {
            this.sourceToteCode = sourceToteCode;
            this.targetToteCode = targetToteCode;
            this.pickedItemId = pickedItemId;
        }
    }

    /**
     * DTO for concluding an execution.
     */
    public static class ConcludeRequest {
        public Long bufferId;

        public ConcludeRequest(Long bufferId) {
            this.bufferId = bufferId;
        }
    }

    /**
     * DTO representing a step in the optimized picking route.
     */
    public static class PickingStepDTO {
        public Long pickedItemId;
        public String aisle;
        public String unit;
        public String position;
        public String productCode;
        public String productName;
        public BigDecimal quantityToPick;
    }
}