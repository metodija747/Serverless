package serverless.lib;

import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.*;
import io.swagger.v3.oas.models.responses.*;
import java.lang.reflect.Method;
import java.util.Arrays;


import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import serverless.lib.LambdaDocumentationAnnotations.*;

public class OpenApiGenerator {

    private OpenAPI openAPI;

    public OpenApiGenerator() {
        this(new OpenAPI());
    }

    public OpenApiGenerator(OpenAPI existingOpenAPI) {
        this.openAPI = existingOpenAPI;
        if (this.openAPI.getInfo() == null) {
            Info info = new Info()
                    .title("Your API Title")
                    .version("1.0.0")
                    .description("Description of your API.");
            openAPI.setInfo(info);

            // Setting the base server URL
            Server server = new Server();
            server.setUrl("https://sjmdwpko0k.execute-api.us-east-1.amazonaws.com/Stage/dispatcher");
            openAPI.addServersItem(server);

            // Ensure components is initialized
            if (this.openAPI.getComponents() == null) {
                this.openAPI.setComponents(new Components());
            }
            SecurityScheme jwtSecurityScheme = new SecurityScheme()
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                    .bearerFormat("JWT");

            // Add the Security Scheme to the components section
            openAPI.getComponents().addSecuritySchemes("BearerAuth", jwtSecurityScheme);
        }
    }

    public OpenAPI generateFromLambda(Class<?> lambdaClass) {

            // Inspect the provided class for annotated methods
            for (Method method : lambdaClass.getDeclaredMethods()) {
                Operation operation = new Operation();

                if (method.isAnnotationPresent(LambdaOperation.class)) {
                    // Process the @LambdaOperation annotation
                    LambdaOperation operationAnnotation = method.getAnnotation(LambdaOperation.class);
                    operation.setSummary(operationAnnotation.summary());
                    operation.setDescription(operationAnnotation.description());

                    // Get or create the path item for the current path
                    String httpMethod = method.getAnnotation(LambdaOperation.class).method().toLowerCase();
                    PathItem existingPathItem = openAPI.getPaths() != null ? openAPI.getPaths().get(method.getAnnotation(LambdaOperation.class).path()) : null;
                    PathItem pathItem = existingPathItem != null ? existingPathItem : new PathItem();

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

                // Add security requirement to the operation if the annotation is present
                if (method.isAnnotationPresent(LambdaSecurityRequirement.class)) {
                    LambdaSecurityRequirement securityRequirementAnnotation = method.getAnnotation(LambdaSecurityRequirement.class);
                    SecurityRequirement securityRequirement = new SecurityRequirement().addList(securityRequirementAnnotation.name());
                    operation.addSecurityItem(securityRequirement);
                }

                if (method.isAnnotationPresent(LambdaRequestBody.class)) {
                    LambdaRequestBody requestBodyAnnotation = method.getAnnotation(LambdaRequestBody.class);
                    RequestBody requestBody = new RequestBody()
                            .description(requestBodyAnnotation.description())
                            .required(requestBodyAnnotation.required());

                    Content content = new Content();
                    MediaType mediaType = new MediaType();

                    // Assuming you have an example JSON string provided in your annotation
                    String exampleJson = requestBodyAnnotation.content().schema().example();

                    if (!exampleJson.isEmpty()) {
                        // Parse the example JSON string and set it as an example for Swagger UI
                        mediaType.setExample(exampleJson);
                    }

                    content.addMediaType("application/json", mediaType);
                    requestBody.setContent(content);
                    operation.setRequestBody(requestBody);
                }
            }
            return openAPI;
        }
    }

