package serverless.lib;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.*;

import java.time.Duration;

public class MetricsHandler {

    private final CloudWatchClient cloudWatch;
    private final String namespace;
    private String lambdaFunctionName;

    public MetricsHandler() {
        this.cloudWatch = CloudWatchClient.builder()
                .region(Region.US_EAST_1) // Set the region here
                .build();
        this.namespace = "FaultTolerance";
    }
    public void setLambdaFunctionName(String lambdaFunctionName) {
        this.lambdaFunctionName = lambdaFunctionName;
    }
//    circuit is closed and the call is successful
    public void incrementCallsSucceeded() {
        publishMetric("circuitBreaker.callsSucceeded.total", 1, StandardUnit.COUNT);
    }
//      circuit is closed and the call is unsuccessful
    public void incrementCallsFailed() {
        publishMetric("circuitBreaker.callsFailed.total", 1, StandardUnit.COUNT);
    }

//    circuit is opened
    public void incrementCallsPrevented() {
        publishMetric("circuitBreaker.callsPrevented.total", 1, StandardUnit.COUNT);
    }
//    first try successful
    public void incrementSuccessfulCallsWithoutRetries() {
        publishMetric("retry.callsSucceededNotRetried.total", 1, StandardUnit.COUNT);
    }
// number of calls that succeeded if retried
    public void incrementSuccessfulCallsWithRetries() {
        publishMetric("retry.callsSucceededRetried.total", 1, StandardUnit.COUNT);
    }
//  number of retries that failed
    public void incrementFailedCalls() {
        publishMetric("retry.callsFailed.total", 1, StandardUnit.COUNT);
    }

    public void incrementCallsTimedOut() {
        publishMetric("timeout.callsTimedOut.total", 1, StandardUnit.COUNT);
    }

    public void incrementCallsNotTimedOut() {
        publishMetric("timeout.callsNotTimedOut.total", 1, StandardUnit.COUNT);
    }

    public void incrementFallbackCalls() {
        publishMetric("fallback.calls.total", 1, StandardUnit.COUNT);
    }

    private void publishMetric(String metricName, double value, StandardUnit unit) {
        System.out.println("Lambda Function Name: " + lambdaFunctionName);

        PutMetricDataRequest request = PutMetricDataRequest.builder()
                .namespace(namespace)
                .metricData(MetricDatum.builder()
                        .metricName(metricName)
                        .dimensions(Dimension.builder().name("LambdaFunction").value(lambdaFunctionName).build())
                        .value(value)
                        .unit(unit)
                        .build())
                .build();
        cloudWatch.putMetricData(request);
    }
}
