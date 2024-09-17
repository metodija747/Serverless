package serverless.CatalogProduct;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.xray.AWSXRay;
import com.amazonaws.xray.entities.Subsegment;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.google.gson.Gson;
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

public class DeleteCommentAndRating implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private static final Gson gson = new Gson();
    private static final Logger logger = Logger.getLogger(DeleteCommentAndRating.class.getName());
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
            summary = "Delete Comment and Rating",
            description = "Allows users to delete a comment and rating for a specific product.",
            path = "/comments/{productId}",
            method = "DELETE"
    )
    @LambdaParameters({
            @LambdaParameter(name = "productId", description = "productID of the product for which comment is deleted.", in = LambdaDocumentationAnnotations.ParameterIn.PATH, example = "a9abe32e-9bd6-43aa-bc00-9044a27b858b")
    })
    @LambdaAPIResponses({
            @LambdaAPIResponse(responseCode = 200, description = "Comment and rating deleted successfully."),
            @LambdaAPIResponse(responseCode = 401, description = "Invalid token."),
            @LambdaAPIResponse(responseCode = 404, description = "Comment cannot be deleted because it is not present in the database."),
            @LambdaAPIResponse(responseCode = 500, description = "Failed to delete comment and rating.")
    })
    @LambdaSecurityRequirement(name = "BearerAuth")
    @Override
    public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
        try {
            Subsegment configSubsegment = AWSXRay.beginSubsegment("collectConfigParams");
            String COMMENT_TABLE = (String) configManager.get("COMMENT_TABLE");
            String PRODUCT_TABLE = (String) configManager.get("PRODUCT_TABLE");
            String ISSUER = (String) configManager.get("ISSUER");
            AWSXRay.endSubsegment();

            DynamoDbClient dynamoDB = DynamoDbClient.builder()
                    .region(Region.of((String) configManager.get("DYNAMO_REGION")))
                    .build();

            Subsegment tokenVerificationSubsegment = AWSXRay.beginSubsegment("authenticatingUser");
            String authHeader = ((Map<String, String>) event.get("headers")).get("Authorization");
            String token = "";
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                token = authHeader.substring("Bearer ".length());
            }
            String userId;
            try {
                userId = TokenVerifier.verifyToken(token, ISSUER);
            } catch (JWTVerificationException | MalformedURLException e) {
                logger.log(Level.SEVERE, "Token verification failed", e);
                return ResponseGenerator.generateResponse(401, gson.toJson("Invalid token."));
            } finally {
                AWSXRay.endSubsegment();
            }

            // Extract productId from path parameters
            Map<String, String> pathParameters = (Map<String, String>) event.get("pathParameters");
            String proxyValue = pathParameters.get("proxy");
            String[] parts = proxyValue.split("/");
            String productId = parts[parts.length - 1];

            // Retrieve the rating of the comment before deletion
            Subsegment retrieveRatingSubsegment = AWSXRay.beginSubsegment("retrieveRating");
            GetItemRequest getItemRequest = GetItemRequest.builder()
                    .tableName(COMMENT_TABLE)
                    .key(Map.of("UserId", AttributeValue.builder().s(userId).build(),
                            "productId", AttributeValue.builder().s(productId).build()))
                    .projectionExpression("Rating")
                    .build();
            GetItemResponse getItemResponse = dynamoDB.getItem(getItemRequest);

            if (getItemResponse.item() == null || getItemResponse.item().isEmpty()) {
                logger.info("Comment with given userId and productId does not exist in the database.");
                return ResponseGenerator.generateResponse(404, gson.toJson("Comment cannot be deleted because it is not present in the database."));
            }
            int deletedRating = Integer.parseInt(getItemResponse.item().get("Rating").n());
            retrieveRatingSubsegment.putMetadata("deletedRating", deletedRating);
            AWSXRay.endSubsegment();

            // Delete the comment
            Subsegment deleteCommentSubsegment = AWSXRay.beginSubsegment("deleteComment");
            Map<String, AttributeValue> key = new HashMap<>();
            key.put("UserId", AttributeValue.builder().s(userId).build());
            key.put("productId", AttributeValue.builder().s(productId).build());
            DeleteItemRequest deleteItemRequest = DeleteItemRequest.builder()
                    .tableName(COMMENT_TABLE)
                    .key(key)
                    .build();
            dynamoDB.deleteItem(deleteItemRequest);
            AWSXRay.endSubsegment();

            // Calculate the new average rating
            Subsegment calculateRatingSubsegment = AWSXRay.beginSubsegment("calculateNewRating");
            QueryRequest queryRequest = QueryRequest.builder()
                    .tableName(COMMENT_TABLE)
                    .keyConditionExpression("productId = :v_id")
                    .expressionAttributeValues(Collections.singletonMap(":v_id", AttributeValue.builder().s(productId).build()))
                    .projectionExpression("Rating")
                    .build();
            QueryResponse queryResponse = dynamoDB.query(queryRequest);
            List<Map<String, AttributeValue>> comments = queryResponse.items();
            int totalRating = 0;
            for (Map<String, AttributeValue> commentItem : comments) {
                totalRating += Integer.parseInt(commentItem.get("Rating").n());
            }
            double avgRating = comments.size() > 0 ? (double) totalRating / comments.size() : 0;
            calculateRatingSubsegment.putMetadata("averageRating", avgRating);
            AWSXRay.endSubsegment();

            // Update the product catalog with the new average rating and decrement the commentsCount
            Subsegment updateProductSubsegment = AWSXRay.beginSubsegment("updateProductDetails");
            Map<String, AttributeValueUpdate> attributeUpdates = new HashMap<>();
            attributeUpdates.put("AverageRating", AttributeValueUpdate.builder()
                    .value(AttributeValue.builder().n(String.valueOf(avgRating)).build())
                    .action(AttributeAction.PUT)
                    .build());
            attributeUpdates.put("commentsCount", AttributeValueUpdate.builder()
                    .value(AttributeValue.builder().n("-1").build())
                    .action(AttributeAction.ADD)
                    .build());
            UpdateItemRequest updateItemRequest = UpdateItemRequest.builder()
                    .tableName(PRODUCT_TABLE)
                    .key(Collections.singletonMap("productId", AttributeValue.builder().s(productId).build()))
                    .attributeUpdates(attributeUpdates)
                    .build();
            dynamoDB.updateItem(updateItemRequest);
            AWSXRay.endSubsegment();

            // Create a response object that includes the success message and the new average rating
            Map<String, Object> response = new HashMap<>();
            response.put("message", "Comment and rating deleted successfully");
            response.put("averageRating", avgRating);

            logger.info("Comment and rating for productId: " + productId + " deleted successfully with new average rating: " + avgRating);
            return ResponseGenerator.generateResponse(200, gson.toJson(response));
        } catch (Exception e) {
            Subsegment failureSubsegment = AWSXRay.beginSubsegment("DeleteCommentAndRatingFailed");
            logger.log(Level.SEVERE, "Error deleting comment and rating: " + e.getMessage(), e);
            AWSXRay.endSubsegment();
            throw new RuntimeException("Failed to delete comment and rating", e);
        }
    }
}
