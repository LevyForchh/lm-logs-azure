package com.logicmonitor.logs.azure;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.logicmonitor.logs.LMLogsApi;
import com.logicmonitor.logs.invoker.ApiException;
import com.logicmonitor.logs.invoker.ApiResponse;
import com.logicmonitor.logs.model.LogEntry;
import com.logicmonitor.logs.model.LogResponse;
import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.annotation.EventHubTrigger;
import com.microsoft.azure.functions.annotation.FunctionName;

/**
 * Azure Function forwarding Azure logs to LogicMonitor endpoint.<br>
 * It is parametrized using the following environment variables:
 * <ul>
 * <li>{@value #PARAMETER_COMPANY_NAME} company in the target URL '{company}.logicmonitor.com'
 * <li>{@value #PARAMETER_ACCESS_ID} LogicMonitor access ID
 * <li>{@value #PARAMETER_ACCESS_KEY} LogicMonitor access key
 * <li>{@value #PARAMETER_CONNECT_TIMEOUT} Connection timeout in milliseconds (default 10000)
 * <li>{@value #PARAMETER_READ_TIMEOUT} Read timeout in milliseconds (default 10000)
 * <li>{@value #PARAMETER_DEBUGGING} HTTP client debugging
 * </ul>
 */
public class LogEventForwarder {
    /**
     * Parameter: company in the target URL '{company}.logicmonitor.com'.
     */
    public static final String PARAMETER_COMPANY_NAME = "LogicMonitorCompanyName";
    /**
     * Parameter: LogicMonitor access ID.
     */
    public static final String PARAMETER_ACCESS_ID = "LogicMonitorAccessId";
    /**
     * Parameter: LogicMonitor access key.
     */
    public static final String PARAMETER_ACCESS_KEY = "LogicMonitorAccessKey";
    /**
     * Parameter: connection timeout in milliseconds (default 10000).
     */
    public static final String PARAMETER_CONNECT_TIMEOUT = "LogApiClientConnectTimeout";
    /**
     * Parameter: read timeout in milliseconds (default 10000).
     */
    public static final String PARAMETER_READ_TIMEOUT = "LogApiClientReadTimeout";
    /**
     * Parameter: HTTP client debugging.
     */
    public static final String PARAMETER_DEBUGGING = "LogApiClientDebugging";

    /**
     * Transforms Azure log events into log entries.
     */
    private static final LogEventAdapter ADAPTER = new LogEventAdapter();
    /**
     * API for sending log requests.
     */
    private static LMLogsApi api;

    /**
     * Gets the API instance (initializes it when needed).
     * @return LMLogsApi instance
     */
    protected synchronized static LMLogsApi getApi() {
        // The initialization must be lazy due to the tests
        // - they must set the environmental variables first.
        if (api == null) {
            api = configureApi();
        }
        return api;
    }

    /**
     * Configures API using the environment variables.
     * @return LMLogsApi instance
     */
    protected static LMLogsApi configureApi() {
        LMLogsApi api = new LMLogsApi(
                System.getenv(PARAMETER_COMPANY_NAME),
                System.getenv(PARAMETER_ACCESS_ID),
                System.getenv(PARAMETER_ACCESS_KEY)
        );
        setProperty(PARAMETER_CONNECT_TIMEOUT, Integer::valueOf,
                api.getApiClient()::setConnectTimeout);
        setProperty(PARAMETER_READ_TIMEOUT, Integer::valueOf,
                api.getApiClient()::setReadTimeout);
        setProperty(PARAMETER_DEBUGGING, Boolean::valueOf,
                api.getApiClient()::setDebugging);
        return api;
    }

    /**
     * Reads an environment variable and sets using the specified consumer
     * when not null nor empty.
     * @param <T> type of the variable
     * @param name name of the variable
     * @param mapper function mapping String to the desired type
     * @param setter consumer setting the property
     */
    private static <T> void setProperty(String name, Function<String, T> mapper,
            Consumer<T> setter) {
        Optional.ofNullable(System.getenv(name))
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .map(mapper)
            .ifPresent(setter);
    }

    /**
     * The main method of the Azure Log Forwarder, triggered by events consumed
     * from the configured Event Hub.
     * @param logEvents JSON array containing Azure events
     * @param context execution context
     */
    @FunctionName("LogForwarder")
    public void forward(
            @EventHubTrigger(name = "logEvents", eventHubName = "eventHub",
                    connection = "LogsEventHubConnectionString") JsonArray logEvents,
            final ExecutionContext context
    ) {
        List<LogEntry> logEntries = processEvents(logEvents);
        if (logEntries.isEmpty()) {
            log(context, Level.INFO, () -> "No entries to send");
            return;
        }

        log(context, Level.INFO, () -> "Sending " + logEntries.size() + " log entries");
        log(context, Level.FINEST, () -> "Request body: " + logEntries);
        try {
            ApiResponse<LogResponse> response = getApi().logIngestPostWithHttpInfo(logEntries);
            logResponse(context, response.getData().getSuccess(), response.getStatusCode(),
                    response.getHeaders(), response.getData());
        } catch (ApiException e) {
            logResponse(context, false, e.getCode(), e.getResponseHeaders(), e.getResponseBody());
        }
    }

    /**
     * Processes the received events and produces log events.
     * @param logEvents JSON array containing Azure events
     * @return the log events
     */
    protected static List<LogEntry> processEvents(JsonArray logEvents) {
        return StreamSupport.stream(logEvents.spliterator(), true)
            .filter(JsonElement::isJsonObject)
            .map(JsonElement::getAsJsonObject)
            .map(ADAPTER)
            .flatMap(List::stream)
            .collect(Collectors.toList());
    }

    /**
     * Logs a message with function name and invocation ID.
     * @param context execution context
     * @param level logging level
     * @param msgSupplier produces the message to log
     */
    private static void log(final ExecutionContext context, Level level,
            Supplier<String> msgSupplier) {
        context.getLogger().log(level, () -> String.format("[%s][%s] %s",
                context.getFunctionName(), context.getInvocationId(), msgSupplier.get()));
    }

    /**
     * Logs a response received from LogicMonitor.
     * @param context execution context
     * @param success if the request was successful
     * @param statusCode HTTP status code
     * @param headers HTTP headers
     * @param body response body
     */
    private static void logResponse(final ExecutionContext context, boolean success,
            int statusCode, Map<String, List<String>> headers, Object body) {
        log(context, success ? Level.INFO : Level.WARNING,
                () -> String.format("Received: status = %d, id = %s", statusCode, getRequestId(headers)));
        log(context, success ? Level.FINEST : Level.WARNING,
                () -> "Response body: " + body);
    }

    /**
     * Reads the request id from the headers.
     * @param headers map of header names and their values
     * @return the request id or null
     */
    protected static String getRequestId(Map<String, List<String>> headers) {
        return headers.keySet().stream()
            .filter(key -> LMLogsApi.REQUEST_ID_HEADER.equalsIgnoreCase(key))
            .findAny()
            .map(headers::get)
            .filter(values -> !values.isEmpty())
            .map(values -> values.get(0))
            .orElse(null);
    }

}
