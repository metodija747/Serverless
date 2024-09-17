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
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import serverless.lib.LambdaDocumentationAnnotations.*;

public class ConfirmForgotPassword implements RequestHandler<Map<String, Object>, Map<String, Object>> {

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
            summary = "Process confirm forgot password request",
            description = "Validates confirmation code and sets new password for the user.",
            path = "/confirm-forgot-password",
            method = "POST"
    )
    @LambdaRequestBody(
            description = "Details including email, confirmation code, and new password",
            content = @LambdaContent(
                    mediaType = "application/json",
                    schema = @LambdaSchema(
                            example = "{ \"email\": \"example@example.com\", \"confirmationCode\": \"123456\", \"newPassword\": \"newPassword!\" }"
                    )
            )
    )
    @LambdaAPIResponses({
            @LambdaAPIResponse(responseCode = 200, description = "Password changed successfully"),
            @LambdaAPIResponse(responseCode = 500, description = "Failed to change password")
    })
    @Override
    public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
        return processConfirmForgotPassword(event);
    }

    private Map<String, Object> processConfirmForgotPassword(Map<String, Object> event) {
        try {
            Subsegment configSubsegment = AWSXRay.beginSubsegment("CollectConfigParams");
            String CLIENT_ID = (String) configManager.get("CLIENT_APP_ID");
            AWSXRay.endSubsegment();

            Subsegment obtainNewUserInfoSubsegment = AWSXRay.beginSubsegment("ObtainingNewUserInfo");
            String eventBody = (String) event.get("body");
            Map<String, String> eventBodyMap = gson.fromJson(eventBody, new TypeToken<Map<String, String>>() {}.getType());
            String email = eventBodyMap.get("email");
            String confirmationCode = eventBodyMap.get("confirmationCode");
            String newPassword = eventBodyMap.get("newPassword");
            AWSXRay.endSubsegment();

            Subsegment resetingPasswordSubsegment = AWSXRay.beginSubsegment("ResettingPassword");
            resetingPasswordSubsegment.putMetadata("email", email);
            ConfirmForgotPasswordRequest confirmForgotPasswordRequest = ConfirmForgotPasswordRequest.builder()
                    .clientId(CLIENT_ID)
                    .username(email)
                    .confirmationCode(confirmationCode)
                    .password(newPassword)
                    .build();
            cognitoClient.confirmForgotPassword(confirmForgotPasswordRequest);
            AWSXRay.endSubsegment();

            Logger.getLogger(ConfirmForgotPassword.class.getName()).info("Password changed successfully");
            return ResponseGenerator.generateResponse(200, gson.toJson("Password changed successfully"));
        } catch (CognitoIdentityProviderException e) {
            Subsegment failureSubsegment = AWSXRay.beginSubsegment("ConfirmForgotPasswordFailed");
            Logger.getLogger(ConfirmForgotPassword.class.getName()).log(Level.SEVERE, "Failed to change password", e);
            AWSXRay.endSubsegment();
            throw new RuntimeException("ConfirmForgotPassword failed", e);
        }
    }
}
