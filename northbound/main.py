import os
import requests
import psycopg2
from fastapi import FastAPI

app = FastAPI(title="StreamFlix Northbound Interface")

DB_DSN = os.environ.get(
    "DATABASE_URL",
    "dbname=streamflix user=streamflix password=streamflixpass host=database"
)
CORE_ENGINE_URL = os.environ.get("CORE_ENGINE_URL", "http://core-engine:8981")


@app.get("/status")
def status():
    """A human/dashboard-friendly summary -- what an ops dashboard or status page would poll."""
    alarms = requests.get(f"{CORE_ENGINE_URL}/alarms", timeout=3).json()
    return {
        "service": "StreamFlix",
        "active_alarms": len(alarms),
        "alarms": alarms,
    }


@app.get("/metrics")
def metrics():
    """A Prometheus-style plaintext metrics endpoint -- what a real monitoring
    system (Grafana/Prometheus, or a partner's own tooling) would scrape."""
    alarms = requests.get(f"{CORE_ENGINE_URL}/alarms", timeout=3).json()

    with psycopg2.connect(DB_DSN) as conn, conn.cursor() as cur:
        cur.execute("SELECT count(*) FROM watch_history")
        total_plays = cur.fetchone()[0]

    lines = [
        f"streamflix_active_alarms {len(alarms)}",
        f"streamflix_total_plays {total_plays}",
    ]
    return "\n".join(lines)
