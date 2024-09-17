package serverless.ShoppingCart;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.xray.AWSXRay;
import com.amazonaws.xray.entities.Subsegment;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import serverless.lib.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import java.net.MalformedURLException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import serverless.lib.LambdaDocumentationAnnotations.*;

public class Checkout implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private static final Gson gson = new Gson();
    private static final Logger logger = Logger.getLogger(Checkout.class.getName());
    private static ConfigManager configManager;
    private static DynamoDbClient dynamoDB;

    static {
        initializeResources();
    }

    private static synchronized void initializeResources() {
        if (configManager == null) {
            configManager = new ConfigManager();
        }
        if (dynamoDB == null) {
            String REGION = (String) configManager.get("DYNAMO_REGION");
            dynamoDB = DynamoDbClient.builder()
                    .region(Region.of(REGION))
                    .build();
        }
    }

    @LambdaOperation(
            summary = "Checkout",
            description = "Allows users to finalize their cart and place an order.",
            path = "/checkout",
            method = "POST"
    )
    @LambdaRequestBody(
            description = "Details required for completing the checkout process, including personal and order information.",
            content = @LambdaContent(
                    mediaType = "application/json",
                    schema = @LambdaSchema(
                            example = "{ \"email\": \"john.doe@example.com\", \"name\": \"John\", \"surname\": \"Doe\", \"address\": \"123 Street\", \"telNumber\": \"1234567890\", \"orderList\": \"product1:2;product2:1;\", \"totalPrice\": \"150.00\" }"
                    )
            )
    )
    @LambdaSecurityRequirement(name = "BearerAuth")
    @LambdaAPIResponses({
            @LambdaAPIResponse(responseCode = 200, description = "Payment successful."),
            @LambdaAPIResponse(responseCode = 403, description = "Invalid token."),
            @LambdaAPIResponse(responseCode = 500, description = "Failed to process checkout.")
    })
    @Override
    public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
        try {
            Subsegment configSubsegment = AWSXRay.beginSubsegment("collectConfigParams");
            String ORDERS_TABLE = (String) configManager.get("ORDERS_TABLE");
            String CART_TABLE = (String) configManager.get("CART_TABLE");
            String ISSUER = (String) configManager.get("ISSUER");
            AWSXRay.endSubsegment();

            DynamoDbClient dynamoDB = DynamoDbClient.builder()
                    .region(Region.of((String) configManager.get("DYNAMO_REGION")))
                    .build();

            Subsegment authenticationSubsegment = AWSXRay.beginSubsegment("authenticatingUser");
            String authHeader = ((Map<String, String>) event.get("headers")).get("Authorization");
            String token = "";
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                token = authHeader.substring("Bearer ".length());
            }
            String userId;
            try {
                userId = TokenVerifier.verifyToken(token, ISSUER);
            } catch (JWTVerificationException | MalformedURLException e) {
                logger.log(Level.SEVERE, "Failed to authenticate user", e);
                return ResponseGenerator.generateResponse(403, "Invalid token.");
            } finally {
                AWSXRay.endSubsegment();
            }

            Subsegment obtainOrderDetailsSubsegment = AWSXRay.beginSubsegment("ObtainingOrderDetails");
            Map<String, String> body = gson.fromJson((String) event.get("body"), new TypeToken<Map<String, String>>() {}.getType());
            String email = body.get("email");
            String name = body.get("name");
            String surname = body.get("surname");
            String address = body.get("address");
            String telNumber = body.get("telNumber");
            String orderListStr = body.get("orderList");
            String totalPrice = body.get("totalPrice");
            AWSXRay.endSubsegment();

            Subsegment putOrderDetailsSubsegment = AWSXRay.beginSubsegment("StoreOrderDetails");
            String hashKeyInput = userId + orderListStr + Instant.now().toString();
            String hashKey = generateHashKey(hashKeyInput);
            putOrderDetailsSubsegment.putMetadata("email", email);
            putOrderDetailsSubsegment.putMetadata("hashKey", hashKey);
            String timeStamp = Instant.now().toString();

            Map<String, AttributeValue> itemValues = new HashMap<>();
            itemValues.put("UserId", AttributeValue.builder().s(userId).build());
            itemValues.put("HashKey", AttributeValue.builder().s(hashKey).build());
            itemValues.put("Email", AttributeValue.builder().s(email).build());
            itemValues.put("Name", AttributeValue.builder().s(name).build());
            itemValues.put("Surname", AttributeValue.builder().s(surname).build());
            itemValues.put("Address", AttributeValue.builder().s(address).build());
            itemValues.put("TelNumber", AttributeValue.builder().s(telNumber).build());
            itemValues.put("OrderList", AttributeValue.builder().s(orderListStr).build());
            itemValues.put("TotalPrice", AttributeValue.builder().n(totalPrice).build());
            itemValues.put("OrderStatus", AttributeValue.builder().s("COMPLETED").build());
            itemValues.put("TimeStamp", AttributeValue.builder().s(timeStamp).build());

            PutItemRequest putItemRequest = PutItemRequest.builder()
                    .tableName(ORDERS_TABLE)
                    .item(itemValues)
                    .build();
            dynamoDB.putItem(putItemRequest);
            AWSXRay.endSubsegment();

            Subsegment deleteCartSubsegment = AWSXRay.beginSubsegment("DeleteUserCart");
            Map<String, AttributeValue> keyToDelete = new HashMap<>();
            keyToDelete.put("UserId", AttributeValue.builder().s(userId).build());

            DeleteItemRequest deleteItemRequest = DeleteItemRequest.builder()
                    .tableName(CART_TABLE)
                    .key(keyToDelete)
                    .build();
            dynamoDB.deleteItem(deleteItemRequest);
            AWSXRay.endSubsegment();

            logger.info("Payment successful");
            return ResponseGenerator.generateResponse(200, gson.toJson("Payment successful"));
        } catch (Exception e) {
            Subsegment failureSubsegment = AWSXRay.beginSubsegment("CheckoutFailed");
            logger.log(Level.SEVERE, "Failed to process checkout", e);
            AWSXRay.endSubsegment();
            throw new RuntimeException("Failed to process checkout", e);
        }
    }

    private String generateHashKey(String input) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(hash);
    }
}
