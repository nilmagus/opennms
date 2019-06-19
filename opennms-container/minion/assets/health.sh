#!/usr/bin/env bash

# Exit script if a statement returns a non-true return value.
set -o errexit

# Use the error status of the first failure, rather than that of the last item in a pipeline.
set -o pipefail

MINION_HOME="/opt/minion"
HEALTH_TEST="Everything is awesome"

# Wrap health check for container health checks to 0 and 1
#
# Reference: https://docs.docker.com/engine/reference/builder/
# The command’s exit status indicates the health status of the container. The possible values are:
#
#   0: success - the container is healthy and ready for use
#   1: unhealthy - the container is not working correctly
#   2: reserved - do not use this exit code
if ${MINION_HOME}/bin/client health:check | grep "${HEALTH_TEST}"; then
  echo "Health check success."
  exit 0
else
  echo "Health check failed."
  exit 1
fi
