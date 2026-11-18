#!/usr/bin/env bash
set -e

echo "Compiling OBSI project..."
chmod +x mvnw
./mvnw clean package

echo "Compilation completed successfully."
