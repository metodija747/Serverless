package serverless.Authorization;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.xray.entities.Subsegment;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import serverless.lib.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.*;
import com.amazonaws.xray.AWSXRay;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import serverless.lib.LambdaDocumentationAnnotations.*;

public class RegisterUser implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private static final Gson gson = new Gson();
    private static ConfigManager configManager;
    private static CognitoIdentityProviderClient cognitoClient;

    static {
        initializeResources();
    }

    private static synchronized void initializeResources() {
        if (configManager == null) {
            configManager = new ConfigManager();
        }
        if (cognitoClient == null) {
            String REGION = (String) configManager.get("DYNAMO_REGION");
            cognitoClient = CognitoIdentityProviderClient.builder()
                    .region(Region.of(REGION))
                    .build();
        }
    }

    @LambdaOperation(
            summary = "Register a new user",
            description = "This operation registers a new user in cognito.",
            path = "/authorization/register",
            method = "POST"
    )
    @LambdaRequestBody(
            description = "User details required for registration",
            content = @LambdaContent(
                    mediaType = "application/json",
                    schema = @LambdaSchema(
                            example = "{ \"email\": \"john.doe@example.com\", \"password\": \"your-password\" }"
                    )
            )
    )
    @LambdaAPIResponses({
            @LambdaAPIResponse(responseCode = 200, description = "User registered successfully"),
            @LambdaAPIResponse(responseCode = 403, description = "Forbidden. User already exists."),
            @LambdaAPIResponse(responseCode = 500, description = "Unable to register user at the moment. Please try again later.")
    })
    @Override
    public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
        return registerUser(event);
    }

    private Map<String, Object> registerUser(Map<String, Object> event) {
        String email;
        String password;
        try {
            Subsegment configSubsegment = AWSXRay.beginSubsegment("collectConfigParams");
            // Use ConfigManager to get configuration values
            String USER_POOL_ID = (String) configManager.get("USER_POOL_ID");
            String CLIENT_APP_ID = (String) configManager.get("CLIENT_APP_ID");
            AWSXRay.endSubsegment();

            Subsegment obtainEmailPasswordSubsegment = AWSXRay.beginSubsegment("Obtaining email and password from event");
            Map<String, Object> body = gson.fromJson((String) event.get("body"), new TypeToken<HashMap<String, Object>>(){}.getType());
            email = (String) body.get("email");
            password = (String) body.get("password");
            AWSXRay.endSubsegment();

            Subsegment signUpUserSubsegment = AWSXRay.beginSubsegment("Signing up the user");
            AttributeType emailAttribute = AttributeType.builder()
                    .name("email")
                    .value(email)
                    .build();
            SignUpRequest signUpRequest = SignUpRequest.builder()
                    .clientId(CLIENT_APP_ID)
                    .username(email)
                    .password(password)
                    .userAttributes(emailAttribute)
                    .build();
            cognitoClient.signUp(signUpRequest);
            AWSXRay.endSubsegment();
            Subsegment confirmEmailSubsegment = AWSXRay.beginSubsegment("Confirming user's email address");
            confirmEmailSubsegment.putMetadata("email", email);
            AdminConfirmSignUpRequest confirmSignUpRequest = AdminConfirmSignUpRequest.builder()
                    .userPoolId(USER_POOL_ID)
                    .username(email)
                    .build();
            cognitoClient.adminConfirmSignUp(confirmSignUpRequest);
            AttributeType emailVerifiedAttribute = AttributeType.builder()
                    .name("email_verified")
                    .value("true")
                    .build();
            AdminUpdateUserAttributesRequest updateUserAttributesRequest = AdminUpdateUserAttributesRequest.builder()
                    .userPoolId(USER_POOL_ID)
                    .username(email)
                    .userAttributes(emailVerifiedAttribute)
                    .build();
            cognitoClient.adminUpdateUserAttributes(updateUserAttributesRequest);
            AWSXRay.endSubsegment();

            Logger.getLogger(RegisterUser.class.getName()).info("User successfully registered");
            return ResponseGenerator.generateResponse(200, gson.toJson("User registered successfully"));
        } catch (UsernameExistsException e) {
            AWSXRay.endSubsegment();
            return ResponseGenerator.generateResponse(403, gson.toJson("Forbidden. User already exists."));
        } catch (Exception e) {
            Subsegment failureSubsegment = AWSXRay.beginSubsegment("User registration failed");
            Logger.getLogger(RegisterUser.class.getName()).log(Level.SEVERE, "Registration failed", e);
            AWSXRay.endSubsegment();
            throw new RuntimeException("Registration failed", e);
        }
    }

}
