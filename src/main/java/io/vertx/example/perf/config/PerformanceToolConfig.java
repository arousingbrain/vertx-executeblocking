package io.vertx.example.perf.config;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Configuration constants for the Performance Tool.
 * Centralizes all configuration parameters to make maintenance easier.
 */
public class PerformanceToolConfig {
    // Test constraints
    public static final int MAX_THREADS = 20;          // Maximum number of concurrent threads/users allowed
    public static final int MAX_LOOP_COUNT = 10000;    // Maximum iterations per thread
    public static final int MAX_RAMP_UP = 3600;        // Maximum ramp-up period in seconds (1 hour)
    public static final long MAX_TEST_DURATION_MS = 300000; // Maximum test duration (5 minutes)
    
    // Thread pool configuration
    public static final int CORE_POOL_SIZE = 4;        // Initial number of threads in pool
    public static final int MAX_POOL_SIZE = 20;        // Maximum number of threads pool can grow to
    public static final long KEEP_ALIVE_TIME = 60000;  // Time in ms before idle threads are removed
    public static final int QUEUE_CAPACITY = 100;      // Size of the task queue
    
    // HTTP Client configuration
    public static final int HTTP_CONNECT_TIMEOUT = 10;  // Connection timeout in seconds
    public static final int HTTP_READ_TIMEOUT = 60;     // Read timeout in seconds
    public static final int HTTP_WRITE_TIMEOUT = 60;    // Write timeout in seconds
    public static final int HTTP_CONNECTION_POOL_SIZE = 50; // Max connections in pool
    
    // Application configuration
    public static final int SERVER_PORT = 8090;        // Port for the HTTP server
    public static final int THREAD_MONITOR_INTERVAL = 5000; // Thread pool monitoring interval in ms
    public static final int THREAD_QUEUE_WARNING_THRESHOLD = 50; // Queue size threshold for warnings
    
    // Headers that won't be passed through to target service
    public static final Set<String> RESERVED_HEADERS = new HashSet<>(Arrays.asList(
        "threads", "rampUpPeriod", "loopCount", "targetUrl"
    ));
    
    // Default values for parameters
    public static final int DEFAULT_THREADS = 1;
    public static final int DEFAULT_RAMP_UP = 0;
    public static final int DEFAULT_LOOP_COUNT = 1;
} 