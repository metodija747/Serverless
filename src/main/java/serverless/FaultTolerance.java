package serverless;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import serverless.lib.MetricsHandler;
import serverless.lib.ResponseGenerator;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;


import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

public class FaultTolerance implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    LambdaClient lambdaClient = LambdaClient.builder().build();
    DynamoDbClient dynamoDB = DynamoDbClient.builder().build();
    private static final String DYNAMODB_TABLE = "ErrorTracker";
    private static final Map<Pattern, FunctionInfo> functionMap = new LinkedHashMap<>();
    static {
        functionMap.put(Pattern.compile("GET:/dispatcher/catalog$"),
                new FunctionInfo("arn:aws:lambda:us-east-1:824949725598:function:advancedMetodija747-GetAndSearchProductsFunctioni-Fia94NeRCooH", 3, 4, 30, "Catalog search is currently unavailable."));
        functionMap.put(Pattern.compile("GET:/dispatcher/catalog/.+"),
                new FunctionInfo("arn:aws:lambda:us-east-1:824949725598:function:advancedMetodija747-GetProductFunction-cyZJ6jck5ci9", 3, 4, 30, "Product details cannot be retrieved at this time."));
        functionMap.put(Pattern.compile("POST:/dispatcher/catalog$"),
                new FunctionInfo("arn:aws:lambda:us-east-1:824949725598:function:advancedMetodija747-AddNewProductFunction-HdY2w0iQiOKY", 3, 4, 30, "Adding or changing product is unavailable."));

        // ... add other mappings
    }

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
        MetricsHandler metricsHandler = new MetricsHandler();
        String httpMethod = (String) event.get("httpMethod");
        String path = (String) event.get("path");
        String key = httpMethod + ":" + path;
        FunctionInfo functionInfo = null;
        for (Map.Entry<Pattern, FunctionInfo> entry : functionMap.entrySet()) {
            if (entry.getKey().matcher(key).matches()) {
                functionInfo = entry.getValue();
                metricsHandler.setLambdaFunctionName(getFunctionNameFromARN(functionInfo.getFunctionArn()));
                break;
            }
        }

        if (functionInfo == null) {
            return ResponseGenerator.generateResponse(400, "Invalid path or method");
        }

        String functionName = functionInfo.getFunctionArn();
        int CircuitOpenThreshold = functionInfo.getCircuitOpenThreshold();
        long CircuitResetTimeout = functionInfo.getCircuitResetTimeout();
        int retries = functionInfo.getRetries();

        if (isCircuitOpen(functionName, CircuitOpenThreshold)) {
            metricsHandler.incrementCallsPrevented();
            metricsHandler.incrementFallbackCalls();
            return fallbackResponse(functionInfo.getFallbackMessage());
        }

        for (int attempt = 1; attempt <= retries; attempt++) {
            if (isCircuitOpen(functionName, CircuitOpenThreshold)) {
                metricsHandler.incrementCallsPrevented();
                metricsHandler.incrementFallbackCalls();
                return fallbackResponse(functionInfo.getFallbackMessage());
            }

            try {
                InvokeRequest invokeRequest = InvokeRequest.builder()
                        .functionName(functionName)
                        .payload(SdkBytes.fromUtf8String(new Gson().toJson(event)))
                        .build();

                InvokeResponse invokeResponse = lambdaClient.invoke(invokeRequest);
                String responseJson = invokeResponse.payload().asUtf8String();

                // Check if the Lambda function has thrown an error
                if (invokeResponse.functionError() != null) {
                    System.err.println("" + invokeResponse + " " + invokeResponse.functionError());

                    metricsHandler.incrementCallsFailed();
                    JsonParser parser = new JsonParser();
                    JsonObject errorObject = parser.parse(responseJson).getAsJsonObject();
                    String errorMessage = errorObject.get("errorMessage").getAsString();
                    String simpleErrorMessage = "Error Message: " + errorMessage;
                    logError(functionName, CircuitResetTimeout, new Exception(simpleErrorMessage));
                    if (errorMessage.contains("Task timed out")) {
                        metricsHandler.incrementCallsTimedOut();
                    }else {
                        metricsHandler.incrementCallsNotTimedOut();
                    }

                    if (attempt < retries) {
                        metricsHandler.incrementFailedCalls();
                        Thread.sleep(1000);
                    }
                    continue;
                }
                metricsHandler.incrementCallsSucceeded();
                metricsHandler.incrementCallsNotTimedOut();
                if (attempt == 1) {
                    metricsHandler.incrementSuccessfulCallsWithoutRetries();
                } else {
                    metricsHandler.incrementSuccessfulCallsWithRetries();
                }
                // Parse the response JSON into a Map
                Map<String, Object> responseMap = new Gson().fromJson(responseJson, Map.class);

                return responseMap;
            } catch (Exception e) {
                logError(functionName, CircuitResetTimeout, e);
                if (attempt < retries) {
                    try {
                        Thread.sleep(1000);  // Introducing a delay of 1 second after catching an exception and before the next retry
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt(); // Restore the interrupted status
                    }
                } else {
                    metricsHandler.incrementFailedCalls();
                    metricsHandler.incrementFallbackCalls();
                    return fallbackResponse(functionInfo.getFallbackMessage());
                }
            }
        }
        metricsHandler.incrementFallbackCalls();
        return fallbackResponse(functionInfo.getFallbackMessage());  // retries exhausted or circuit is open, return fallback response
    }

    private String getFunctionNameFromARN(String arn) {
        String[] parts = arn.split(":");
        return parts[parts.length - 1];
    }

    private boolean isCircuitOpen(String serviceName, int CircuitOpenThreshold) {
        Map<String, AttributeValue> expressionAttributeValues = new HashMap<>();
        expressionAttributeValues.put(":serviceName", AttributeValue.builder().s(serviceName).build());
        expressionAttributeValues.put(":now", AttributeValue.builder().n(String.valueOf(System.currentTimeMillis() / 1000)).build());

        QueryRequest queryRequest = QueryRequest.builder()
                .tableName(DYNAMODB_TABLE)
                .keyConditionExpression("PK = :serviceName and SK >= :now")
                .expressionAttributeValues(expressionAttributeValues)
                .build();

        QueryResponse queryResponse = dynamoDB.query(queryRequest);
        System.out.println("" + queryResponse.count());

        return queryResponse.count() >= CircuitOpenThreshold;  // Assume circuit is open if 10 or more errors are found
    }

    private void logError(String serviceName, long CircuitResetTimeout, Exception e) {
        long now = System.currentTimeMillis();
        long resetTime = now / 1000 + CircuitResetTimeout;  // Reset the circuit after 30 seconds

        Map<String, AttributeValue> item = new HashMap<>();
        item.put("PK", AttributeValue.builder().s(serviceName).build());
        item.put("SK", AttributeValue.builder().n(String.valueOf(resetTime)).build());
        item.put("ErrorType", AttributeValue.builder().s(e.getMessage()).build());
        item.put("TTL", AttributeValue.builder().n(String.valueOf(resetTime)).build());

        PutItemRequest putItemRequest = PutItemRequest.builder()
                .tableName(DYNAMODB_TABLE)
                .item(item)
                .build();

        dynamoDB.putItem(putItemRequest);

        }

    private Map<String, Object> fallbackResponse(String fallbackMessage) {
        return ResponseGenerator.generateResponse(500, fallbackMessage);
    }

    public static class FunctionInfo {
        private final String functionArn;
        private final int retries;
        private final int circuitOpenThreshold;
        private final long circuitResetTimeout;
        private final String fallbackMessage;

        public FunctionInfo(String functionArn, int retries, int circuitOpenThreshold, long circuitResetTimeout, String fallbackMessage) {
            this.functionArn = functionArn;
            this.retries = retries;
            this.circuitOpenThreshold = circuitOpenThreshold;
            this.circuitResetTimeout = circuitResetTimeout;
            this.fallbackMessage = fallbackMessage;

        }

        public String getFallbackMessage() {
            return fallbackMessage;
        }

        public int getCircuitOpenThreshold() {
            return circuitOpenThreshold;
        }

        public long getCircuitResetTimeout() {
            return circuitResetTimeout;
        }

        public String getFunctionArn() {
            return functionArn;
        }

        public int getRetries() {
            return retries;
        }
    }
}
