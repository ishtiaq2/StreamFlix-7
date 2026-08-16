import os
import requests
import psycopg2
from fastapi import FastAPI, HTTPException

app = FastAPI(title="StreamFlix Catalog API")

DB_DSN = os.environ.get(
    "DATABASE_URL",
    "dbname=streamflix user=streamflix password=streamflixpass host=database"
)
CORE_ENGINE_URL = os.environ.get("CORE_ENGINE_URL", "http://core-engine:8981")


def db_connection():
    return psycopg2.connect(DB_DSN)


@app.get("/health")
def health():
    return {"status": "ok"}


@app.get("/titles")
def list_titles():
    with db_connection() as conn, conn.cursor() as cur:
        cur.execute("SELECT id, name, genre FROM titles ORDER BY id")
        rows = cur.fetchall()
    return [{"id": r[0], "name": r[1], "genre": r[2]} for r in rows]


@app.post("/play/{title_id}")
def play_title(title_id: int, user_id: int = 1):
    with db_connection() as conn, conn.cursor() as cur:
        cur.execute("SELECT name FROM titles WHERE id = %s", (title_id,))
        row = cur.fetchone()
        if not row:
            raise HTTPException(status_code=404, detail="Title not found")

        cur.execute(
            "INSERT INTO watch_history (user_id, title_id) VALUES (%s, %s)",
            (user_id, title_id),
        )
        conn.commit()

    stream_id = f"stream-{user_id}-{title_id}"

    # Report a normal playback-start event to the core engine --
    # the same REST pattern any external integration would use.
    try:
        requests.post(
            f"{CORE_ENGINE_URL}/events",
            json={
                "uei": "streamflix/playback/started",
                "streamId": stream_id,
                "message": f"Playback started for '{row[0]}'",
            },
            timeout=2,
        )
    except requests.RequestException:
        pass  # a monitoring hiccup shouldn't block playback itself

    return {"streamId": stream_id, "title": row[0], "status": "playing"}
