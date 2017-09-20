#!/bin/bash -e

source "$(dirname "$0")/common.sh"
cd "$APP_HOME"

if [[ -f "$APP_PID_FILE" ]]; then
  PID="`cat "$APP_PID_FILE"`"
  if [[ ! "$PID" =~ (^[0-9]+$) ]]; then
    eecho "PID file exists, but it does not contain a PID; removing $APP_PID_FILE"
  fi
  if ps -p "$PID" >/dev/null 2>&1; then
    eecho "$APP_NAME started already with PID $PID; aborting."
    exit 10
  else
    eecho "PID file exists, but its process ($PID) does not exist; removing $APP_PID_FILE"
  fi
fi

rm -f "$APP_PID_FILE" >/dev/null 2>&1

# Start the application up with jsvc
set +e
eecho "Starting up $APP_NAME .."
"$JSVC_BIN" \
  -java-home "$JAVA_HOME" \
  -pidfile "$APP_PID_FILE" \
  -wait 10 \
  -cwd "$APP_HOME" \
  -outfile "$APP_STDOUT_FILE" \
  -errfile "$APP_STDERR_FILE" \
  ${JAVA_OPTS} \
  -classpath "$APP_HOME/conf:$APP_LIB_DIR/ext/*:$APP_LIB_DIR/*" \
  "$APP_MAIN" \
  $APP_OPTS

JSVC_EXIT_CODE=$?

if [[ "$JSVC_EXIT_CODE" -ne 0 ]]; then
  eecho "Failed to start up $APP_NAME (exit code: $JSVC_EXIT_CODE)"
  exit $JSVC_EXIT_CODE
else
  eecho "Started up $APP_NAME successfully: $(cat "$APP_PID_FILE")"
  exit 0
fi

