package serverless.ShoppingCart;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.xray.AWSXRay;
import com.amazonaws.xray.entities.Subsegment;
import com.auth0.jwk.JwkException;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.google.gson.Gson;
import serverless.lib.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import java.net.MalformedURLException;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import serverless.lib.LambdaDocumentationAnnotations.*;

public class DeleteFromCart implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private static final Gson gson = new Gson();
    private static final Logger logger = Logger.getLogger(DeleteFromCart.class.getName());
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
            summary = "Delete From Cart",
            description = "Allows users to delete a product from their shopping cart.",
            path = "/cart/{productId}",
            method = "DELETE"
    )
    @LambdaParameters({
            @LambdaParameter(name = "productId", description = "Unique identifier for the product to be deleted from the cart", in = LambdaDocumentationAnnotations.ParameterIn.PATH, example = "69c52025-fcd6-4fc3-a3c0-5a2a915607c4")
    })
    @LambdaSecurityRequirement(name = "BearerAuth")
    @LambdaAPIResponses({
            @LambdaAPIResponse(responseCode = 200, description = "Product deleted from cart successfully."),
            @LambdaAPIResponse(responseCode = 403, description = "Invalid token."),
            @LambdaAPIResponse(responseCode = 404, description = "Product cannot be deleted because it is not present in the database."),
            @LambdaAPIResponse(responseCode = 500, description = "Failed to delete from cart.")
    })
    @Override
    public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
        try {
            // Configuration Management
            Subsegment configSubsegment = AWSXRay.beginSubsegment("collectConfigParams");
            String CART_TABLE = (String) configManager.get("CART_TABLE");
            String PRODUCT_TABLE = (String) configManager.get("PRODUCT_TABLE");
            String ISSUER = (String) configManager.get("ISSUER");
            AWSXRay.endSubsegment();

            DynamoDbClient dynamoDB = DynamoDbClient.builder()
                    .region(Region.of((String) configManager.get("DYNAMO_REGION")))
                    .build();

            // Authentication
            Subsegment authenticationSubsegment = AWSXRay.beginSubsegment("authenticatingUser");
            String authHeader = ((Map<String, String>) event.get("headers")).get("Authorization");
            String token = "";
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                token = authHeader.substring("Bearer ".length());
            }
            String userId;
            try {
                userId = TokenVerifier.verifyToken(token, ISSUER);
            } catch (JWTVerificationException | MalformedURLException | JwkException e) {
                logger.log(Level.SEVERE, "Failed to authenticate user", e);
                return ResponseGenerator.generateResponse(403, "Invalid token.");
            } finally {
                AWSXRay.endSubsegment();
            }

            // Obtain Product ID for Deletion
            Subsegment obtainProductIDSubsegment = AWSXRay.beginSubsegment("ObtainingProductID");
            Map<String, String> pathParameters = (Map<String, String>) event.get("pathParameters");
            String proxyValue = pathParameters.get("proxy");
            String[] partsing = proxyValue.split("/");
            String productIdToDelete = partsing[partsing.length - 1];
            obtainProductIDSubsegment.putMetadata("productIdToDelete", productIdToDelete);
            AWSXRay.endSubsegment();

            // Construct the key for the item
            Subsegment deleteProductFromCartSubsegment = AWSXRay.beginSubsegment("DeleteProductFromCart");
            Map<String, AttributeValue> key = new HashMap<>();
            key.put("UserId", AttributeValue.builder().s(userId).build());

            // Fetch current cart details
            GetItemRequest getItemRequest = GetItemRequest.builder()
                    .tableName(CART_TABLE)
                    .key(key)
                    .build();
            GetItemResponse getItemResponse = dynamoDB.getItem(getItemRequest);

            // Get the OrderList and current TotalPrice
            String orderListStr = getItemResponse.item().get("OrderList").s();
            double totalPrice = Double.parseDouble(getItemResponse.item().get("TotalPrice").n());

            // Split the OrderList and remove the specified product
            String[] orderList = orderListStr.split(";");
            StringBuilder updatedOrderListStr = new StringBuilder();
            int quantityToDelete = 0;
            for (String order : orderList) {
                String[] parts = order.split(":");
                if (parts[0].equals(productIdToDelete)) {
                    quantityToDelete = Integer.parseInt(parts[1]);
                } else {
                    updatedOrderListStr.append(order).append(";");
                }
            }

            // Fetch the product price
            Map<String, AttributeValue> productKey = new HashMap<>();
            productKey.put("productId", AttributeValue.builder().s(productIdToDelete).build());
            GetItemRequest getProductRequest = GetItemRequest.builder()
                    .tableName(PRODUCT_TABLE)
                    .key(productKey)
                    .build();
            GetItemResponse getProductResponse = dynamoDB.getItem(getProductRequest);

            // Check if the product exists in the database
            if (getProductResponse.item() == null || getProductResponse.item().isEmpty()) {
                logger.info("Product with given productId does not exist in the database.");
                return ResponseGenerator.generateResponse(404, gson.toJson("Product cannot be deleted because it is not present in the database."));
            }
            double productPrice = Double.parseDouble(getProductResponse.item().get("discountPrice").n());

            // Update the total price
            double updatedTotalPrice = totalPrice - productPrice * quantityToDelete;

            // Define updated attributes for the cart
            AttributeValue updatedOrderListAttr = AttributeValue.builder().s(updatedOrderListStr.toString()).build();
            AttributeValue updatedTotalPriceAttr = AttributeValue.builder().n(String.valueOf(updatedTotalPrice)).build();
            Map<String, AttributeValueUpdate> updatedItemAttrs = new HashMap<>();
            updatedItemAttrs.put("OrderList", AttributeValueUpdate.builder().value(updatedOrderListAttr).action(AttributeAction.PUT).build());
            updatedItemAttrs.put("TotalPrice", AttributeValueUpdate.builder().value(updatedTotalPriceAttr).action(AttributeAction.PUT).build());

            // Update the cart in the database
            UpdateItemRequest updateItemRequest = UpdateItemRequest.builder()
                    .tableName(CART_TABLE)
                    .key(key)
                    .attributeUpdates(updatedItemAttrs)
                    .build();
            dynamoDB.updateItem(updateItemRequest);
            AWSXRay.endSubsegment();

            // Prepare the response
            Map<String, Object> response = new HashMap<>();
            response.put("TotalPrice", updatedTotalPrice);
            response.put("message", "Product deleted from cart successfully");

            logger.info("Product deleted from cart successfully");
            return ResponseGenerator.generateResponse(200, gson.toJson(response));
        } catch (DynamoDbException e) {
            Subsegment failureSubsegment = AWSXRay.beginSubsegment("DeleteFromCartFailed");
            logger.log(Level.SEVERE, "Failed to delete from cart", e);
            AWSXRay.endSubsegment();
            throw new RuntimeException("Failed to delete from cart", e);
        }
    }
}
