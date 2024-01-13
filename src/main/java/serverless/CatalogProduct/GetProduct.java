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
import serverless.lib.LambdaDocumentationAnnotations.*;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public class GetProduct implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private static final Gson gson = new Gson();
    private static ConfigManager configManager;
    private static DynamoDbClient dynamoDB;

    @LambdaOperation(
            summary = "Get Product Details",
            description = "This endpoint allows users to get details of a specific product in the catalog by its productId.",
            path = "/catalog/{productId}",
            method = "GET"
    )
    @LambdaParameters({
            @LambdaParameter(name = "productId", description = "Unique identifier for the product", in = LambdaDocumentationAnnotations.ParameterIn.PATH, example = "a9abe32e-9bd6-43aa-bc00-9044a27b858b")
    })
    @LambdaAPIResponses({
            @LambdaAPIResponse(responseCode = 200, description = "Successfully obtained product details."),
            @LambdaAPIResponse(responseCode = 404, description = "Product not found."),
            @LambdaAPIResponse(responseCode = 500, description = "Unable to get product details. Please try again")
    })
    @Override
    public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
        initializeResources();
        return getProduct(event);
    }

    private synchronized void initializeResources() {
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

    public Map<String, Object> getProduct(Map<String, Object> event) {
        try {
            Subsegment configSubsegment = AWSXRay.beginSubsegment("collectConfigParams");
            String PRODUCT_TABLE = (String) configManager.get("PRODUCT_TABLE");
            AWSXRay.endSubsegment();

            Map<String, String> pathParameters = (Map<String, String>) event.get("pathParameters");
            String proxyValue = pathParameters.get("proxy");
            String[] parts = proxyValue.split("/");
            String productId = parts[parts.length - 1];
            Map<String, AttributeValue> key = new HashMap<>();
            key.put("productId", AttributeValue.builder().s(productId).build());
            GetItemRequest request = GetItemRequest.builder()
                    .key(key)
                    .tableName(PRODUCT_TABLE)
                    .build();
            GetItemResponse getItemResponse = dynamoDB.getItem(request);
            Map<String, String> itemString = ResponseTransformer.transformItem(getItemResponse.item());

            Logger.getLogger(GetProduct.class.getName()).info("Successfully obtained product details");
            return ResponseGenerator.generateResponse(200, gson.toJson(itemString));

        } catch (DynamoDbException e) {
            Logger.getLogger(GetProduct.class.getName()).log(Level.SEVERE, "Failed to obtain product details", e);
            throw new RuntimeException("Failed to obtain product details", e);
        }
    }
}
