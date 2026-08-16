"""
A small Chaos-Monkey-style agent: checks catalog-api's health over HTTP,
and (optionally) uses SSH to kill a process inside a target container to
simulate a real outage -- then reports the resulting event to core-engine,
the same way any monitoring integration would.

Configuration is read from environment variables (see .env / Dockerfile):
    CORE_ENGINE_URL     -- base URL of core-engine's REST API
    CATALOG_HEALTH_URL  -- health-check URL for catalog-api
    SSH_HOST/PORT/USER/PASSWORD -- only used by the `break` SSH path, which
                                   is documented but not wired up by default
                                   (catalog-api has no SSH daemon out of the
                                   box -- see the step-by-step guide, Part 8).
"""
import os
import sys

import requests
import paramiko

CORE_ENGINE_URL = os.environ.get("CORE_ENGINE_URL", "http://core-engine:8981")
CATALOG_HEALTH_URL = os.environ.get("CATALOG_HEALTH_URL", "http://catalog-api:8000/health")

SSH_HOST = os.environ.get("SSH_HOST", "catalog-api")
SSH_PORT = int(os.environ.get("SSH_PORT", "22"))
SSH_USER = os.environ.get("SSH_USER", "root")
SSH_PASSWORD = os.environ.get("SSH_PASSWORD", "chaospass")


def check_health() -> bool:
    try:
        resp = requests.get(CATALOG_HEALTH_URL, timeout=2)
        return resp.status_code == 200
    except requests.RequestException:
        return False


def report_event(uei: str, stream_id: str, message: str) -> None:
    requests.post(
        f"{CORE_ENGINE_URL}/events",
        json={"uei": uei, "streamId": stream_id, "message": message},
        timeout=2,
    )
    print(f"Reported: {uei} ({message})")


def simulate_outage_via_ssh(host: str, port: int, user: str, password: str) -> None:
    """
    Connects over SSH and kills the target process -- a real chaos-engineering
    action, not just an HTTP health check. Requires an SSH server running in
    the target container, which is intentionally NOT included in catalog-api's
    Dockerfile (see the step-by-step guide, Part 8, Step 4, for why). Use
    `podman exec catalog-api pkill -f uvicorn` instead for the demo in this repo.
    """
    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    client.connect(host, port=port, username=user, password=password, timeout=5)
    client.exec_command("pkill -f uvicorn")
    client.close()


def run_chaos_check(stream_id: str = "stream-chaos-test") -> None:
    healthy = check_health()
    if healthy:
        report_event(
            "streamflix/chaos/healthcheckPassed", stream_id,
            "catalog-api responded normally"
        )
    else:
        report_event(
            "streamflix/chaos/healthcheckFailed", stream_id,
            "catalog-api did not respond to health check"
        )


if __name__ == "__main__":
    mode = sys.argv[1] if len(sys.argv) > 1 else "check"

    if mode == "check":
        run_chaos_check()
    elif mode == "break":
        # Deliberately induce a failure via SSH -- only works if you've
        # added an SSH daemon to catalog-api yourself.
        simulate_outage_via_ssh(SSH_HOST, SSH_PORT, SSH_USER, SSH_PASSWORD)
        report_event(
            "streamflix/chaos/inducedFailure", "stream-chaos-test",
            "chaos-agent deliberately killed catalog-api"
        )
    else:
        print("usage: python chaos.py [check|break]")
