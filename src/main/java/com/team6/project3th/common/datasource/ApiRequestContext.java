package com.team6.project3th.common.datasource;

public class ApiRequestContext {

    private static final ThreadLocal<String> URI = new ThreadLocal<>();
    private static final ThreadLocal<String> METHOD = new ThreadLocal<>();

    public static void set(String method, String uri) {
        METHOD.set(method);
        URI.set(uri);
    }

    public static String getUri() {
        return URI.get() == null ? "unknown" : URI.get();
    }

    public static String getMethod() {
        return METHOD.get() == null ? "unknown" : METHOD.get();
    }

    public static void clear() {
        URI.remove();
        METHOD.remove();
    }
}