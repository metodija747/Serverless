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
            path = "/users/delete/{email}",
            method = "DELETE"
    )
    @LambdaAPIResponses({
            @LambdaAPIResponse(responseCode = 200, description = "User deleted successfully"),
            @LambdaAPIResponse(responseCode = 400, description = "User does not exist"),
            @LambdaAPIResponse(responseCode = 401, description = "Invalid token"),
            @LambdaAPIResponse(responseCode = 403, description = "Not authorized to delete this user"),
            @LambdaAPIResponse(responseCode = 500, description = "Failed to delete user")
    })
    @LambdaSecurityRequirement(name = "BearerAuth")
    @Override
    public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
        return deleteUser(event);
    }

    private Map<String, Object> deleteUser(Map<String, Object> event) {
        try {
            Subsegment configSubsegment = AWSXRay.beginSubsegment("CollectConfigParams");
            String USER_POOL_ID = (String) configManager.get("USER_POOL_ID");
            String REGION = (String) configManager.get("DYNAMO_REGION");
            String ISSUER = (String) configManager.get("ISSUER");
            AWSXRay.endSubsegment();

            Subsegment authenticationSubsegment = AWSXRay.beginSubsegment("AuthenticatingUser");
            String idToken = ((Map<String, String>) event.get("headers")).get("Authorization");
            DecodedJWT decodedJWT = JWT.decode(idToken);
            String userId;
            try {
                userId = TokenVerifier.verifyToken(idToken, ISSUER);
            } catch (Exception e) {
                Logger.getLogger(DeleteUser.class.getName()).log(Level.SEVERE, "Failed to authenticate user", e);
                return ResponseGenerator.generateResponse(401, "Invalid token.");
            }
            String email = decodedJWT.getClaim("cognito:username").asString();
            String pathEmail = URLDecoder.decode(((Map<String, String>) event.get("pathParameters")).get("email"), "UTF-8");
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

            Logger.getLogger(DeleteUser.class.getName()).info("User successfully deleted");
            return ResponseGenerator.generateResponse(200, gson.toJson("User deleted successfully"));
        } catch (UnsupportedEncodingException e) {
            Subsegment failureSubsegment = AWSXRay.beginSubsegment("DeletingUserFailed");
            Logger.getLogger(DeleteUser.class.getName()).log(Level.SEVERE, "Failed to delete user", e);
            AWSXRay.endSubsegment();
            throw new RuntimeException("Deleting user failed due to UnsupportedEncodingException", e);
        } catch (CognitoIdentityProviderException e) {
            Subsegment failureSubsegment = AWSXRay.beginSubsegment("DeletingUserFailed");
            Logger.getLogger(DeleteUser.class.getName()).log(Level.SEVERE, "Failed to delete user", e);
            AWSXRay.endSubsegment();
            if (e.statusCode() == 400 && e.getMessage().contains("User does not exist.")) {
                return ResponseGenerator.generateResponse(400, gson.toJson("Failed to delete user because it does not exist."));
            } else {
                throw new RuntimeException("Deleting user failed due to CognitoIdentityProviderException", e);
            }
        }
    }
}
