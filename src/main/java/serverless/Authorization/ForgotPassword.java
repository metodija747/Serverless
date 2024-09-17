package serverless.Authorization;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.xray.AWSXRay;
import com.amazonaws.xray.entities.Subsegment;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import serverless.lib.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.*;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import serverless.lib.LambdaDocumentationAnnotations.*;

public class ForgotPassword implements RequestHandler<Map<String, Object>, Map<String, Object>> {

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
            summary = "Process forgot password request",
            description = "Initiates a password reset process and sends a confirmation code to the user's registered email.",
            path = "/forgot-password",
            method = "POST"
    )
    @LambdaRequestBody(
            description = "Email of the user to receive the password reset code",
            content = @LambdaContent(
                    mediaType = "application/json",
                    schema = @LambdaSchema(
                            example = "{ \"email\": \"example@example.com\" }"
                    )
            )
    )
    @LambdaAPIResponses({
            @LambdaAPIResponse(responseCode = 200, description = "Confirmation code sent to your email!"),
            @LambdaAPIResponse(responseCode = 404, description = "User with given email address does not exist."),
            @LambdaAPIResponse(responseCode = 500, description = "Failed to send confirmation code")
    })
    @Override
    public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
        return processForgotPassword(event);
    }

    private Map<String, Object> processForgotPassword(Map<String, Object> event) {
        Subsegment forgotPasswordSubsegment = AWSXRay.beginSubsegment("ForgotPassword");
        try {
            String email = getEmailFromEvent(event);
            Subsegment adminGetUserSubsegment = AWSXRay.beginSubsegment("AdminGetUser");
            AdminGetUserRequest adminGetUserRequest = AdminGetUserRequest.builder()
                    .userPoolId((String) configManager.get("USER_POOL_ID"))
                    .username(email)
                    .build();
            cognitoClient.adminGetUser(adminGetUserRequest);
            AWSXRay.endSubsegment(); // End the AdminGetUser subsegment

            Subsegment forgotPasswordRequestSubsegment = AWSXRay.beginSubsegment("SendForgotPasswordRequest");
            ForgotPasswordRequest forgotPasswordRequest = ForgotPasswordRequest.builder()
                    .clientId((String) configManager.get("CLIENT_APP_ID"))
                    .username(email)
                    .build();
            cognitoClient.forgotPassword(forgotPasswordRequest);
            AWSXRay.endSubsegment(); // End the SendForgotPasswordRequest subsegment

            Logger.getLogger(ForgotPassword.class.getName()).info("Confirmation code sent to user's email");
            return ResponseGenerator.generateResponse(200, gson.toJson("Confirmation code sent to your email!"));
        } catch (UserNotFoundException e) {
            Logger.getLogger(ForgotPassword.class.getName()).log(Level.SEVERE, "User with given email address does not exist.", e);
            return ResponseGenerator.generateResponse(404, gson.toJson("User with given email address does not exist."));
        } catch (CognitoIdentityProviderException e) {
            Logger.getLogger(ForgotPassword.class.getName()).log(Level.SEVERE, "Failed to send confirmation code", e);
            throw new RuntimeException("Forgot password failed", e);
        } finally {
            AWSXRay.endSubsegment();
        }
    }

    private String getEmailFromEvent(Map<String, Object> event) {
        Map<String, Object> body = gson.fromJson((String) event.get("body"), new TypeToken<HashMap<String, Object>>(){}.getType());
        return (String) body.get("email");
    }
}
