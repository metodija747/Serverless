package serverless.lib;

//import io.swagger.models.Operation;
import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.*;
import io.swagger.v3.oas.models.responses.*;
import java.lang.reflect.Method;
import java.util.Arrays;
//import io.swagger.models.*;

import io.swagger.v3.oas.models.servers.Server;
import serverless.lib.LambdaDocumentationAnnotations.*;

public class OpenApiGenerator {

    private OpenAPI openAPI;

    public OpenApiGenerator() {
        this.openAPI = new OpenAPI();
        Info info = new Info()
                .title("Your API Title")
                .version("1.0.0")
                .description("Description of your API.");
        openAPI.setInfo(info);

        // Setting the base server URL
        Server server = new Server();
        server.setUrl("https://xr51u2pzwg.execute-api.us-east-1.amazonaws.com/Stage/dispatcher");
        openAPI.addServersItem(server);
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

                // Add the constructed operation to the OpenAPI model (assuming a GET method for this example)
                PathItem pathItem = new PathItem().get(operation);
                openAPI.path("/catalog", pathItem); // Replace "/your-path-here" with the actual path for your Lambda function
            }
        }
        return openAPI;
    }
}
