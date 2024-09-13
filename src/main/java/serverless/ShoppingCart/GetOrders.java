package serverless.ShoppingCart;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.xray.entities.Subsegment;
import com.google.gson.Gson;
import serverless.lib.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import com.amazonaws.xray.AWSXRay;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import serverless.lib.LambdaDocumentationAnnotations.*;


public class GetOrders implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private static final Gson gson = new Gson();
    private static final Logger logger = Logger.getLogger(GetOrders.class.getName());
    private static ConfigManager configManager;
    private static DynamoDbClient dynamoDB;
    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @LambdaOperation(
            summary = "Fetch orders for the user",
            description = "Fetches the list of orders placed by the authenticated user with pagination support.",
            path = "/orders",
            method = "GET"
    )
    @LambdaAPIResponses({
            @LambdaAPIResponse(responseCode = 200, description = "Orders successfully fetched."),
            @LambdaAPIResponse(responseCode = 401, description = "Unauthorized: Invalid token."),
            @LambdaAPIResponse(responseCode = 500, description = "Unable to fetch orders. Please try again later.")
    })
    @LambdaParameters({
            @LambdaParameter(name = "page", description = "Page number for pagination", in = LambdaDocumentationAnnotations.ParameterIn.QUERY, example = "1"),
            @LambdaParameter(name = "pageSize", description = "Number of orders per page", in = LambdaDocumentationAnnotations.ParameterIn.QUERY, example = "10")
    })
    @LambdaSecurityRequirement(name = "BearerAuth")
    @Override
    public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
        try {
            initializeResources();
            return getOrders(event);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error fetching orders", e);
            return ResponseGenerator.generateResponse(500, gson.toJson("Error fetching orders."));
        }
    }

    private synchronized static void initializeResources() {
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

    public Map<String, Object> getOrders(Map<String, Object> event) {
        try {
            Subsegment configSubsegment = AWSXRay.beginSubsegment("collectConfigParams");
            String REGION = (String) configManager.get("DYNAMO_REGION");
            String ISSUER = (String) configManager.get("ISSUER");
            String ORDERS_TABLE = (String) configManager.get("ORDERS_TABLE");
            AWSXRay.endSubsegment();

            dynamoDB = DynamoDbClient.builder()
                    .region(Region.of(REGION))
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
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Failed to authenticate user", e);
                return ResponseGenerator.generateResponse(401, gson.toJson("Unauthorized: Invalid token."));
            } finally {
                AWSXRay.endSubsegment();
            }

            Subsegment getOrdersSubsegment = AWSXRay.beginSubsegment("obtainingOrders");
            Map<String, String> queryStringParameters = (Map<String, String>) event.get("queryStringParameters");
            int page = Integer.parseInt(queryStringParameters.getOrDefault("page", "1"));
            int pageSize = Integer.parseInt(queryStringParameters.getOrDefault("pageSize", "10"));

            QueryRequest queryRequest = QueryRequest.builder()
                    .tableName(ORDERS_TABLE)
                    .keyConditionExpression("UserId = :v_userId")
                    .expressionAttributeValues(Map.of(":v_userId", AttributeValue.builder().s(userId).build()))
                    .limit(pageSize)
                    .build();
            QueryResponse queryResponse = dynamoDB.query(queryRequest);
            AWSXRay.endSubsegment();

            Subsegment processingSubsegment = AWSXRay.beginSubsegment("processingResults");
            List<Map<String, AttributeValue>> items = queryResponse.items();
            int totalPages = (int) Math.ceil((double) items.size() / pageSize);
            int start = (page - 1) * pageSize;
            int end = Math.min(start + pageSize, items.size());
            List<Map<String, AttributeValue>> pagedItems = items.subList(start, end);
            List<Map<String, String>> orders = new ArrayList<>();
            for (Map<String, AttributeValue> item : pagedItems) {
                orders.add(ResponseTransformer.transformOrderItem(item));
            }
            orders.forEach(order -> {
                Instant timestamp = Instant.parse(order.get("TimeStamp"));
                String formattedTimestamp = formatter.format(timestamp.atZone(ZoneId.systemDefault()));
                order.put("TimeStamp", formattedTimestamp);
            });

            AWSXRay.endSubsegment();

            Map<String, Object> responseBody = new HashMap<>();
            responseBody.put("orders", orders);
            responseBody.put("totalPages", totalPages);
            AWSXRay.endSubsegment();

            logger.info("Successfully obtained user's orders");
            return ResponseGenerator.generateResponse(200, gson.toJson(responseBody));

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to obtain user's orders", e);
            throw new RuntimeException("Failed to obtain user's orders", e);
        }
    }

}
