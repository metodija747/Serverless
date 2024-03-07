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

//    LambdaClient lambdaClient = LambdaClient.builder().build();
//    DynamoDbClient dynamoDB = DynamoDbClient.builder().build();
    private static LambdaClient lambdaClient;
    private static DynamoDbClient dynamoDB;
    private static final String DYNAMODB_TABLE = "ErrorTracker";
    private static final Gson gson = new Gson();
    private static final Map<Pattern, FunctionInfo> functionMap = new LinkedHashMap<>();
    static {
        functionMap.put(Pattern.compile("GET:/dispatcher/catalog$"),
                new FunctionInfo("arn:aws:lambda:us-east-1:183636859032:function:advancedMetodija747-GetAndSearchProductsFunctioni-Wlsr4NgrtglS",
                        3,
                        4,
                        30,
                        "Catalog search is currently unavailable."));
        functionMap.put(Pattern.compile("GET:/dispatcher/catalog/.+"),
                new FunctionInfo("arn:aws:lambda:us-east-1:183636859032:function:advancedMetodija747-GetProductFunction-54kF1YY5ACYU",
                        3,
                        4,
                        30,
                        "Product details cannot be retrieved at this time."));
        functionMap.put(Pattern.compile("POST:/dispatcher/catalog$"),
                new FunctionInfo("arn:aws:lambda:us-east-1:183636859032:function:advancedMetodija747-AddNewProductFunction-QZm7xYjjHRT1",
                        3,
                        4,
                        30,
                        "Adding or changing product is unavailable."));
        functionMap.put(Pattern.compile("POST:/dispatcher/authorization/login$"),
                new FunctionInfo("arn:aws:lambda:us-east-1:183636859032:function:advancedMetodija747-SignInFunction-F5kN2KStTeN5",
                        3,
                        4,
                        30,
                        "Unable to login at the moment. Please try again later."));
        functionMap.put(Pattern.compile("POST:/dispatcher/authorization/register$"),
                new FunctionInfo("arn:aws:lambda:us-east-1:183636859032:function:advancedMetodija747-RegisterUserFunction-7CoYuNbVJ6Yg",
                        3,
                        4,
                        30,
                        "Unable to register user at the moment. Please try again later."));

        // ... other mappings
    }

    // Static initialization block remains unchanged

    private static LambdaClient getLambdaClient() {
        if (lambdaClient == null) {
            lambdaClient = LambdaClient.builder().build();
        }
        return lambdaClient;
    }

    private static DynamoDbClient getDynamoDbClient() {
        if (dynamoDB == null) {
            dynamoDB = DynamoDbClient.builder().build();
        }
        return dynamoDB;
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

                LambdaClient lambdaClientInstance = getLambdaClient();
                InvokeResponse invokeResponse = lambdaClientInstance.invoke(invokeRequest);
                String responseJson = invokeResponse.payload().asUtf8String();

                // Check if the Lambda function has thrown an error
                if (invokeResponse.functionError() != null) {
                    System.err.println("" + invokeResponse + " " + invokeResponse.functionError());

                    metricsHandler.incrementCallsFailed();

                    Map<String, Object> errorResponse = new Gson().fromJson(responseJson, Map.class);
                    Number statusCode = (Number) errorResponse.get("statusCode");
                    if (statusCode != null && (statusCode.intValue() == 403 || statusCode.intValue() == 401)) {
                        return ResponseGenerator.generateResponse(statusCode.intValue(), gson.toJson(errorResponse.get("message")));
                    }

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
                Map<String, Object> responseMap = new Gson().fromJson(responseJson, Map.class);

                return responseMap;
            } catch (Exception e) {
                logError(functionName, CircuitResetTimeout, e);
                if (attempt < retries) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
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

        DynamoDbClient dynamoDbClientInstance = getDynamoDbClient();
        QueryResponse queryResponse = dynamoDbClientInstance.query(queryRequest);
        System.out.println("" + queryResponse.count());

        return queryResponse.count() >= CircuitOpenThreshold;
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

        DynamoDbClient dynamoDbClientInstance = getDynamoDbClient();
        dynamoDbClientInstance.putItem(putItemRequest);

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
