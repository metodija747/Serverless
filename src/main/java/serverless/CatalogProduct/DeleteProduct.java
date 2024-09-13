package serverless.CatalogProduct;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.xray.AWSXRay;
import com.amazonaws.xray.entities.Subsegment;
import com.google.gson.Gson;
import serverless.lib.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import serverless.lib.LambdaDocumentationAnnotations.*;

public class DeleteProduct implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private static final Gson gson = new Gson();
    private static final Logger logger = Logger.getLogger(DeleteProduct.class.getName());
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
            summary = "Delete Product",
            description = "Allows admin users to delete a specific product from the catalog.",
            path = "/product/{productId}",
            method = "DELETE"
    )
    @LambdaAPIResponses({
            @LambdaAPIResponse(responseCode = 200, description = "Product deleted successfully."),
            @LambdaAPIResponse(responseCode = 401, description = "Unauthorized: only admin users can delete products."),
            @LambdaAPIResponse(responseCode = 403, description = "Invalid token."),
            @LambdaAPIResponse(responseCode = 404, description = "Product cannot be deleted because it is not present in the database."),
            @LambdaAPIResponse(responseCode = 500, description = "Failed to delete product.")
    })
    @LambdaSecurityRequirement(name = "BearerAuth")
    @Override
    public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
        try {
            Subsegment configSubsegment = AWSXRay.beginSubsegment("collectConfigParams");
            String PRODUCT_TABLE = (String) configManager.get("PRODUCT_TABLE");
            String ISSUER = (String) configManager.get("ISSUER");
            AWSXRay.endSubsegment();

            DynamoDbClient dynamoDB = DynamoDbClient.builder()
                    .region(Region.of((String) configManager.get("DYNAMO_REGION")))
                    .build();

            Subsegment tokenVerificationSubsegment = AWSXRay.beginSubsegment("authenticatingUser");
            String token = ((Map<String, String>) event.get("headers")).get("Authorization");
            List<String> groups;
            try {
                groups = TokenVerifier.getGroups(token, ISSUER);
                if (groups == null || !groups.contains("Admins")) {
                    return ResponseGenerator.generateResponse(401, gson.toJson("Unauthorized: only admin users can delete products."));
                }
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Failed to authenticate user", e);
                return ResponseGenerator.generateResponse(403, gson.toJson("Invalid token."));
            } finally {
                AWSXRay.endSubsegment();
            }

            Subsegment deleteProductSubsegment = AWSXRay.beginSubsegment("deleteProduct");
            Map<String, String> pathParameters = (Map<String, String>) event.get("pathParameters");
            String productId = pathParameters.get("productId");
            deleteProductSubsegment.putMetadata("productId", productId);

            // Check if the product exists in the database
            GetItemRequest getItemRequest = GetItemRequest.builder()
                    .tableName(PRODUCT_TABLE)
                    .key(Map.of("productId", AttributeValue.builder().s(productId).build()))
                    .build();
            GetItemResponse getItemResponse = dynamoDB.getItem(getItemRequest);

            if (getItemResponse.item() == null || getItemResponse.item().isEmpty()) {
                logger.info("Product with given productId does not exist in the database.");
                return ResponseGenerator.generateResponse(404, gson.toJson("Product cannot be deleted because it is not present in the database."));
            }

            // Delete the product
            Map<String, AttributeValue> key = new HashMap<>();
            key.put("productId", AttributeValue.builder().s(productId).build());
            DeleteItemRequest deleteItemRequest = DeleteItemRequest.builder()
                    .tableName(PRODUCT_TABLE)
                    .key(key)
                    .build();
            dynamoDB.deleteItem(deleteItemRequest);
            AWSXRay.endSubsegment();

            logger.info("Product deleted successfully.");
            return ResponseGenerator.generateResponse(200, gson.toJson("Product deleted successfully."));
        } catch (Exception e) {
            Subsegment failureSubsegment = AWSXRay.beginSubsegment("DeleteProductFailed");
            logger.log(Level.SEVERE, "Error deleting product: " + e.getMessage(), e);
            AWSXRay.endSubsegment();
            throw new RuntimeException("Failed to delete product", e);
        }
    }
}
