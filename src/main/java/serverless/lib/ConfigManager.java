package serverless.lib;

import com.google.gson.Gson;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class ConfigManager {
    private Map<String, String> config;

    public ConfigManager() {
        // Load default configuration from config.json
        loadDefaultConfig();

        // Override with values from Parameter Store
        overrideWithParameterStore();
    }

    private void loadDefaultConfig() {
        try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream("config.json")) {
            if (inputStream == null) {
                throw new FileNotFoundException("Config file not found");
            }
            Gson gson = new Gson();
            try (Reader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {
                config = gson.fromJson(reader, Map.class);
            }
        } catch (Exception e) {
            // Handle exception, e.g., log error, throw a runtime exception, etc.
            throw new RuntimeException("Failed to load default configuration", e);
        }
    }
    private void overrideWithParameterStore() {
        // Override values with Parameter Store if they exist
        overrideIfNotNull("USER_POOL_ID");
        overrideIfNotNull("CLIENT_APP_ID");
        overrideIfNotNull("DYNAMO_REGION");
        overrideIfNotNull("ISSUER");
        overrideIfNotNull("CART_TABLE");
        overrideIfNotNull("ORDERS_TABLE");
        overrideIfNotNull("PRODUCT_TABLE");
    }

    private void overrideIfNotNull(String key) {
        String value = getParameter(key);
        if (value != null) {
            config.put(key, value);
        }
    }

    public String get(String key) {
        return config.get(key);
    }

    private String getParameter(String parameterName) {
        try {
            SsmClient ssmClient = SsmClient.builder()
                    .region(Region.US_EAST_1)
                    .build();

            GetParameterRequest request = GetParameterRequest.builder()
                    .name(parameterName)
                    .withDecryption(true)
                    .build();

            GetParameterResponse response = ssmClient.getParameter(request);
            System.out.println("Fetched value for " + parameterName + ": " + response.parameter().value());
            return response.parameter().value();
        } catch (Exception e) {
            System.err.println("Error fetching value for " + parameterName + ": " + e.getMessage());
            return null;
        }
    }
}
