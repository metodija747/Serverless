package serverless.Authorization;

import com.auth0.jwt.JWT;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.xray.AWSXRay;
import com.amazonaws.xray.entities.Subsegment;
import com.google.gson.Gson;
import serverless.lib.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.*;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.net.URLDecoder;
import java.io.UnsupportedEncodingException;
import serverless.lib.LambdaDocumentationAnnotations.*;

public class DeleteUser implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private static final Gson gson = new Gson();
    private static final Logger logger = Logger.getLogger(DeleteUser.class.getName());
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
            summary = "Delete a user",
            description = "Deletes a user from AWS Cognito user pool after verifying their identity.",
            path = "/users/{email}",
            method = "DELETE"
    )
    @LambdaAPIResponses({
            @LambdaAPIResponse(responseCode = 200, description = "User deleted successfully."),
            @LambdaAPIResponse(responseCode = 400, description = "User cannot be deleted because it is not present in the database."),
            @LambdaAPIResponse(responseCode = 401, description = "Invalid token."),
            @LambdaAPIResponse(responseCode = 403, description = "Not authorized to delete this user."),
            @LambdaAPIResponse(responseCode = 500, description = "Failed to delete user due to an internal error.")
    })
    @LambdaSecurityRequirement(name = "BearerAuth")
    @Override
    public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
        return deleteUser(event);
    }

    private Map<String, Object> deleteUser(Map<String, Object> event) {
        try {
            // Log the entire event to understand the structure
            logger.info("Received event: " + gson.toJson(event));

            Subsegment configSubsegment = AWSXRay.beginSubsegment("CollectConfigParams");
            String USER_POOL_ID = (String) configManager.get("USER_POOL_ID");
            String REGION = (String) configManager.get("DYNAMO_REGION");
            String ISSUER = (String) configManager.get("ISSUER");
            AWSXRay.endSubsegment();

            Subsegment authenticationSubsegment = AWSXRay.beginSubsegment("AuthenticatingUser");
            String authHeader = ((Map<String, String>) event.get("headers")).get("Authorization");
            String idToken = "";
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                idToken = authHeader.substring("Bearer ".length());
            }
            DecodedJWT decodedJWT = JWT.decode(idToken);
            String userId;
            try {
                userId = TokenVerifier.verifyToken(idToken, ISSUER);
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Failed to authenticate user", e);
                return ResponseGenerator.generateResponse(401, "Invalid token.");
            }

            // Correctly get the email claim from the token
            String email = decodedJWT.getClaim("email").asString();

            // Extract email from path parameters using proxy method
            Subsegment extractParamsSubsegment = AWSXRay.beginSubsegment("ExtractingParameters");
            Map<String, String> pathParameters = (Map<String, String>) event.get("pathParameters");
            if (pathParameters == null || !pathParameters.containsKey("proxy")) {
                logger.log(Level.SEVERE, "Proxy path parameters are missing or invalid");
                return ResponseGenerator.generateResponse(400, "Missing or invalid path parameters.");
            }

            String proxyValue = pathParameters.get("proxy");
            if (proxyValue == null || proxyValue.isEmpty()) {
                logger.log(Level.SEVERE, "Proxy value is missing or empty");
                return ResponseGenerator.generateResponse(400, "Proxy value is missing or empty.");
            }

            // Extracting email from proxy path
            String[] parts = proxyValue.split("/");
            String pathEmail = parts[parts.length - 1];
            logger.info("Extracted email from URL: " + pathEmail);

            try {
                pathEmail = URLDecoder.decode(pathEmail, "UTF-8");
            } catch (UnsupportedEncodingException e) {
                logger.log(Level.SEVERE, "Error decoding email parameter", e);
                return ResponseGenerator.generateResponse(400, "Invalid email format.");
            }

            if (!email.equals(pathEmail)) {
                return ResponseGenerator.generateResponse(403, gson.toJson("Not authorized to delete this user."));
            }
            AWSXRay.endSubsegment();

            Subsegment deleteUserSubsegment = AWSXRay.beginSubsegment("DeletingUserFromCognito");
            deleteUserSubsegment.putMetadata("email", email);
            AdminDeleteUserRequest deleteUserRequest = AdminDeleteUserRequest.builder()
                    .userPoolId(USER_POOL_ID)
                    .username(email)
                    .build();
            cognitoClient.adminDeleteUser(deleteUserRequest);
            AWSXRay.endSubsegment();

            logger.info("User successfully deleted");
            return ResponseGenerator.generateResponse(200, gson.toJson("User deleted successfully"));
        } catch (CognitoIdentityProviderException e) {
            Subsegment failureSubsegment = AWSXRay.beginSubsegment("DeletingUserFailed");
            logger.log(Level.SEVERE, "Failed to delete user", e);
            AWSXRay.endSubsegment();
            if (e.statusCode() == 400 && e.getMessage().contains("User does not exist.")) {
                return ResponseGenerator.generateResponse(400, gson.toJson("User cannot be deleted because it is not present in the database."));
            } else {
                throw new RuntimeException("Deleting user failed due to CognitoIdentityProviderException", e);
            }
        } catch (Exception e) {
            Subsegment failureSubsegment = AWSXRay.beginSubsegment("DeletingUserFailed");
            logger.log(Level.SEVERE, "An unexpected error occurred while deleting the user", e);
            AWSXRay.endSubsegment();
            throw new RuntimeException("Failed to delete user", e);
        }
    }
}
