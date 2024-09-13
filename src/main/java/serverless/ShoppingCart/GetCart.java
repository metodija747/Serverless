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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import serverless.lib.LambdaDocumentationAnnotations.*;

public class GetCart implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private static final Gson gson = new Gson();
    private static final Logger logger = Logger.getLogger(GetCart.class.getName());
    private static ConfigManager configManager;
    private static DynamoDbClient dynamoDB;
    private static final int DEFAULT_PAGE_SIZE = 10;

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
            summary = "Get Cart",
            description = "Allows users to retrieve their shopping cart with paginated product listings.",
            path = "/cart",
            method = "GET"
    )
    @LambdaParameters({
            @LambdaParameter(name = "page", description = "Page number for pagination", in = LambdaDocumentationAnnotations.ParameterIn.QUERY, example = "1"),
            @LambdaParameter(name = "pageSize", description = "Number of products per page", in = LambdaDocumentationAnnotations.ParameterIn.QUERY, example = "10")
    })
    @LambdaSecurityRequirement(name = "BearerAuth")
    @LambdaAPIResponses({
            @LambdaAPIResponse(responseCode = 200, description = "Successfully obtained user's cart."),
            @LambdaAPIResponse(responseCode = 401, description = "Invalid token."),
            @LambdaAPIResponse(responseCode = 500, description = "Failed to obtain user's cart.")
    })
    @Override
    public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
        try {
            Subsegment configSubsegment = AWSXRay.beginSubsegment("collectConfigParams");
            String CART_TABLE = (String) configManager.get("CART_TABLE");
            String ISSUER = (String) configManager.get("ISSUER");
            AWSXRay.endSubsegment();

            DynamoDbClient dynamoDB = DynamoDbClient.builder()
                    .region(Region.of((String) configManager.get("DYNAMO_REGION")))
                    .build();

            Subsegment authenticationSubsegment = AWSXRay.beginSubsegment("authenticatingUser");
            String token = ((Map<String, String>) event.get("headers")).get("Authorization");
            String userId;
            try {
                userId = TokenVerifier.verifyToken(token, ISSUER);
            } catch (JWTVerificationException | MalformedURLException e) {
                logger.log(Level.SEVERE, "Failed to authenticate user. Exception: " + e.getMessage(), e);
                return ResponseGenerator.generateResponse(401, "Invalid token: " + e.getMessage());
            } finally {
                AWSXRay.endSubsegment();
            }

            Subsegment getCartSubsegment = AWSXRay.beginSubsegment("obtainingCart");
            QueryRequest queryRequest = QueryRequest.builder()
                    .tableName(CART_TABLE)
                    .keyConditionExpression("UserId = :v_id")
                    .expressionAttributeValues(
                            Map.of(":v_id", AttributeValue.builder().s(userId).build())
                    )
                    .build();
            QueryResponse queryResponse = dynamoDB.query(queryRequest);

            if (queryResponse.items().isEmpty() || queryResponse.items().get(0).get("OrderList").s().isEmpty()) {
                Map<String, Object> emptyCartResponse = new HashMap<>();
                emptyCartResponse.put("products", Collections.emptyList());
                emptyCartResponse.put("totalPages", 0);
                emptyCartResponse.put("totalPrice", 0);

                logger.info("User's cart is empty");
                return ResponseGenerator.generateResponse(200, gson.toJson(emptyCartResponse));
            }

            Map<String, AttributeValue> userCart = queryResponse.items().get(0);
            List<Map<String, String>> items = ResponseTransformer.transformCartItems(Collections.singletonList(userCart));
            List<Map<String, String>> products = gson.fromJson(items.get(0).get("products"), new TypeToken<List<Map<String, String>>>() {}.getType());

            Map<String, String> queryParams = (Map<String, String>) event.get("queryStringParameters");
            int page = Integer.parseInt(queryParams.getOrDefault("page", "1"));
            int pageSize = Integer.parseInt(queryParams.getOrDefault("pageSize", String.valueOf(DEFAULT_PAGE_SIZE)));
            int totalPages = (int) Math.ceil((double) products.size() / pageSize);
            int start = (page - 1) * pageSize;
            int end = Math.min(start + pageSize, products.size());
            List<Map<String, String>> pagedProducts = products.subList(start, end);
            AWSXRay.endSubsegment();

            Map<String, Object> responseBody = new HashMap<>();
            responseBody.put("products", pagedProducts);
            responseBody.put("totalPages", totalPages);
            responseBody.put("totalPrice", items.get(0).get("TotalPrice"));

            logger.info("Successfully obtained user's cart");
            return ResponseGenerator.generateResponse(200, gson.toJson(responseBody));
        } catch (Exception e) {
            Subsegment failureSubsegment = AWSXRay.beginSubsegment("FailedToObtainCart");
            logger.log(Level.SEVERE, "Failed to obtain user's cart", e);
            AWSXRay.endSubsegment();
            throw new RuntimeException("Failed to obtain user's cart", e);
        }
    }
}
