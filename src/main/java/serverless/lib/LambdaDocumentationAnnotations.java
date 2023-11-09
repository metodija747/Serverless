package serverless.lib;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

public final class LambdaDocumentationAnnotations {

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    public @interface LambdaOperation {
        String summary();
        String description() default "";
        String path();
        String method();
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    public @interface LambdaRequestBody {
        String description() default "";
        boolean required() default true;
        LambdaContent content();
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    public @interface LambdaContent {
        String mediaType() default "application/json";
        LambdaSchema schema();
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    public @interface LambdaSchema {
        Class<?> implementation() default Void.class;
        String example() default "";
        String[] enumeration() default {};
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    public @interface LambdaAPIResponses {
        LambdaAPIResponse[] value();
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    public @interface LambdaAPIResponse {
        int responseCode();
        String description();
    }

    public enum ParameterIn {
        QUERY, HEADER, PATH, COOKIE
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.METHOD, ElementType.PARAMETER})
    public @interface LambdaParameter {
        String name();
        String description() default "";
        ParameterIn in() default ParameterIn.QUERY;
        String example() default "";
        LambdaSchema schema() default @LambdaSchema();
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    public @interface LambdaParameters {
        LambdaParameter[] value();
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface LambdaSecurityScheme {
        String name();
        String type();
        String in();
        String bearerFormat() default "";
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface LambdaSecurityRequirement {
        String name();
    }
}
