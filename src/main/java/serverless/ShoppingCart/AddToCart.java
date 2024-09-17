package serverless.ShoppingCart;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.xray.AWSXRay;
import com.amazonaws.xray.entities.Subsegment;
import com.auth0.jwk.JwkException;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import serverless.lib.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import java.net.MalformedURLException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import serverless.lib.LambdaDocumentationAnnotations.*;

public class AddToCart implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private static final Gson gson = new Gson();
    private static final Logger logger = Logger.getLogger(AddToCart.class.getName());
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
            summary = "Add to Cart",
            description = "Allows users to add a product to their shopping cart or update the quantity of an existing product.",
            path = "/cart",
            method = "POST"
    )
    @LambdaRequestBody(
            description = "Details for adding a product to the cart, including productId and quantity.",
            content = @LambdaContent(
                    mediaType = "application/json",
                    schema = @LambdaSchema(
                            example = "{ \"productId\": \"a9abe32e-9bd6-43aa-bc00-9044a27b858b\", \"quantity\": \"2\" }"
                    )
            )
    )
    @LambdaSecurityRequirement(name = "BearerAuth")
    @LambdaAPIResponses({
            @LambdaAPIResponse(responseCode = 200, description = "Product added to cart successfully."),
            @LambdaAPIResponse(responseCode = 403, description = "Invalid token."),
            @LambdaAPIResponse(responseCode = 500, description = "Failed to add to cart.")
    })
    @Override
    public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
        try {
            Subsegment configSubsegment = AWSXRay.beginSubsegment("collectConfigParams");
            String CART_TABLE = (String) configManager.get("CART_TABLE");
            String PRODUCT_TABLE = (String) configManager.get("PRODUCT_TABLE");
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
            } catch (JWTVerificationException | MalformedURLException | JwkException e) {
                logger.log(Level.SEVERE, "Failed to authenticate user", e);
                return ResponseGenerator.generateResponse(403, "Invalid token.");
            } finally {
                AWSXRay.endSubsegment();
            }

            Subsegment obtainProductIDandQTYDetailsSubsegment = AWSXRay.beginSubsegment("ObtainingProductDetails");
            Map<String, String> body = gson.fromJson((String) event.get("body"), HashMap.class);
            String productId = body.get("productId");
            String quantity = body.get("quantity");
            obtainProductIDandQTYDetailsSubsegment.putMetadata("productId", productId);
            obtainProductIDandQTYDetailsSubsegment.putMetadata("quantity", quantity);
            AWSXRay.endSubsegment();

            Subsegment updateQTYandPriceSubsegment = AWSXRay.beginSubsegment("UpdateQuantityAndPrice");
            Map<String, AttributeValue> key = new HashMap<>();
            key.put("UserId", AttributeValue.builder().s(userId).build());
            GetItemRequest getItemRequest = GetItemRequest.builder()
                    .tableName(CART_TABLE)
                    .key(key)
                    .build();
            GetItemResponse getItemResponse = dynamoDB.getItem(getItemRequest);

            if (getItemResponse.item() == null || !getItemResponse.item().containsKey("OrderList") || !getItemResponse.item().containsKey("TotalPrice")) {
                Map<String, String> expressionAttributeNames = new HashMap<>();
                expressionAttributeNames.put("#O", "OrderList");
                expressionAttributeNames.put("#T", "TotalPrice");
                Map<String, AttributeValue> expressionAttributeValues = new HashMap<>();
                expressionAttributeValues.put(":ol", AttributeValue.builder().s(";").build()); // Initialize an empty OrderList
                expressionAttributeValues.put(":t", AttributeValue.builder().n("0.0").build()); // Initialize TotalPrice
                UpdateItemRequest updateItemRequest = UpdateItemRequest.builder()
                        .tableName(CART_TABLE)
                        .key(key)
                        .updateExpression("SET #O = :ol, #T = :t")
                        .expressionAttributeNames(expressionAttributeNames)
                        .expressionAttributeValues(expressionAttributeValues)
                        .build();
                dynamoDB.updateItem(updateItemRequest);
                // Re-fetch the item after initializing OrderList and TotalPrice
                getItemResponse = dynamoDB.getItem(getItemRequest);
            }

            // Update the OrderList and quantity
            String orderListStr = getItemResponse.item().get("OrderList").s();
            if (!orderListStr.endsWith(";")) {
                orderListStr += ";";
            }
            String[] orderList = orderListStr.split(";");
            boolean found = false;
            for (int i = 0; i < orderList.length; i++) {
                String[] parts = orderList[i].split(":");
                if (parts[0].equals(productId)) {
                    // Found the product, update the quantity
                    parts[1] = String.valueOf(Integer.parseInt(quantity));
                    orderList[i] = String.join(":", parts);
                    found = true;
                    break;
                }
            }
            if (!found) {
                orderList = Arrays.copyOf(orderList, orderList.length + 1);
                orderList[orderList.length - 1] = productId + ":" + quantity;
            }
            orderListStr = String.join(";", orderList);

            // Update the OrderList in the cart
            Map<String, String> expressionAttributeNames = new HashMap<>();
            expressionAttributeNames.put("#O", "OrderList");
            Map<String, AttributeValue> expressionAttributeValues = new HashMap<>();
            expressionAttributeValues.put(":ol", AttributeValue.builder().s(orderListStr).build());
            UpdateItemRequest updateItemRequest = UpdateItemRequest.builder()
                    .tableName(CART_TABLE)
                    .key(key)
                    .updateExpression("SET #O = :ol")
                    .expressionAttributeNames(expressionAttributeNames)
                    .expressionAttributeValues(expressionAttributeValues)
                    .build();
            dynamoDB.updateItem(updateItemRequest);

            // Calculate the total price
            double totalPrice = 0.0;
            for (String order : orderList) {
                String[] parts = order.split(":");
                String productIdOrder = parts[0];
                int quantityOrder = Integer.parseInt(parts[1]);
                Map<String, AttributeValue> productKey = new HashMap<>();
                productKey.put("productId", AttributeValue.builder().s(productIdOrder).build());
                GetItemRequest getProductRequest = GetItemRequest.builder()
                        .tableName(PRODUCT_TABLE)
                        .key(productKey)
                        .attributesToGet("discountPrice")
                        .build();
                GetItemResponse getProductResponse = dynamoDB.getItem(getProductRequest);
                double productPrice = Double.parseDouble(getProductResponse.item().get("discountPrice").n());
                totalPrice += productPrice * quantityOrder;
            }

            // Update the TotalPrice in the CartDB table
            Map<String, String> expressionAttributeNamesTotal = new HashMap<>();
            expressionAttributeNamesTotal.put("#T", "TotalPrice");
            Map<String, AttributeValue> expressionAttributeValuesTotal = new HashMap<>();
            expressionAttributeValuesTotal.put(":t", AttributeValue.builder().n(String.valueOf(totalPrice)).build());
            UpdateItemRequest updateTotalPriceRequest = UpdateItemRequest.builder()
                    .tableName(CART_TABLE)
                    .key(key)
                    .updateExpression("SET #T = :t")
                    .expressionAttributeNames(expressionAttributeNamesTotal)
                    .expressionAttributeValues(expressionAttributeValuesTotal)
                    .build();
            dynamoDB.updateItem(updateTotalPriceRequest);

            // Fetch the updated cart item
            GetItemResponse updatedItemResponse = dynamoDB.getItem(getItemRequest);
            AWSXRay.endSubsegment();

            logger.info("Update quantity and price successful");
            return ResponseGenerator.generateResponse(200, gson.toJson(ResponseTransformer.transformItem(updatedItemResponse.item())));
        } catch (DynamoDbException e) {
            Subsegment failureSubsegment = AWSXRay.beginSubsegment("AddToCartFailed");
            logger.log(Level.SEVERE, "Failed to add to cart", e);
            AWSXRay.endSubsegment();
            throw new RuntimeException("Failed to add to cart", e);
        }
    }
}
