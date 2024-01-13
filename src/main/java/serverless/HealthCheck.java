package serverless;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import serverless.lib.ConfigManager;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.*;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.ListTablesResponse;

import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class HealthCheck implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private static final Gson gson = new Gson();
    private static final ConfigManager configManager = new ConfigManager();

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
        Object healthConfig = configManager.get("HEALTH");
        if (healthConfig == null) {
            Map<String, Object> response = new HashMap<>();
            response.put("statusCode", 200);
            response.put("body", "HEALTH CHECKS WERE NOT SET UP");
            return response;
        }
        JsonObject configJson = fetchHealthConfig();

        Map<String, Object> combinedStatus = new HashMap<>();

        if (configJson.has("DATASOURCES")) {
            combinedStatus.putAll(checkDatabases());
        }
        if (configJson.has("HTTP_RESOURCE")) {
            combinedStatus.putAll(checkHttpResources());
        }
        if (configJson.has("METRICS")) {
            combinedStatus.putAll(checkMetrics());
        }

        Map<String, Object> response = new HashMap<>();
        response.put("statusCode", 200);
        response.put("body", gson.toJson(combinedStatus));
        return response;
    }

    private JsonObject fetchHealthConfig() {
        Object healthConfig = configManager.get("HEALTH");
        JsonObject configJson;
        if (healthConfig instanceof String) {
            configJson = gson.fromJson((String) healthConfig, JsonObject.class);
        } else if (healthConfig instanceof Map) {
            String jsonStr = gson.toJson(healthConfig);
            configJson = gson.fromJson(jsonStr, JsonObject.class);
        } else {
            throw new RuntimeException("Unexpected type for 'HEALTH' configuration.");
        }
        return configJson;
    }

    public Map<String, Object> checkDatabases() {
        String REGION = (String) configManager.get("DYNAMO_REGION");
        JsonObject configJson = fetchHealthConfig();

        var datasources = configJson.getAsJsonArray("DATASOURCES");

        DynamoDbClient dynamoDB = DynamoDbClient.builder()
                .region(Region.of(REGION))
                .build();

        ListTablesResponse listTablesResponse = dynamoDB.listTables();
        List<String> availableTables = listTablesResponse.tableNames();

        Map<String, Object> finalResponse = new HashMap<>();
        List<Map<String, String>> datasourcesStatus = new ArrayList<>();

        for (var datasourceElement : datasources) {
            Map<String, String> singleTableStatus = new HashMap<>();
            String tableName = datasourceElement.getAsString();
            singleTableStatus.put("TABLE", tableName);
            singleTableStatus.put("STATUS", availableTables.contains(tableName) ? "UP" : "DOWN");
            datasourcesStatus.add(singleTableStatus);
        }

        finalResponse.put("DATASOURCES", datasourcesStatus);
        return finalResponse;
    }

    public Map<String, Object> checkHttpResources() {
        JsonObject configJson = fetchHealthConfig();
        var httpResources = configJson.getAsJsonArray("HTTP_RESOURCE");

        List<Map<String, String>> httpResourcesStatus = new ArrayList<>();

        for (var resourceElement : httpResources) {
            Map<String, String> httpResponse = new HashMap<>();
            String resourceUrl = resourceElement.getAsString();
            String status = "DOWN";
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL(resourceUrl).openConnection();
                connection.setRequestMethod("HEAD");
                int responseCode = connection.getResponseCode();
                if (responseCode >= 200 && responseCode < 300) {
                    status = "UP";
                }
            } catch (Exception e) {
                System.out.println(e.getMessage());
            }
            httpResponse.put("link", resourceUrl);
            httpResponse.put("status", status);
            httpResourcesStatus.add(httpResponse);
        }

        Map<String, Object> finalResponse = new HashMap<>();
        finalResponse.put("HTTP_CHECK", httpResourcesStatus);
        return finalResponse;
    }


    public Map<String, Object> checkMetrics() {
        JsonObject configJson = fetchHealthConfig();
        String REGION = (String) configManager.get("DYNAMO_REGION");
        JsonObject metricsJson = configJson.getAsJsonObject("METRICS");
        String timeRange = metricsJson.get("TIME_RANGE").getAsString();
        long startTime = Instant.now().minusMillis(Long.parseLong(timeRange)).toEpochMilli();
        CloudWatchClient cw = CloudWatchClient.builder()
                .region(Region.of(REGION))
                .build();

        Map<String, Object> metricsResponse = new HashMap<>();
        for (JsonElement functionElement : metricsJson.getAsJsonArray("FUNCTIONS")) {
            JsonObject functionObj = functionElement.getAsJsonObject();
            String functionName = functionObj.get("FUNCTION_NAME").getAsString();
            JsonObject thresholds = functionObj.getAsJsonObject("THRESHOLDS");
            List<MetricDataQuery> metricQueries = new ArrayList<>();
            int index = 0;
            for (String metricName : thresholds.keySet()) {

                MetricDataQuery query = MetricDataQuery.builder()
                        .id("metric" + index)
                        .metricStat(MetricStat.builder()
                                .metric(Metric.builder()
                                        .namespace("LambdaInsights")
                                        .metricName(metricName)
                                        .dimensions(Dimension.builder()
                                                .name("function_name")
                                                .value(functionName)
                                                .build())
                                        .build())
                                .period(Integer.parseInt(timeRange) / 1000)
                                .stat("Average")
                                .build())
                        .build();
                metricQueries.add(query);
                index++;
            }
            GetMetricDataRequest request = GetMetricDataRequest.builder()
                    .startTime(Instant.ofEpochMilli(startTime))
                    .endTime(Instant.now())
                    .metricDataQueries(metricQueries)
                    .build();
            GetMetricDataResponse response = cw.getMetricData(request);

            Map<String, Object> functionMetricsResponse = new HashMap<>();
            for (MetricDataResult result : response.metricDataResults()) {
                if (result.values().isEmpty()) {  // Check if metric data is empty
                    functionMetricsResponse.put(result.label(), "Metric data not available");
                    continue;
                }
                double actualValue = result.values().get(0);
                String metricName = result.label();

                double thresholdValue = thresholds.get(metricName).getAsDouble();
                String status = (metricName.equals("memory_utilization") && actualValue < thresholdValue) ||
                        (!metricName.equals("memory_utilization") && actualValue > thresholdValue) ?
                        "DOWN" : "UP";

                Map<String, Object> metricResult = new HashMap<>();
                metricResult.put("THRESHOLD_VALUE", thresholdValue);
                metricResult.put("REAL_VALUE", actualValue);
                metricResult.put("STATUS", status);

                functionMetricsResponse.put(metricName, metricResult);
            }

            if (!functionMetricsResponse.isEmpty()) {
                metricsResponse.put(functionName, functionMetricsResponse);
            }
        }

        Map<String, Object> finalResponse = new HashMap<>();
        finalResponse.put("METRICS", metricsResponse);
        return finalResponse;
    }
}
