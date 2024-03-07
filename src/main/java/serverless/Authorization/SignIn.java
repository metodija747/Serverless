package serverless.Authorization;

import com.google.gson.reflect.TypeToken;
import serverless.CatalogProduct.GetProduct;
import serverless.lib.LambdaDocumentationAnnotations.*;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.google.gson.Gson;
import serverless.lib.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.*;

import com.amazonaws.xray.AWSXRay;
import com.amazonaws.xray.entities.Subsegment;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public class SignIn implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private static final Gson gson = new Gson();
    private static ConfigManager configManager;
    private static CognitoIdentityProviderClient cognitoClient;

    @LambdaDocumentationAnnotations.LambdaOperation(
            summary = "Login an existing user",
            description = "This operation logs an existing user into the system.",
            path = "/authorization/login",
            method = "POST"
    )
    @LambdaRequestBody(
            description = "User credentials required for login",
            content = @LambdaContent(
                    mediaType = "application/json",
                    schema = @LambdaSchema(
                            example = "{ \"email\": \"john.doe@example.com\", \"password\": \"your-password\" }"
                    )
            )
    )
    @LambdaAPIResponses({
            @LambdaAPIResponse(responseCode = 200, description = "Successfully logged in"),
            @LambdaAPIResponse(responseCode = 403, description = "Forbidden. Invalid credentials."),
            @LambdaAPIResponse(responseCode = 500, description = "Internal Server Error")
    })
    @Override
    public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
        initializeResources();
        return signInUser(event);
    }

    private synchronized void initializeResources() {
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

    public Map<String, Object> signInUser(Map<String, Object> event) {
        String email;
        String password;
        try {
            Subsegment configSubsegment = AWSXRay.beginSubsegment("collectConfigParams");
            String USER_POOL_ID = (String) configManager.get("USER_POOL_ID");
            String CLIENT_ID = (String) configManager.get("CLIENT_APP_ID");
            String REGION = (String) configManager.get("DYNAMO_REGION");
            AWSXRay.endSubsegment();

            // Extract email and password from the event body
            Subsegment obtainEmailPasswordSubsegment = AWSXRay.beginSubsegment("Obtaining email and password from event");
            Map<String, Object> body = gson.fromJson((String) event.get("body"), new TypeToken<HashMap<String, Object>>(){}.getType());
            email = (String) body.get("email");
            password = (String) body.get("password");
            Logger.getLogger(SignIn.class.getName()).info("" + email + " " + password);
            AWSXRay.endSubsegment();


            // Sign in user subsegment
            Subsegment signInUserSubsegment = AWSXRay.beginSubsegment("Signing in the user and obtaining role");
            signInUserSubsegment.putMetadata("email", email);

            // Build the Cognito client and initiate authentication request
            CognitoIdentityProviderClient cognitoClient = CognitoIdentityProviderClient.builder()
                    .region(Region.of(REGION))
                    .build();
            Map<String, String> authParams = new HashMap<>();
            authParams.put("USERNAME", email);
            authParams.put("PASSWORD", password);

            InitiateAuthRequest authRequest = InitiateAuthRequest.builder()
                    .authFlow(AuthFlowType.USER_PASSWORD_AUTH)
                    .clientId(CLIENT_ID)
                    .authParameters(authParams)
                    .build();
            InitiateAuthResponse authResponse = cognitoClient.initiateAuth(authRequest);

            // Check if user belongs to the "Admins" group
            boolean isAdmin = isAdmin(email, USER_POOL_ID, cognitoClient);
            AWSXRay.endSubsegment(); // End subsegment

            // Logging and response preparation
            Logger.getLogger(SignIn.class.getName()).info("User successfully signed in");
            Map<String, Object> responseBody = new HashMap<>();
            responseBody.put("accessToken", authResponse.authenticationResult().accessToken());
            responseBody.put("idToken", authResponse.authenticationResult().idToken());
            responseBody.put("refreshToken", authResponse.authenticationResult().refreshToken());
            responseBody.put("isAdmin", isAdmin);
            return ResponseGenerator.generateResponse(200, gson.toJson(responseBody));
        } catch (NotAuthorizedException e) {
            AWSXRay.endSubsegment();
            return ResponseGenerator.generateResponse(403, gson.toJson("Forbidden. Invalid credentials."));
        } catch (Exception e) {
            Subsegment failureSubsegment = AWSXRay.beginSubsegment("User sign in failed");
            Logger.getLogger(SignIn.class.getName()).log(Level.SEVERE, "Sign in failed", e);
            AWSXRay.endSubsegment();
            throw new RuntimeException("Sign in failed", e);
        }
    }

    private boolean isAdmin(String email, String userPoolId, CognitoIdentityProviderClient cognitoClient) {
        AdminListGroupsForUserRequest listGroupsRequest = AdminListGroupsForUserRequest.builder()
                .username(email)
                .userPoolId(userPoolId)
                .build();
        AdminListGroupsForUserResponse listGroupsResponse = cognitoClient.adminListGroupsForUser(listGroupsRequest);

        return listGroupsResponse.groups().stream()
                .anyMatch(group -> group.groupName().equals("Admins"));
    }
}