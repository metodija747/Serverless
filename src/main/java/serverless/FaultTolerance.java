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
import java.util.Map;

public class FaultTolerance implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    LambdaClient lambdaClient = LambdaClient.builder().build();
    DynamoDbClient dynamoDB = DynamoDbClient.builder().build();
    private static final String DYNAMODB_TABLE = "ErrorTracker";
    private static final Map<String, FunctionInfo> functionMap = new HashMap<>();
    static {
        functionMap.put("GET:/dispatcher/catalog", new FunctionInfo("arn:aws:lambda:us-east-1:824949725598:function:advancedMetodija747-GetAndSearchProductsFunctioni-Fia94NeRCooH", 3));
        // ... add    other mappings
    }
    private static final Map<String, String> FUNCTION_NAME_MAPPING = new HashMap<>();
    static {
        FUNCTION_NAME_MAPPING.put("GET:/dispatcher/catalog", "GetAndSearchProductsFunctioni");
        // ... add other mappings
    }
    @Override
    public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
        MetricsHandler metricsHandler = new MetricsHandler();
        String httpMethod = (String) event.get("httpMethod");
        String path = (String) event.get("path");
        String key = httpMethod + ":" + path;
        String lambdaFunctionName = FUNCTION_NAME_MAPPING.get(key);
        metricsHandler.setLambdaFunctionName(lambdaFunctionName);
        FunctionInfo functionInfo = functionMap.get(key);
        if (functionInfo == null) {
            return ResponseGenerator.generateResponse(400, "Invalid path or method");
        }

        String functionName = functionInfo.getFunctionArn();
        int retries = functionInfo.getRetries();

        if (isCircuitOpen(functionName)) {
            metricsHandler.incrementCallsPrevented();
            metricsHandler.incrementFallbackCalls();
            return fallbackResponse(functionName);
        }

        for (int attempt = 1; attempt <= retries; attempt++) {
            if (isCircuitOpen(functionName)) {
                metricsHandler.incrementCallsPrevented();
                metricsHandler.incrementFallbackCalls();
                return fallbackResponse(functionName);
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
                    String errorType = errorObject.get("errorType").getAsString();
                    String simpleErrorMessage = "Error Message: " + errorMessage + ", Error Type: " + errorType;
                    logError(functionName, new Exception(simpleErrorMessage));
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
                logError(functionName, e);
                if (attempt < retries) {
                    try {
                        Thread.sleep(1000);  // Introducing a delay of 1 second after catching an exception and before the next retry
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt(); // Restore the interrupted status
                    }
                } else {
                    metricsHandler.incrementFailedCalls();
                    metricsHandler.incrementFallbackCalls();
                    return fallbackResponse(functionName);
                }
            }
        }
        metricsHandler.incrementFallbackCalls();
        return fallbackResponse(functionName);  // retries exhausted or circuit is open, return fallback response
    }

    private boolean isCircuitOpen(String serviceName) {
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

        return queryResponse.count() >= 4;  // Assume circuit is open if 10 or more errors are found
    }

    private void logError(String serviceName, Exception e) {
        long now = System.currentTimeMillis();
        long resetTime = now / 1000 + 30;  // Reset the circuit after 30 seconds

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

    private Map<String, Object> fallbackResponse(String functionName) {
        return ResponseGenerator.generateResponse(500, "Fallback response: Service:" + functionName +  " is temporarily unavailable");
    }

    public static class FunctionInfo {
        private final String functionArn;
        private final int retries;

        public FunctionInfo(String functionArn, int retries) {
            this.functionArn = functionArn;
            this.retries = retries;
        }

        public String getFunctionArn() {
            return functionArn;
        }

        public int getRetries() {
            return retries;
        }
    }
}
