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

public class ConcreteProduct implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private static final Gson gson = new Gson();
    private static ConfigManager configManager;
    private static DynamoDbClient dynamoDB;
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
            Map<String, AttributeValue> key = new HashMap<>();
            key.put("productId", AttributeValue.builder().s(pathParameters.get("productId")).build());
            GetItemRequest request = GetItemRequest.builder()
                    .key(key)
                    .tableName(PRODUCT_TABLE)
                    .build();
            GetItemResponse getItemResponse = dynamoDB.getItem(request);
            Map<String, String> itemString = ResponseTransformer.transformItem(getItemResponse.item());

            Logger.getLogger(GetProduct.class.getName()).info("Successfully obtained product details");
            return ResponseGenerator.generateResponse(200, gson.toJson(itemString));

        } catch (Exception e) {
            Logger.getLogger(GetProduct.class.getName()).log(Level.SEVERE, "Failed to obtain product details", e);
            throw new RuntimeException("Failed to obtain product details", e);
        }
    }
}
