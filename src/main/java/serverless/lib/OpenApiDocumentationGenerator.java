package serverless.lib;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.swagger.v3.oas.models.OpenAPI;
import serverless.CatalogProduct.GetAndSearchProducts;

import com.fasterxml.jackson.annotation.JsonIgnore;

public class OpenApiDocumentationGenerator {

    // Mixin class to ignore exampleSetFlag property
    abstract class SchemaMixin {
        @JsonIgnore
        private Boolean exampleSetFlag;
    }

    public static void main(String[] args) {
        OpenApiGenerator generator = new OpenApiGenerator();
        OpenAPI openAPI = generator.generateFromLambda(GetAndSearchProducts.class);

        try {
            ObjectMapper objectMapper = new ObjectMapper();
            objectMapper.addMixIn(io.swagger.v3.oas.models.media.Schema.class, SchemaMixin.class);  // Add the mixin
            objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL); // Ignore null fields

            String openApiJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(openAPI);

            // Save to file
            java.nio.file.Files.write(java.nio.file.Paths.get("openapi.json"), openApiJson.getBytes());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

