#!/bin/bash

# Exit immediately if a command exits with a non-zero status
set -e

echo "=========================================================="
echo "    GhostNode Production Validation & Chaos Test"
echo "=========================================================="

# Port Cleanup
echo "Cleaning up any processes running on ports 8080, 8081, 8082, 8474..."
for port in 8080 8081 8082 8474; do
  pid=$(lsof -t -i:$port || true)
  if [ -n "$pid" ]; then
    echo "Killing process $pid on port $port"
    kill -9 $pid || true
  fi
done

# Start Toxiproxy Server
echo "Starting Toxiproxy Server..."
ghostnode-consumer-app/bin/toxiproxy-server > toxiproxy.log 2>&1 &
TOXIPROXY_PID=$!

# Wait for Toxiproxy to be ready
echo "Waiting for Toxiproxy to start on port 8474..."
for i in {1..10}; do
  if curl -s http://localhost:8474/version > /dev/null; then
    echo "Toxiproxy is ready!"
    break
  fi
  if [ $i -eq 10 ]; then
    echo "Error: Toxiproxy failed to start."
    kill $TOXIPROXY_PID || true
    exit 1
  fi
  sleep 1
done

# Register Proxy in Toxiproxy
echo "Registering event-bus-proxy (127.0.0.1:8082 -> 127.0.0.1:8081)..."
curl -s -X POST http://localhost:8474/proxies \
  -H "Content-Type: application/json" \
  -d '{"name": "event-bus-proxy", "listen": "127.0.0.1:8082", "upstream": "127.0.0.1:8081", "enabled": true}' > /dev/null

# Start the Spring Boot Consumer Application
echo "Starting Spring Boot Consumer Application..."
cd ghostnode-consumer-app
./gradlew bootRun > ../consumer-app.log 2>&1 &
APP_PID=$!
cd ..

# Wait for Consumer Application to be ready
echo "Waiting for Spring Boot App to start on port 8080..."
for i in {1..30}; do
  if curl -s http://localhost:8080/actuator/health > /dev/null; then
    echo "Consumer Application is ready!"
    break
  fi
  if [ $i -eq 30 ]; then
    echo "Error: Consumer Application failed to start."
    echo "---- Last 30 lines of consumer-app.log ----"
    tail -n 30 consumer-app.log || true
    kill $APP_PID || true
    kill $TOXIPROXY_PID || true
    exit 1
  fi
  sleep 2
done

# Run the automated Chaos & Convergence Integrity Check
echo "Running Chaos & Convergence Integrity Check..."
set +e
RESULT=$(curl -s -X POST http://localhost:8080/chaos/verify)
set -e

echo ""
echo "----------------------------------------------------------"
echo "Result from endpoint:"
echo "$RESULT"
echo "----------------------------------------------------------"
echo ""

# Cleanup background processes
echo "Cleaning up background processes..."
kill $APP_PID || true
kill $TOXIPROXY_PID || true

# Check if the result passed
if [[ "$RESULT" == *"CONVERGENCE INTEGRITY CHECK: PASSED"* ]]; then
  echo "✅ SUCCESS: GhostNode successfully validated under simulated network partition."
  exit 0
else
  echo "❌ FAILURE: Chaos test did not pass."
  exit 1
fi
