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
    private Map<String, Object> config;
    private final SsmClient ssmClient;

    public ConfigManager() {
        // Initialize the SSM Client, MAY NEED CHANGE FOR DIFFERENT REGION
        ssmClient = SsmClient.builder()
                .region(Region.US_EAST_1)
                .build();

        // Load the default configuration
        loadDefaultConfig();
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
            throw new RuntimeException("Failed to load default configuration", e);
        }
    }

    public Object get(String key) {
        // Try to get the value from the Parameter Store first
        String parameterValue = getParameter(key);
        if (parameterValue != null) {
            // If a value is found, use it and update the config map
            config.put(key, parameterValue);
            return parameterValue;
        }
        // If not found in the Parameter Store, return the value from the config map (default value)
        return config.get(key);
    }

    private String getParameter(String parameterName) {
        try {
            GetParameterRequest request = GetParameterRequest.builder()
                    .name(parameterName)
                    .withDecryption(true)
                    .build();

            GetParameterResponse response = ssmClient.getParameter(request);
            return response.parameter().value();
        } catch (Exception e) {
            return null;
        }
    }
}
