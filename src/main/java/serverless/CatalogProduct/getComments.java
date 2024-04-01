package serverless.CatalogProduct;

        import com.amazonaws.services.lambda.runtime.Context;
        import com.amazonaws.services.lambda.runtime.RequestHandler;
        import com.amazonaws.xray.entities.Subsegment;
        import com.google.gson.Gson;
        import com.google.gson.reflect.TypeToken;
        import serverless.lib.*;
        import software.amazon.awssdk.regions.Region;
        import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
        import software.amazon.awssdk.services.dynamodb.model.*;
        import com.amazonaws.xray.AWSXRay;
        import java.util.*;
        import java.util.logging.Level;
        import java.util.logging.Logger;
        import java.util.stream.Collectors;

        import serverless.lib.LambdaDocumentationAnnotations.*;


public class getComments implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private static final Gson gson = new Gson();
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

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
        return getProductComments(event);
    }

    public Map<String, Object> getProductComments(Map<String, Object> event) {
        try {
            Subsegment configSubsegment = AWSXRay.beginSubsegment("collectConfigParams");
            String REGION = (String) configManager.get("DYNAMO_REGION");
            String COMMENT_TABLE = (String) configManager.get("COMMENT_TABLE");
            AWSXRay.endSubsegment();

            DynamoDbClient dynamoDB = DynamoDbClient.builder()
                    .region(Region.of(REGION))
                    .build();

            Subsegment extractParamsSubsegment = AWSXRay.beginSubsegment("extractingParameters");
            Map<String, Object> queryStringParameters = new HashMap<>();
            if (event.containsKey("queryStringParameters") && event.get("queryStringParameters") != null) {
                queryStringParameters = (Map<String, Object>) event.get("queryStringParameters");
            }
            int page = Integer.parseInt(queryStringParameters.getOrDefault("page", "1").toString());
            int pageSize = Integer.parseInt(queryStringParameters.getOrDefault("pageSize", "4").toString());
            Map<String, String> pathParameters = (Map<String, String>) event.get("pathParameters");
            String productId = pathParameters.get("productId");
            extractParamsSubsegment.putMetadata("page", String.valueOf(page));
            extractParamsSubsegment.putMetadata("pageSize", String.valueOf(pageSize));
            AWSXRay.endSubsegment();
            Subsegment querySubsegment = AWSXRay.beginSubsegment("queryingComments");
            QueryRequest queryRequest = QueryRequest.builder()
                    .tableName(COMMENT_TABLE)
                    .keyConditionExpression("productId = :pid")
                    .expressionAttributeValues(Map.of(":pid", AttributeValue.builder().s(productId).build()))
                    .build();
            QueryResponse queryResponse = dynamoDB.query(queryRequest);
            AWSXRay.endSubsegment();
            Subsegment processingSubsegment = AWSXRay.beginSubsegment("processingResults");
            int totalPages = (int) Math.ceil((double) queryResponse.items().size() / pageSize);
            int start = (page - 1) * pageSize;
            int end = Math.min(start + pageSize, queryResponse.items().size());
            List<Map<String, AttributeValue>> pagedItems = queryResponse.items().subList(start, end);
            List<Map<String, String>> itemsString = ResponseTransformer.transformItems(pagedItems);
            int totalComments = queryResponse.items().size();
            Map<String, Integer> ratingCounts = new HashMap<>();
            for (Map<String, AttributeValue> item : queryResponse.items()) {
                String rating = item.get("Rating").n();
                ratingCounts.put(rating, ratingCounts.getOrDefault(rating, 0) + 1);
            }
            Map<String, Object> responseBody = new HashMap<>();
            responseBody.put("comments", itemsString);
            responseBody.put("totalPages", totalPages);
            responseBody.put("totalComments", totalComments);
            responseBody.put("ratingCounts", ratingCounts);
            AWSXRay.endSubsegment();

            Logger.getLogger(GetProductComments.class.getName()).info("Successfully obtained product comments");
            return ResponseGenerator.generateResponse(200, gson.toJson(responseBody));

        } catch (Exception e) {
            Logger.getLogger(GetProductComments.class.getName()).log(Level.SEVERE, "Failed to obtain product comments", e);
            throw new RuntimeException("Failed to obtain product comments", e);

        }
    }

}
