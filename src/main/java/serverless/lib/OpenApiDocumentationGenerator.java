package serverless.lib;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.OpenAPI;
import serverless.CatalogProduct.GetAndSearchProducts;

import com.fasterxml.jackson.annotation.JsonIgnore;
import serverless.CatalogProduct.GetProduct;

public class OpenApiDocumentationGenerator {

    // Mixin class to ignore exampleSetFlag property
    abstract class SchemaMixin {
        @JsonIgnore
        private Boolean exampleSetFlag;
    }

    public static void main(String[] args) {
        OpenAPI aggregatedOpenAPI = new OpenAPI();
        OpenApiGenerator generator = new OpenApiGenerator(aggregatedOpenAPI);
        Class<?>[] lambdaClasses = {
                GetAndSearchProducts.class,
                GetProduct.class
        };
        for (Class<?> lambdaClass : lambdaClasses) {
            generator.generateFromLambda(lambdaClass);
        }
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            objectMapper.addMixIn(io.swagger.v3.oas.models.media.Schema.class, SchemaMixin.class);  // Add the mixin
            objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL); // Ignore null fields

            String openApiJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(aggregatedOpenAPI);

            // Save to file
            java.nio.file.Files.write(java.nio.file.Paths.get("openapi.json"), openApiJson.getBytes());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

