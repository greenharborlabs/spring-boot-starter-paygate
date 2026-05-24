#!/usr/bin/env bash

require_docker_daemon() {
  if ! command -v docker > /dev/null 2>&1; then
    echo "ERROR: docker is required but was not found on PATH."
    echo "       Install Docker Desktop or Docker Engine, then retry."
    exit 1
  fi

  if ! docker compose version > /dev/null 2>&1; then
    echo "ERROR: Docker Compose v2 is required but 'docker compose' is unavailable."
    echo "       Install or update Docker Desktop / Docker Engine, then retry."
    exit 1
  fi

  if ! docker info > /dev/null 2>&1; then
    echo "ERROR: Docker daemon is not reachable."
    echo "       Start Docker Desktop or Docker Engine, wait until it is running, then retry."
    echo "       Current DOCKER_HOST: ${DOCKER_HOST:-default Docker socket}"
    exit 1
  fi
}
