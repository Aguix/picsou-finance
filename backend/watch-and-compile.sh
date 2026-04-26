#!/bin/sh
# Dev: watch src/ for changes, recompile, let DevTools restart Spring Boot

# Initial compile + start Spring Boot in background
mvn compile -q && \
mvn spring-boot:run \
  -Dspring-boot.run.profiles=dev \
  -Dspring-boot.run.jvmArguments="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005" &

# Watch src/ for changes and recompile
while inotifywait -r -e modify,create,delete --format '%w%f' /app/src/ 2>/dev/null; do
  echo "Source change detected — recompiling..."
  if mvn compile -q 2>&1; then
    echo "Compile OK — DevTools will restart the app"
  else
    echo "Compile failed — fix errors and save again"
  fi
done
