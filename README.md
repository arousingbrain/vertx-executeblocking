# Performance Testing Applications

This project contains two separate applications:
1. PerfApp - A load testing application
2. DummyServer - A simple HTTP server for testing

## Project Structure
```
.
├── src/main/java/io/vertx/example/
│   ├── PerfApp.java       # Load testing application
│   ├── DummyServer.java   # Test HTTP server
│   └── body.json         # Test payload for PerfApp
├── pom.xml
└── README.md
```

## Building the Applications

Build both applications as fat JARs:

```bash
mvn clean package
```

This creates two JAR files in the `target` directory:
- `target/perfapp.jar` - The load testing application
- `target/dummyserver.jar` - The dummy HTTP server

## Running the Applications

### Running the Dummy Server

```bash
java -jar target/dummyserver.jar
```

The server will start on port 8123.

### Running the Load Test Application

1. First, ensure the `body.json` file is in the same directory as the JAR file:
```bash
cp src/main/java/io/vertx/example/body.json target/
```

2. Then run the load test:
```bash
cd target
java -jar perfapp.jar
```

## Configuration

- DummyServer listens on port 8123 by default
- PerfApp is configured to send requests to `http://localhost:8123/`
- The load test parameters can be modified in the `PerfApp.java` source code:
  - THREADS: Number of concurrent threads (default: 10)
  - RAMP_UP_PERIOD: Seconds to ramp up threads (default: 5)
  - LOOP_COUNT: Number of requests per thread (default: 100)

## Requirements

- Java 11 or higher
- Maven 3.6 or higher 