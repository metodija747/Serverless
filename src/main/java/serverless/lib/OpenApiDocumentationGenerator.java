package serverless.lib;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.models.OpenAPI;
import serverless.Authorization.RegisterUser;
import serverless.Authorization.SignIn;
import serverless.CatalogProduct.AddNewProduct;
import serverless.CatalogProduct.GetAndSearchProducts;

import com.fasterxml.jackson.annotation.JsonIgnore;
import serverless.CatalogProduct.GetProduct;
import serverless.CatalogProduct.GetProductComments;
import serverless.ShoppingCart.GetOrders;

public class OpenApiDocumentationGenerator {

    // Mixin class to ignore exampleSetFlag property
    abstract class SchemaMixin {
        @JsonIgnore
        private Boolean exampleSetFlag;
    }

    public static void main(String[] args) {
        OpenAPI aggregatedOpenAPI = new OpenAPI();
        OpenApiGenerator generator = new OpenApiGenerator(aggregatedOpenAPI);
//        insert here all classes for which we added open api specification
        Class<?>[] lambdaClasses = {
                GetAndSearchProducts.class,
                GetProduct.class,
                AddNewProduct.class,
                SignIn.class,
                RegisterUser.class,
                GetProductComments.class,
                GetOrders.class
        };
        for (Class<?> lambdaClass : lambdaClasses) {
            generator.generateFromLambda(lambdaClass);
        }
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            objectMapper.addMixIn(io.swagger.v3.oas.models.media.Schema.class, SchemaMixin.class);  // Add the mixin
            objectMapper.addMixIn(io.swagger.v3.oas.models.media.MediaType.class, SchemaMixin.class);
            objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL); // Ignore null fields

            String openApiJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(aggregatedOpenAPI);
            JsonNode jsonNode = objectMapper.readTree(openApiJson);
            // Replace "HTTP" with "http" in the JSON string.
            ((ObjectNode) jsonNode.at("/components/securitySchemes/BearerAuth")).put("type", "http");
            openApiJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonNode);

            // Save to file
            java.nio.file.Files.write(java.nio.file.Paths.get("openapi.json"), openApiJson.getBytes());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

