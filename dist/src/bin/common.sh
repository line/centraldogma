# Common script for Java application
#
# This script checks if the following environment variables exist and sets their default values if necessary.
# -----------------------------------------------------------------------------------------------------------
# APP_HOME        (Optional) The base directory of the application.
#                 If not set, the parent directory of this script is used.
#
# APP_LIB_DIR     (Optional) The directory which contains the .JAR files
#                 If not set, '$APP_HOME/lib' is used.
#
# APP_LOG_DIR     (Optional) The directory where the log files are written out
#                 If not set, '$APP_HOME/log' is used.
#
# APP_MAIN        (Required) The fully qualified class name of the application
#
# APP_NAME        (Required) The name of the application
#
# APP_OPTS        (Optional) The application options
#
# APP_PID_FILE    (Optional) The path to the PID file which contains the ID of the application process.
#                 If not set, '$APP_HOME/$APP_NAME.pid' is used.
#
# APP_STDOUT_FILE (Optional) The path to the file that contains the stdout console output
#                 If not set, '$APP_LOG_DIR/$APP_NAME.stdout' is used.
#
# APP_STDERR_FILE (Optional) The path to the file that contains the stderr console output
#                 If not set, '$APP_LOG_DIR/$APP_NAME.stderr' is used.
#
# APP_WAIT_TIME   (Optional) Wait the specified seconds for the service to start or stop.
#                 If not set, 60 seconds are used.
#
# JAVA_HOME       (Required) The location of the installed JDK/JRE
#
# JAVA_OPTS       (Optional) The JVM options
#

set -e

function eecho() {
  echo "$@" >&2
}

if [[ -f "$(dirname "$0")/common.pre.sh" ]]; then
  source "$(dirname "$0")/common.pre.sh"
fi

if [[ -z "$APP_NAME" ]]; then
  eecho "Set APP_NAME environment variable first."
  exit 1
fi

if [[ ! "$APP_NAME" =~ (^[-_a-zA-Z0-9]+$) ]]; then
  eecho "Invalid APP_NAME: $APP_NAME"
  exit 2
fi

if [[ -z "$APP_MAIN" ]]; then
  eecho "Set APP_MAIN environment variable first."
  exit 3
fi

if [[ -z "$JAVA_HOME" ]]; then
  eecho "Set JAVA_HOME enviroment variable first."
  exit 4
fi

if [[ -z "$APP_HOME" ]]; then
  APP_BIN_DIR="$(cd "$(dirname "$0")" && pwd)"
  APP_HOME="$(cd "$APP_BIN_DIR/.." && pwd)"
else
  APP_HOME="$(cd "$APP_HOME" && pwd)"
  APP_BIN_DIR="$APP_HOME/bin"
fi

if [[ -z "$APP_LIB_DIR" ]]; then
  APP_LIB_DIR="$APP_HOME/lib"
fi

if [[ -z "$APP_LOG_DIR" ]]; then
  APP_LOG_DIR="$APP_HOME/log"
fi

if [[ -z "$APP_STDOUT_FILE" ]]; then
  APP_STDOUT_FILE="$APP_LOG_DIR/$APP_NAME".stdout
fi

if [[ -z "$APP_STDERR_FILE" ]]; then
  APP_STDERR_FILE="$APP_LOG_DIR/$APP_NAME".stderr
fi

if [[ -z "$APP_WAIT_TIME" ]]; then
  APP_WAIT_TIME=60
elif [[ ! "$APP_WAIT_TIME" =~ (^[0-9]+$) ]]; then
  eecho "Invalid APP_WAIT_TIME: $APP_WAIT_TIME"
  exit 5
fi

if [[ -z "$APP_PID_FILE" ]]; then
  APP_PID_FILE="$APP_HOME/$APP_NAME".pid
fi

if [[ -f "$(dirname "$0")/common.post.sh" ]]; then
  source "$(dirname "$0")/common.post.sh"
fi

export APP_BIN_DIR \
       APP_HOME \
       APP_LIB_DIR \
       APP_LOG_DIR \
       APP_MAIN \
       APP_NAME \
       APP_OPTS \
       APP_PID_FILE \
       APP_STDOUT_FILE \
       APP_STDERR_FILE \
       APP_WAIT_TIME \
       JAVA_HOME \
       JAVA_OPTS

eecho "Environment variables:"
eecho "======================"
eecho "APP_BIN_DIR:     $APP_BIN_DIR"
eecho "APP_HOME:        $APP_HOME"
eecho "APP_LIB_DIR:     $APP_LIB_DIR"
eecho "APP_LOG_DIR:     $APP_LOG_DIR"
eecho "APP_MAIN:        $APP_MAIN"
eecho "APP_NAME:        $APP_NAME"
eecho "APP_OPTS:        $APP_OPTS"
eecho "APP_PID_FILE:    $APP_PID_FILE"
eecho "APP_STDOUT_FILE: $APP_STDOUT_FILE"
eecho "APP_STDERR_FILE: $APP_STDERR_FILE"
eecho "APP_WAIT_TIME:   $APP_WAIT_TIME"
eecho "JAVA_HOME:       $JAVA_HOME"
eecho "JAVA_OPTS:       $JAVA_OPTS"
eecho
