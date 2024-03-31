package serverless.ShoppingCart;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.google.gson.Gson;
import serverless.lib.ConfigManager;
import serverless.lib.ResponseTransformer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GetCart implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private static final Gson gson = new Gson();
    private static final int PAGE_SIZE = 3;
    private static ConfigManager configManager;
    private static DynamoDbClient dynamoDB;

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

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
        initializeResources();
        return getCart(event);
    }

    public Map<String, Object> getCart(Map<String, Object> event) {

        String userId = "b2693157-8200-4882-9fcc-7f7f2c5d77b8";

        QueryRequest queryRequest = QueryRequest.builder()
                .tableName("CartDB")
                .keyConditionExpression("UserId = :v_id")
                .expressionAttributeValues(
                        Map.of(":v_id", AttributeValue.builder().s(userId).build())
                )
                .build();
        QueryResponse queryResponse = dynamoDB.query(queryRequest);

        // Assuming the business logic is to fetch the first cart item
        if (queryResponse.items().isEmpty() || queryResponse.items().get(0).get("OrderList").s().isEmpty()) {
            return Collections.singletonMap("cart", Collections.emptyList());
        }

        Map<String, AttributeValue> userCart = queryResponse.items().get(0);
        List<Map<String, String>> items = ResponseTransformer.transformCartItems(Collections.singletonList(userCart));
        List<Map<String, String>> products = gson.fromJson(items.get(0).get("products"), List.class);

        int totalPages = (int) Math.ceil((double) products.size() / PAGE_SIZE);
        int page = Integer.parseInt(((Map<String, String>) event.get("queryStringParameters")).getOrDefault("page", "1"));
        int start = (page - 1) * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, products.size());
        List<Map<String, String>> pagedProducts = products.subList(start, end);

        Map<String, Object> response = new HashMap<>();
        Map<String, Object> responseBody = new HashMap<>();
        responseBody.put("products", pagedProducts);
        responseBody.put("totalPages", totalPages);
        responseBody.put("totalPrice", items.get(0).get("TotalPrice")); // Assuming 'TotalPrice' is a string
        response.put("statusCode", 200);

        return Collections.singletonMap("cart", responseBody);
    }
}
