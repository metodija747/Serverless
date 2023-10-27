package serverless.CatalogProduct;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.xray.AWSXRay;
import com.amazonaws.xray.entities.Subsegment;
import com.google.gson.Gson;
import serverless.lib.ConfigManager;
import serverless.lib.ResponseGenerator;
import serverless.lib.ResponseTransformer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

// ova test    raboti
public class GetAndSearchProducts implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private static final Gson gson = new Gson();

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
        return getAndSearchProducts(event);
    }


    public Map<String, Object> getAndSearchProducts(Map<String, Object> event) {
        try {
            ConfigManager configManager = new ConfigManager();
            String REGION =configManager.get("DYNAMO_REGION");
            String PRODUCT_TABLE = configManager.get("PRODUCT_TABLEI");
            DynamoDbClient dynamoDB = DynamoDbClient.builder()
                    .region(Region.of(REGION))
                    .build();

            Map<String, Object> queryStringParameters = new HashMap<>();
            if (event.containsKey("queryStringParameters") && event.get("queryStringParameters") != null) {
                queryStringParameters = (Map<String, Object>) event.get("queryStringParameters");
            }
            Logger.getLogger(GetAndSearchProducts.class.getName()).info("" + REGION + " " + PRODUCT_TABLE);

            // Ext ract the search term, sorting option, and category from the query string parameters
            // If no value is provided, default to an empty string or null
            String searchTerm = queryStringParameters.getOrDefault("searchTerm", "").toString().toLowerCase();
            String sortBy = queryStringParameters.getOrDefault("sortBy", "").toString();
            String sortOrder = queryStringParameters.getOrDefault("sortOrder", "").toString();
            String category = queryStringParameters.getOrDefault("category", "").toString();
            int page = Integer.parseInt(queryStringParameters.getOrDefault("page", "1").toString());
            int pageSize = Integer.parseInt(queryStringParameters.getOrDefault("pageSize", "4").toString());
            AWSXRay.endSubsegment();

            Subsegment filteringSortingSubsegment = AWSXRay.beginSubsegment("performFilteringSorting");
            Map<String, Object> queryParams = new HashMap<>();
            queryParams.put("searchTerm", searchTerm);
            queryParams.put("sortBy", sortBy);
            queryParams.put("sortOrder", sortOrder);
            queryParams.put("category", category);
            queryParams.put("page", page);
            queryParams.put("pageSize", pageSize);
            queryParams.forEach((key, value) -> filteringSortingSubsegment.putMetadata(key, value));
            ScanRequest.Builder scanRequestBuilder = ScanRequest.builder().tableName(PRODUCT_TABLE);
            // Initi  alize the filter expression and attribute values
            String filterExpression = "";
            Map<String, AttributeValue> expressionAttributeValues = new HashMap<>();
            // Capitalize first letter of each word in searchTerm
            if (searchTerm != null && !searchTerm.isEmpty()) {
                String[] words = searchTerm.split(" ");
                StringBuilder filterExpressionBuilder = new StringBuilder();
                int index = 0;
                for (String word : words) {
                    if (word != null && !word.isEmpty()) {
                        String capitalizedWord = word.substring(0, 1).toUpperCase() + word.substring(1).toLowerCase();
                        if (index > 0) {
                            filterExpressionBuilder.append(" AND ");
                        }
                        filterExpressionBuilder.append("contains(productName, :val").append(index).append(")");
                        expressionAttributeValues.put(":val" + index, AttributeValue.builder().s(capitalizedWord).build());
                        index++;
                    }
                }
                filterExpression += filterExpressionBuilder.toString();
            }

            // Apply the category filter if a category is provided
            if (!category.isEmpty()) {
                // Add an AND operator if there's already a condition in the filter expression
                if (!filterExpression.isEmpty()) {
                    filterExpression += " AND ";
                }
                filterExpression += "categoryName = :cat";
                expressionAttributeValues.put(":cat", AttributeValue.builder().s(category).build());
            }
            // Set the filter expression and attribute values in the scan request
            if (!filterExpression.isEmpty()) {
                scanRequestBuilder.filterExpression(filterExpression)
                        .expressionAttributeValues(expressionAttributeValues);
            }
            // Perform the scan
            ScanRequest scanRequest = scanRequestBuilder.build();
            ScanResponse scanResponse = dynamoDB.scan(scanRequest);
            List<Map<String, AttributeValue>> items = scanResponse.items();
            List<Map<String, AttributeValue>> sortedItems = new ArrayList<>(items);
            // Sort the items if a sorting option is provided
            if (!sortBy.isEmpty()) {
                switch (sortBy) {
                    case "AverageRating":
                        sortedItems.sort(Comparator.comparing(item -> Double.parseDouble(item.get("AverageRating").n())));
                        if (sortOrder.equalsIgnoreCase("DSC")) Collections.reverse(sortedItems);
                        items = sortedItems;
                        break;
                    case "Price":
                        sortedItems.sort(Comparator.comparing(item -> Double.parseDouble(item.get("Price").n())));
                        if (sortOrder.equalsIgnoreCase("DSC")) Collections.reverse(sortedItems);
                        items = sortedItems;
                        break;
                    default:
                        break;
                }
            }

            AWSXRay.endSubsegment();

            Subsegment pagingAndSegmentingSubsegment = AWSXRay.beginSubsegment("processingResults");
            // Calculate the total number of pages
            int totalPages = (int) Math.ceil((double) items.size() / pageSize);
            // Get the items for the current page
            int start = (page - 1) * pageSize;
            int end = Math.min(start + pageSize, items.size());
            List<Map<String, AttributeValue>> pagedItems = items.subList(start, end);
            List<Map<String, String>> itemsString = ResponseTransformer.transformItems(pagedItems);
            AWSXRay.endSubsegment();

            Map<String, Object> responseBody = new HashMap<>();
            responseBody.put("products", itemsString);
            responseBody.put("totalPages", totalPages);
            responseBody.put("totalProducts", items.size());
            responseBody.put("currentRangeStart", start + 1);
            responseBody.put("currentRangeEnd", end);

            Logger.getLogger(GetAndSearchProducts.class.getName()).info("Successfully obtained product list");
            return ResponseGenerator.generateResponse(200, gson.toJson(responseBody));

        } catch (Exception e) {
            Logger.getLogger(GetAndSearchProducts.class.getName()).log(Level.SEVERE, "Failed to obtain product list", e);
            throw new RuntimeException("Failed to obtain product list", e);
        }
    }
}




