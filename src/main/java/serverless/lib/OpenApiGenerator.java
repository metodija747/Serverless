package serverless.lib;

import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.*;
import io.swagger.v3.oas.models.responses.*;
import java.lang.reflect.Method;
import java.util.Arrays;

import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import serverless.lib.LambdaDocumentationAnnotations.*;

public class OpenApiGenerator {

    private OpenAPI openAPI;

    public OpenApiGenerator() {
        this(new OpenAPI());
    }

    public OpenApiGenerator(OpenAPI existingOpenAPI) {
        this.openAPI = existingOpenAPI != null ? existingOpenAPI : new OpenAPI();

        // Initialize components if they don't exist
        if (this.openAPI.getComponents() == null) {
            this.openAPI.setComponents(new Components());
        }

        // Create and add the JWT Security Scheme to the components
        SecurityScheme jwtSecurityScheme = new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")
                .name("Authorization");

        this.openAPI.getComponents().addSecuritySchemes("BearerAuth", jwtSecurityScheme);
        // Ensure the Info section is set up
        if (this.openAPI.getInfo() == null) {
            Info info = new Info()
                    .title("Your API Title")
                    .version("1.0.0")
                    .description("Description of your API.");
            this.openAPI.setInfo(info);
        }

        // Setting the base server URL
        Server server = new Server();
        server.setUrl("https://xr51u2pzwg.execute-api.us-east-1.amazonaws.com/Stage/dispatcher");
        this.openAPI.addServersItem(server);
    }

    public OpenAPI generateFromLambda(Class<?> lambdaClass) {
        // Inspect the provided class for annotated methods
        for (Method method : lambdaClass.getDeclaredMethods()) {
            if (method.isAnnotationPresent(LambdaOperation.class)) {
                // Process the @LambdaOperation annotation
                LambdaOperation operationAnnotation = method.getAnnotation(LambdaOperation.class);
                Operation operation = new Operation()
                        .summary(operationAnnotation.summary())
                        .description(operationAnnotation.description());

                // Process the @LambdaParameters annotation, if present
                if (method.isAnnotationPresent(LambdaParameters.class)) {
                    LambdaParameters parametersAnnotation = method.getAnnotation(LambdaParameters.class);
                    for (LambdaParameter paramAnnotation : parametersAnnotation.value()) {
                        Parameter parameter = new Parameter()
                                .name(paramAnnotation.name())
                                .description(paramAnnotation.description())
                                .in(paramAnnotation.in().name().toLowerCase())
                                .example(paramAnnotation.example());

                        // If there's a schema defined, set it
                        if (paramAnnotation.schema() != null) {
                            Schema schema = new Schema();
                            if (paramAnnotation.schema().enumeration().length > 0) {
                                schema.setEnum(Arrays.asList(paramAnnotation.schema().enumeration()));
                            }
                            parameter.setSchema(schema);
                        }
                        operation.addParametersItem(parameter);
                    }
                }

                // Process the @LambdaAPIResponses annotation, if present
                if (method.isAnnotationPresent(LambdaAPIResponses.class)) {
                    LambdaAPIResponses responsesAnnotation = method.getAnnotation(LambdaAPIResponses.class);
                    ApiResponses apiResponses = new ApiResponses();
                    for (LambdaAPIResponse respAnnotation : responsesAnnotation.value()) {
                        ApiResponse apiResponse = new ApiResponse().description(respAnnotation.description());
                        apiResponses.addApiResponse(String.valueOf(respAnnotation.responseCode()), apiResponse);
                    }
                    operation.setResponses(apiResponses);
                }

                String httpMethod = operationAnnotation.method().toLowerCase();
                PathItem pathItem = new PathItem();
                switch (httpMethod) {
                    case "get":
                        pathItem.setGet(operation);
                        break;
                    case "post":
                        pathItem.setPost(operation);
                        break;
                    case "put":
                        pathItem.setPut(operation);
                        break;
                    case "delete":
                        pathItem.setDelete(operation);
                        break;
                    default:
                        throw new IllegalArgumentException("Unsupported HTTP method: " + httpMethod);
                }
                openAPI.path(operationAnnotation.path(), pathItem);
            }
        }
        return openAPI;
    }
}
