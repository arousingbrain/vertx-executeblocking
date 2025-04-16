# Vert.x Blocked Thread Demo

This application demonstrates how Vert.x handles blocked threads and how to monitor them. It consists of two verticles:
- `MainVerticle`: A non-blocking verticle that responds immediately
- `MainVerticleWithBlocking`: A verticle that simulates a blocking operation

## Setup

1. Ensure you have Java 11+ and Maven installed
2. Clone this repository
3. Run the application:
```bash
mvn clean compile exec:java
```

## Log Files

The application creates two log files:
- `log1.txt`: Logs from MainVerticle
- `log2.txt`: Logs from MainVerticleWithBlocking

## The Issue

The application demonstrates a common issue in Vert.x applications: blocking operations in event loop threads. Here's what happens:

1. `MainVerticleWithBlocking` receives a message
2. It simulates a blocking service call that takes X number of seconds to complete
3. During this time, the thread is blocked
4. Vert.x's `BlockedThreadChecker` detects this and logs warnings

## Configuration

The application is configured with:
- Blocked thread check interval: 2 second - Simulate how often BlockedThreadChecker logs blocked threads
- Maximum worker execute time: 2 seconds - Simulate timeout of caller/client.
- Blocking operation timeout: X seconds - Manually configured to demonstrate different times that will trigger the exception

## Monitoring

To monitor the logs in real-time:
```bash
# Monitor MainVerticle logs
tail -f log1.txt

# Monitor MainVerticleWithBlocking logs
tail -f log2.txt

# Monitor blocked thread warnings
tail -f log3.txt
```

## Stopping the Application

Press `Ctrl+C` to stop the application. The shutdown hook will properly close both Vert.x instances.

## Best Practices

This demo shows why you should:
1. Avoid blocking operations in event loop threads
2. Use `executeBlocking` for operations that might block
3. Monitor blocked thread warnings in production
4. Set appropriate timeouts for blocking operations 