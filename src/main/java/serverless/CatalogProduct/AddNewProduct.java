package serverless.CatalogProduct;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.xray.AWSXRay;
import com.amazonaws.xray.entities.Subsegment;
import com.auth0.jwk.JwkException;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.google.gson.Gson;
import serverless.lib.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.net.MalformedURLException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import serverless.lib.LambdaDocumentationAnnotations.*;

public class AddNewProduct implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private static final Gson gson = new Gson();
    private static final Logger logger = Logger.getLogger(AddNewProduct.class.getName());

    @LambdaOperation(
            summary = "Add a new product",
            description = "This endpoint allows admins to add a new product to the product catalog.",
            path = "/catalog",
            method = "post"
    )
    @LambdaRequestBody(
            description = "Product object that needs to be added to the catalog",
            content = @LambdaContent(
                    mediaType = "application/json",
                    schema = @LambdaSchema(
                            example = "{ \"averageRating\": 0, \"beautifulComment\": \"Excellent\", \"categoryName\": \"Timepiece\", \"commentsCount\": 0, \"description\": \"Perfect\", \"discountPrice\": 40, \"imageURL\": \"https://www.theclockstore.co.uk/wp-content/uploads/2022/11/3277BR-front-01-A-Aberdeen_1200x-1-Medium-300x300.jpeg\", \"price\": 70, \"productId\": \"\", \"productName\": \"Aberdeen Clock\" }"
                    )
            )
    )
    @LambdaAPIResponses({
            @LambdaAPIResponse(responseCode = 200, description = "Product successfully added."),
            @LambdaAPIResponse(responseCode = 401, description = "Unauthorized: Invalid token."),
            @LambdaAPIResponse(responseCode = 403, description = "Forbidden: only admins can add/update products."),
            @LambdaAPIResponse(responseCode = 500, description = "Internal Server Error.")
    })
    @LambdaSecurityScheme(
            name = "BearerAuth",
            type = "http",
            in = "header",
            bearerFormat = "JWT"
    )
    @LambdaSecurityRequirement(name = "BearerAuth")
    @Override
    public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
        try {
            return addNewProduct(event);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error adding product: " + e.getMessage(), e);
            // Instead of a fallback, we directly return an error response
            return ResponseGenerator.generateResponse(500, gson.toJson("Unable to add new product at the moment."));
        }
    }

    public Map<String, Object> addNewProduct(Map<String, Object> event) {
        Subsegment configSubsegment = AWSXRay.beginSubsegment("collectConfigParams");
        ConfigManager configManager = new ConfigManager();
        String REGION = (String) configManager.get("DYNAMO_REGION");
        String PRODUCT_TABLE = (String) configManager.get("PRODUCT_TABLEI");
        String ISSUER = (String)  configManager.get("ISSUER");
        AWSXRay.endSubsegment();

        DynamoDbClient dynamoDB = DynamoDbClient.builder()
                .region(Region.of(REGION))
                .build();

        Subsegment tokenVerificationSubsegment = AWSXRay.beginSubsegment("authenticatingUser");
        String token = ((Map<String, String>) event.get("headers")).get("Authorization");
        System.out.println("OVA E TOKENOT: " + event);
        try {
            TokenVerifier.verifyToken(token, ISSUER);
            List<String> groups = TokenVerifier.getGroups(token, ISSUER);
            if (groups == null || !groups.contains("Admins")) {
                return ResponseGenerator.generateResponse(403, gson.toJson("Unauthorized: only admin users can add new products."));
            }
        } catch (JWTVerificationException | JwkException | MalformedURLException e) {
            logger.log(Level.SEVERE, "Failed to authenticate user", e);
            return ResponseGenerator.generateResponse(401, gson.toJson("Invalid token."));
        } finally {
            AWSXRay.endSubsegment();
        }

        Subsegment bodyParamsforNewProduct = AWSXRay.beginSubsegment("extractbodyParamsforNewProduct");
        Map<String, String> body = gson.fromJson((String) event.get("body"), HashMap.class);
        String productName = (String) body.get("productName");
        String categoryName = (String) body.get("categoryName");
        String imageURL =  (String) body.get("imageURL");
        double price = Double.parseDouble((String) body.get("price"));
        String productId = body.containsKey("productId") ? (String) body.get("productId") : UUID.randomUUID().toString();
        String description = (String) body.get("description");
        String beautifulComment = (String) body.get("beautifulComment");
        int commentsCount = 0; // Initialize commentsCount as 0 for a new product
        double discountPrice = Double.parseDouble((String) body.get("discountPrice"));
        AWSXRay.endSubsegment();

        Subsegment addNewProductSubSegment = AWSXRay.beginSubsegment("addNewProduct");
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("productId", AttributeValue.builder().s(productId).build());
        item.put("AverageRating", AttributeValue.builder().n("0.0").build());
        item.put("categoryName", AttributeValue.builder().s(categoryName).build());
        item.put("imageURL", AttributeValue.builder().s(imageURL).build());
        item.put("Price", AttributeValue.builder().n(Double.toString(price)).build());
        item.put("productName", AttributeValue.builder().s(productName).build());
        item.put("Description", AttributeValue.builder().s(description).build());
        item.put("beautifulComment", AttributeValue.builder().s(beautifulComment).build());
        item.put("commentsCount", AttributeValue.builder().n(Integer.toString(commentsCount)).build());
        item.put("discountPrice", AttributeValue.builder().n(Double.toString(discountPrice)).build());
        item.forEach((key, value) -> addNewProductSubSegment.putMetadata(key, value.toString()));
        PutItemRequest putItemRequest = PutItemRequest.builder()
                .tableName(PRODUCT_TABLE)
                .item(item)
                .build();
        dynamoDB.putItem(putItemRequest);
        AWSXRay.endSubsegment();

        Logger.getLogger(AddNewProduct.class.getName()).info("Product added successfully.");
        return ResponseGenerator.generateResponse(200, gson.toJson("Product added successfully"));
    }
}
