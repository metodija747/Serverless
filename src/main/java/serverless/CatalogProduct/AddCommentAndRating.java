package serverless.CatalogProduct;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.xray.AWSXRay;
import com.amazonaws.xray.entities.Subsegment;
import com.auth0.jwt.JWT;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.google.gson.Gson;
import serverless.lib.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import serverless.lib.LambdaDocumentationAnnotations.*;

public class AddCommentAndRating implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private static final Gson gson = new Gson();
    private static final Logger logger = Logger.getLogger(AddCommentAndRating.class.getName());
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
            summary = "Add Comment and Rating",
            description = "Allows users to add a comment and rating for a specific product.",
            path = "/comments/{productId}",
            method = "POST"
    )
    @LambdaRequestBody(
            description = "Details for adding a comment and rating to a specific product",
            content = @LambdaContent(
                    mediaType = "application/json",
                    schema = @LambdaSchema(
                            example = "{ \"productId\": \"a9abe32e-9bd6-43aa-bc00-9044a27b858b\", \"comment\": \"Love this product! Highly recommend.\", \"rating\": 5 }"
                    )
            )
    )
    @LambdaParameters({
            @LambdaParameter(name = "productId", description = "productID of the product for which comment is inserted.", in = LambdaDocumentationAnnotations.ParameterIn.PATH, example = "a9abe32e-9bd6-43aa-bc00-9044a27b858b")
    })
    @LambdaAPIResponses({
            @LambdaAPIResponse(responseCode = 200, description = "Comment and rating added successfully."),
            @LambdaAPIResponse(responseCode = 401, description = "Invalid token."),
            @LambdaAPIResponse(responseCode = 500, description = "Failed to add comment and rating.")
    })
    @LambdaSecurityRequirement(name = "BearerAuth")
    @Override
    public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
        try {
            Subsegment configSubsegment = AWSXRay.beginSubsegment("CollectConfigParams");
            String COMMENT_TABLE = (String) configManager.get("COMMENT_TABLE");
            String PRODUCT_TABLE = (String) configManager.get("PRODUCT_TABLE");
            String ISSUER = (String) configManager.get("ISSUER");
            AWSXRay.endSubsegment();

            Subsegment tokenVerificationSubsegment = AWSXRay.beginSubsegment("AuthenticatingUser");
            String authHeader = ((Map<String, String>) event.get("headers")).get("Authorization");
            String token = "";
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                token = authHeader.substring("Bearer ".length());
            }
            String userId = TokenVerifier.verifyToken(token, ISSUER);
            AWSXRay.endSubsegment();

            Subsegment extractParametersSubsegment = AWSXRay.beginSubsegment("ExtractParameters");
            Map<String, String> body = gson.fromJson((String) event.get("body"), HashMap.class);
            String productId = body.get("productId");
            String comment = body.get("comment");
            int rating = Integer.parseInt(body.get("rating"));
            AWSXRay.endSubsegment();

            Subsegment putNewCommentSubsegment = AWSXRay.beginSubsegment("PutNewComment");
            Map<String, AttributeValue> item = new HashMap<>();
            item.put("UserId", AttributeValue.builder().s(userId).build());
            item.put("productId", AttributeValue.builder().s(productId).build());
            item.put("Comment", AttributeValue.builder().s(comment).build());
            item.put("Rating", AttributeValue.builder().n(String.valueOf(rating)).build());

            QueryRequest checkExistingCommentRequest = QueryRequest.builder()
                    .tableName(COMMENT_TABLE)
                    .keyConditionExpression("productId = :v_product AND UserId = :v_user")
                    .expressionAttributeValues(Map.of(
                            ":v_product", AttributeValue.builder().s(productId).build(),
                            ":v_user", AttributeValue.builder().s(userId).build()))
                    .build();
            QueryResponse checkExistingCommentResponse = dynamoDB.query(checkExistingCommentRequest);
            if (checkExistingCommentResponse.items().isEmpty()) {
                dynamoDB.putItem(PutItemRequest.builder().tableName(COMMENT_TABLE).item(item).build());
            }
            AWSXRay.endSubsegment();

            updateProductCatalog(productId, rating, PRODUCT_TABLE, COMMENT_TABLE);

            Map<String, Object> response = new HashMap<>();
            response.put("message", "Comment and rating added successfully");
            logger.info("Comment and rating added successfully");
            return ResponseGenerator.generateResponse(200, gson.toJson(response));
        } catch (JWTVerificationException e) {
            logger.log(Level.SEVERE, "Authentication failed: " + e.getMessage(), e);
            return ResponseGenerator.generateResponse(401, "Invalid token.");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error adding comment and rating: " + e.getMessage(), e);
            throw new RuntimeException("Failed to add comment and rating.", e);
        }
    }

    private void updateProductCatalog(String productId, int newRating, String PRODUCT_TABLE, String COMMENT_TABLE) {
        Subsegment updateCatalogSubsegment = AWSXRay.beginSubsegment("UpdateProductCatalog");
        try {
            QueryRequest queryRequest = QueryRequest.builder()
                    .tableName(COMMENT_TABLE)
                    .keyConditionExpression("productId = :v_id")
                    .expressionAttributeValues(Collections.singletonMap(":v_id", AttributeValue.builder().s(productId).build()))
                    .projectionExpression("Rating")
                    .build();
            QueryResponse queryResponse = dynamoDB.query(queryRequest);
            List<Map<String, AttributeValue>> comments = queryResponse.items();
            int totalRating = comments.stream().mapToInt(comment -> Integer.parseInt(comment.get("Rating").n())).sum();
            double avgRating = comments.size() > 0 ? Math.round((double) totalRating / comments.size()) : 0;

            Map<String, AttributeValue> key = new HashMap<>();
            key.put("productId", AttributeValue.builder().s(productId).build());
            Map<String, AttributeValueUpdate> attributeUpdates = new HashMap<>();
            attributeUpdates.put("AverageRating", AttributeValueUpdate.builder()
                    .value(AttributeValue.builder().n(String.valueOf(avgRating)).build())
                    .action(AttributeAction.PUT)
                    .build());

            UpdateItemRequest updateItemRequest = UpdateItemRequest.builder()
                    .tableName(PRODUCT_TABLE)
                    .key(key)
                    .attributeUpdates(attributeUpdates)
                    .build();
            dynamoDB.updateItem(updateItemRequest);
        } finally {
            AWSXRay.endSubsegment();
        }
    }
}
