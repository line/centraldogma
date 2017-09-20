#!/bin/bash -e

source "$(dirname "$0")/common.sh"
cd "$APP_HOME"

if [[ -f "$APP_PID_FILE" ]]; then
  PID="`cat "$APP_PID_FILE"`"
  if [[ "$PID" =~ (^[0-9]+$) ]]; then
    kill -0 "$PID" >/dev/null 2>&1
    if [[ $? -gt 0 ]]; then
      eecho "PID file exists, but its process ($PID) does not exist; removing $APP_PID_FILE"
      rm -f "$APP_PID_FILE" > /dev/null 2>&1
      exit 10
    fi
  else
    eecho "$APP_PID_FILE does not contain a PID."
    exit 10
  fi
else
  eecho "PID file does not exist; $APP_NAME not running?"
  exit 10
fi

# Shut the application down with JSVC
set +e
eecho "Shutting down $APP_NAME ($(cat "$APP_PID_FILE")) .."

"$JSVC_BIN" \
  -stop \
  -java-home "$JAVA_HOME" \
  -pidfile "$APP_PID_FILE" \
  -cwd "$APP_HOME" \
  -outfile "$APP_STDOUT_FILE" \
  -errfile "$APP_STDERR_FILE" \
  -classpath "$APP_HOME/conf:$APP_LIB_DIR/ext/*:$APP_LIB_DIR/*" \
  "$APP_MAIN"

JSVC_EXIT_CODE=$?

if [[ "$JSVC_EXIT_CODE" -ne 0 ]]; then
  eecho "Failed to shut down $APP_NAME (exit code: $JSVC_EXIT_CODE)"
  exit $JSVC_EXIT_CODE
else
  eecho "Shut down $APP_NAME successfully."
  exit 0
fi

