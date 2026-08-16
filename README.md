# StreamFlix-7

# StreamFlix: A Real-Life 7-Container Streaming Service Architecture
### Built with Podman — from your whiteboard notes to a working stack

This guide takes your 7-container list and builds it as a genuine (if intentionally
small) streaming service, so every concept lands as something concrete rather than
abstract. Everything here is runnable with Podman.

**Every container maps directly to your notes:**

| # | Your note | What it becomes here |
|---|---|---|
| 1 | Embedded Jetty with Jersey (EventD, Alarmd) | `core-engine` — Java service tracking playback events and turning them into alarms |
| 2 | FastAPI | `catalog-api` — Python service for browsing content and starting playback |
| 3 | Load Balancer | `edge-lb` — nginx, the single front door for all customer traffic |
| 4 | PostgreSQL | `database` — users, subscriptions, watch history, alarms |
| 5 | Python Script → HTTP and SSH Service | `chaos-agent` — a Netflix-Chaos-Monkey-style resilience tester |
| 6 | Web Interface | `web-ui` — the customer-facing site |
| 7 | Northbound Interface | `northbound` — a status/metrics API for external dashboards |

---

## Table of Contents

1. [The Real-Life Story](#1-the-real-life-story)
2. [Full Architecture](#2-full-architecture)
3. [Repository Layout](#3-repository-layout)
4. [Container 4 — PostgreSQL (build this first)](#4-container-4--postgresql-build-this-first)
5. [Container 1 — core-engine (Embedded Jetty + Jersey)](#5-container-1--core-engine-embedded-jetty--jersey)
6. [Container 2 — catalog-api (FastAPI)](#6-container-2--catalog-api-fastapi)
7. [Container 6 — web-ui](#7-container-6--web-ui)
8. [Container 3 — edge-lb (Load Balancer)](#8-container-3--edge-lb-load-balancer)
9. [Container 5 — chaos-agent (HTTP + SSH)](#9-container-5--chaos-agent-http--ssh)
10. [Container 7 — northbound](#10-container-7--northbound)
11. [Wiring It Together: podman-compose.yml](#11-wiring-it-together-podman-composeyml)
12. [Running the Whole Thing](#12-running-the-whole-thing)
13. [Walkthrough: A Customer Watches a Show, Then Something Breaks](#13-walkthrough-a-customer-watches-a-show-then-something-breaks)
14. [What Makes This "Real Life" and What's Still Simplified](#14-what-makes-this-real-life-and-whats-still-simplified)
15. [Troubleshooting & Security](#15-troubleshooting--security)

---

## 1. The Real-Life Story

Real streaming platforms — Netflix chief among them — are built from exactly this shape
of system: an edge layer that absorbs traffic, a set of independently deployable
services behind it, a database of record, an events/alarms pipeline watching for
trouble, automated resilience testing that deliberately breaks things in production
(Netflix's own **Chaos Monkey**, part of their "Simian Army" tooling, is the direct
namesake for container #5 here), and a northbound feed so humans and dashboards can see
system health without digging through logs.

We're building a deliberately small version of that shape — seven containers instead of
thousands of microservices — but the *relationships* between the pieces are the same
ones a real platform has.

---

## 2. Full Architecture

```
                              customer's browser
                                     │
                                     ▼
                        ┌──────────────────────────┐
                        │   web-ui (nginx+static)  │   :8090
                        └────────────┬─────────────┘
                                     │ API calls
                                     ▼
                        ┌──────────────────────────┐
                        │  edge-lb (nginx)         │   :8080
                        │  load balances catalog-  │
                        │  api instances           │
                        └────────────┬─────────────┘
                                     │
                       ┌─────────────┴─────────────┐
                       ▼                           ▼
            ┌────────────────────┐     ┌───────────────────┐
            │ catalog-api (x2)   │     │ catalog-api (x2)  │
            │ FastAPI            │     │ FastAPI           │
            └──────────┬─────────┘     └─────────────┬─────┘
                       │  playback events (HTTP)     │
                       ▼                             ▼
                     ┌────────────────────────────────────┐
                     │  core-engine                       │  :8981
                     │  Jetty + Jersey                    │
                     │  EventResource / Alarm             │
                     │  Resource (reduction logic)        │
                     └───────────────┬────────────────────┘
                                     │ JDBC
                                     ▼
                        ┌──────────────────────────┐
                        │  database (PostgreSQL)   │  :5432
                        │  users, subs, watch      │
                        │  history, events, alarms │
                        └────────────┬─────────────┘
                                     ▲
                       ┌─────────────┴──────────────┐
                       │                            │
            ┌───────────────────────┐     ┌────────────────────────┐
            │ chaos-agent           │     │ northbound             │
            │ Python: HTTP+SSH      │     │ FastAPI: /status,      │
            │ breaks things on      │     │ /metrics → external    │
            │ purpose               │     │ dashboards             │
            └───────────────────────┘     └────────────────────────┘
```

All seven containers share one Podman network (`streamflix-net`), reaching each other by
service name, exactly like the OpenNMS stack we built earlier.

---

## 3. Repository Layout

```
streamflix-stack/
├── podman-compose.yml
├── database/
│   └── init.sql
├── core-engine/
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/main/java/com/streamflix/core/
│       ├── Main.java
│       ├── EventResource.java
│       ├── AlarmResource.java
│       └── AlarmStore.java
├── catalog-api/
│   ├── Dockerfile
│   ├── requirements.txt
│   └── main.py
├── web-ui/
│   ├── Dockerfile
│   └── index.html
├── edge-lb/
│   ├── Dockerfile
│   └── nginx.conf
├── chaos-agent/
│   ├── Dockerfile
│   ├── requirements.txt
│   └── chaos.py
└── northbound/
    ├── Dockerfile
    ├── requirements.txt
    └── main.py
```

---

## 4. Container 4 — PostgreSQL (build this first)

Everything else depends on this schema existing, so define it before writing any service
code.

`database/init.sql`:
```sql
CREATE TABLE users (
    id SERIAL PRIMARY KEY,
    email TEXT UNIQUE NOT NULL,
    subscription_tier TEXT NOT NULL DEFAULT 'basic'
);

CREATE TABLE titles (
    id SERIAL PRIMARY KEY,
    name TEXT NOT NULL,
    genre TEXT NOT NULL
);

CREATE TABLE watch_history (
    id SERIAL PRIMARY KEY,
    user_id INTEGER REFERENCES users(id),
    title_id INTEGER REFERENCES titles(id),
    started_at TIMESTAMP DEFAULT now()
);

CREATE TABLE events (
    id SERIAL PRIMARY KEY,
    uei TEXT NOT NULL,
    stream_id TEXT NOT NULL,
    message TEXT,
    received_at TIMESTAMP DEFAULT now()
);

CREATE TABLE alarms (
    reduction_key TEXT PRIMARY KEY,
    uei TEXT NOT NULL,
    stream_id TEXT NOT NULL,
    severity TEXT NOT NULL,
    occurrence_count INTEGER NOT NULL DEFAULT 1,
    first_seen TIMESTAMP DEFAULT now(),
    last_seen TIMESTAMP DEFAULT now(),
    cleared BOOLEAN DEFAULT false
);

-- Seed data so the catalog isn't empty on first run
INSERT INTO users (email, subscription_tier) VALUES
    ('ada@example.com', 'premium'),
    ('grace@example.com', 'basic');

INSERT INTO titles (name, genre) VALUES
    ('Nebula Drift', 'sci-fi'),
    ('The Long Harbor', 'drama'),
    ('Kitchen Rivals', 'reality');
```

This mounts into the Postgres container's auto-init directory — no separate Dockerfile
needed, the official `postgres` image runs any `.sql` file it finds under
`/docker-entrypoint-initdb.d/` on first boot.

---

## 5. Container 1 — core-engine (Embedded Jetty + Jersey)

This is the capstone of everything we covered earlier in this conversation: a real,
standalone embedded Jetty server running a Jersey-powered REST API, doing the same
reduction-key alarm logic OpenNMS's `eventconf.xml` does — just implemented directly in
Java this time instead of configured via XML.

`core-engine/pom.xml`:
```xml
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.streamflix</groupId>
  <artifactId>core-engine</artifactId>
  <version>1.0.0</version>
  <packaging>jar</packaging>

  <properties>
    <maven.compiler.source>17</maven.compiler.source>
    <maven.compiler.target>17</maven.compiler.target>
    <jersey.version>3.1.5</jersey.version>
    <jetty.version>11.0.20</jetty.version>
  </properties>

  <dependencies>
    <dependency>
      <groupId>org.eclipse.jetty</groupId>
      <artifactId>jetty-server</artifactId>
      <version>${jetty.version}</version>
    </dependency>
    <dependency>
      <groupId>org.eclipse.jetty</groupId>
      <artifactId>jetty-servlet</artifactId>
      <version>${jetty.version}</version>
    </dependency>
    <dependency>
      <groupId>org.glassfish.jersey.containers</groupId>
      <artifactId>jersey-container-servlet</artifactId>
      <version>${jersey.version}</version>
    </dependency>
    <dependency>
      <groupId>org.glassfish.jersey.media</groupId>
      <artifactId>jersey-media-json-jackson</artifactId>
      <version>${jersey.version}</version>
    </dependency>
    <dependency>
      <groupId>org.glassfish.jersey.inject</groupId>
      <artifactId>jersey-hk2</artifactId>
      <version>${jersey.version}</version>
    </dependency>
    <dependency>
      <groupId>org.postgresql</groupId>
      <artifactId>postgresql</artifactId>
      <version>42.7.3</version>
    </dependency>
  </dependencies>

  <build>
    <finalName>core-engine</finalName>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-shade-plugin</artifactId>
        <version>3.5.1</version>
        <executions>
          <execution>
            <phase>package</phase>
            <goals><goal>shade</goal></goals>
            <configuration>
              <transformers>
                <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                  <mainClass>com.streamflix.core.Main</mainClass>
                </transformer>
              </transformers>
            </configuration>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
```

`core-engine/src/main/java/com/streamflix/core/Main.java` — this is the "embedded Jetty"
part from Section 1 of our very first conversation, made concrete:
```java
package com.streamflix.core;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.glassfish.jersey.jackson.JacksonFeature;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.servlet.ServletContainer;

public class Main {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8981"));

        ResourceConfig config = new ResourceConfig();
        config.register(EventResource.class);
        config.register(AlarmResource.class);
        config.register(JacksonFeature.class);

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");
        context.addServlet(new ServletHolder(new ServletContainer(config)), "/*");

        Server server = new Server(port);
        server.setHandler(context);

        System.out.println("core-engine (Jetty+Jersey) listening on :" + port);
        server.start();
        server.join();
    }
}
```

`core-engine/src/main/java/com/streamflix/core/AlarmStore.java` — the reduction-key logic
from the OpenNMS `alarm-data` element, reimplemented directly:
```java
package com.streamflix.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class AlarmStore {
    public static final AlarmStore INSTANCE = new AlarmStore();

    // key = reduction key (uei + streamId), same concept as OpenNMS's reduction-key
    private final Map<String, Alarm> alarms = new ConcurrentHashMap<>();

    public void recordEvent(String uei, String streamId, String message) {
        String reductionKey = uei + ":" + streamId;

        alarms.compute(reductionKey, (key, existing) -> {
            if (uei.endsWith("Recovered")) {
                // "recovered" events clear the matching "down" alarm,
                // same pairing mechanic as clear-key in eventconf.xml
                if (existing != null) {
                    existing.cleared = true;
                }
                return existing;
            }
            if (existing == null || existing.cleared) {
                return new Alarm(reductionKey, uei, streamId, message);
            }
            existing.occurrenceCount++;
            existing.lastMessage = message;
            return existing;
        });
    }

    public Map<String, Alarm> getActiveAlarms() {
        Map<String, Alarm> active = new ConcurrentHashMap<>();
        alarms.forEach((k, v) -> { if (!v.cleared) active.put(k, v); });
        return active;
    }

    public static class Alarm {
        public String reductionKey;
        public String uei;
        public String streamId;
        public String lastMessage;
        public int occurrenceCount = 1;
        public boolean cleared = false;

        public Alarm(String reductionKey, String uei, String streamId, String message) {
            this.reductionKey = reductionKey;
            this.uei = uei;
            this.streamId = streamId;
            this.lastMessage = message;
        }
    }
}
```

`core-engine/src/main/java/com/streamflix/core/EventResource.java` — this is our Jersey
`@Path` resource, exactly the pattern from the very start of this conversation:
```java
package com.streamflix.core;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;
import java.util.Map;

@Path("/events")
public class EventResource {

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public String receiveEvent(Map<String, String> event) {
        String uei = event.get("uei");
        String streamId = event.get("streamId");
        String message = event.getOrDefault("message", "");

        AlarmStore.INSTANCE.recordEvent(uei, streamId, message);
        return "{\"status\":\"accepted\"}";
    }
}
```

`core-engine/src/main/java/com/streamflix/core/AlarmResource.java`:
```java
package com.streamflix.core;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.Map;

@Path("/alarms")
public class AlarmResource {

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, AlarmStore.Alarm> getAlarms() {
        return AlarmStore.INSTANCE.getActiveAlarms();
    }
}
```

`core-engine/Dockerfile` — a multi-stage build, so the final image doesn't carry the
whole Maven toolchain:
```dockerfile
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /build/target/core-engine.jar app.jar
EXPOSE 8981
CMD ["java", "-jar", "app.jar"]
```

Note the parallel to the original OpenNMS tutorial: `EventResource` here is exactly the
role of OpenNMS's `send-event.pl`/`/rest/events` endpoint, and `AlarmStore`'s
reduction-key logic is a hand-rolled version of what `<alarm-data reduction-key="...">`
does declaratively in `eventconf.xml`.

---

## 6. Container 2 — catalog-api (FastAPI)

This is the customer-facing API — browsing titles, starting playback — and it reports
playback events to `core-engine` over plain HTTP, the same way our earlier Python
container reported events to OpenNMS's REST API.

`catalog-api/requirements.txt`:
```
fastapi==0.115.0
uvicorn==0.30.6
psycopg2-binary==2.9.9
requests==2.32.3
```

`catalog-api/main.py`:
```python
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
    # same pattern as our earlier OpenNMS REST integration.
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
```

`catalog-api/Dockerfile`:
```dockerfile
FROM python:3.12-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY main.py .
EXPOSE 8000
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
```

---

## 7. Container 6 — web-ui

A minimal but real customer-facing page — plain HTML/JS is enough to demonstrate the
full request path without a heavy frontend build toolchain.

`web-ui/index.html`:
```html
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <title>StreamFlix</title>
  <style>
    body { font-family: sans-serif; background: #141414; color: #fff; padding: 2rem; }
    .title-card { display: inline-block; background: #222; padding: 1rem; margin: 0.5rem;
                  border-radius: 8px; cursor: pointer; width: 180px; }
    .title-card:hover { background: #333; }
    #status { margin-top: 1rem; color: #46d369; }
  </style>
</head>
<body>
  <h1>StreamFlix</h1>
  <div id="titles">Loading catalog...</div>
  <div id="status"></div>

  <script>
    // The web UI talks to edge-lb, never to catalog-api directly --
    // same "one front door" principle as the load balancer in our earlier stacks.
    const API_BASE = "/api";

    fetch(`${API_BASE}/titles`)
      .then(res => res.json())
      .then(titles => {
        document.getElementById("titles").innerHTML = titles.map(t => `
          <div class="title-card" onclick="play(${t.id}, '${t.name}')">
            <strong>${t.name}</strong><br><small>${t.genre}</small>
          </div>
        `).join("");
      });

    function play(id, name) {
      fetch(`${API_BASE}/play/${id}`, { method: "POST" })
        .then(res => res.json())
        .then(data => {
          document.getElementById("status").innerText =
            `Now playing: ${data.title} (stream ${data.streamId})`;
        });
    }
  </script>
</body>
</html>
```

`web-ui/Dockerfile`:
```dockerfile
FROM docker.io/library/nginx:alpine
COPY index.html /usr/share/nginx/html/index.html
EXPOSE 80
```

---

## 8. Container 3 — edge-lb (Load Balancer)

nginx sitting in front of multiple `catalog-api` replicas, plus reverse-proxying the
static web UI. This is the "one front door" from our Jetty-vs-plain-web-server
discussion, made real: nginx handles routing and load distribution, and doesn't know or
care that FastAPI is running Python behind it.

`edge-lb/nginx.conf`:
```nginx
events {}

http {
    upstream catalog_backend {
        # podman-compose can scale catalog-api to multiple replicas;
        # nginx round-robins across whatever resolves under this name
        server catalog-api:8000;
    }

    server {
        listen 8080;

        location /api/ {
            proxy_pass http://catalog_backend/;
            proxy_set_header Host $host;
        }

        location / {
            proxy_pass http://web-ui:80/;
        }
    }
}
```

`edge-lb/Dockerfile`:
```dockerfile
FROM docker.io/library/nginx:alpine
COPY nginx.conf /etc/nginx/nginx.conf
EXPOSE 8080
```

---

## 9. Container 5 — chaos-agent (HTTP + SSH)

Your note specifically called out **"HTTP and SSH Service"** — this container uses both
protocols, matching real chaos-engineering tools: HTTP to check health, SSH to actually
reach into a host/container and cause a controlled failure.

`chaos-agent/requirements.txt`:
```
requests==2.32.3
paramiko==3.4.0
```

`chaos-agent/chaos.py`:
```python
"""
A small Chaos-Monkey-style agent: checks catalog-api's health over HTTP,
and (optionally) uses SSH to kill a process inside a target container to
simulate a real outage -- then reports the resulting event to core-engine,
the same way any monitoring integration would.
"""
import random
import sys
import time

import requests
import paramiko

CORE_ENGINE_URL = "http://core-engine:8981"
CATALOG_HEALTH_URL = "http://catalog-api:8000/health"


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
    the target container (see the guide's note on this).
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
        # Deliberately induce a failure via SSH, matching the "SSH Service" in your notes
        simulate_outage_via_ssh(
            host="catalog-api", port=22, user="root", password="chaospass"
        )
        report_event(
            "streamflix/chaos/inducedFailure", "stream-chaos-test",
            "chaos-agent deliberately killed catalog-api"
        )
    else:
        print("usage: python chaos.py [check|break]")
```

`chaos-agent/Dockerfile`:
```dockerfile
FROM python:3.12-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY chaos.py .
CMD ["sleep", "infinity"]
```

> **A practical note on the SSH half:** for `simulate_outage_via_ssh()` to actually work,
> the *target* container (`catalog-api`) needs an SSH server running inside it, which the
> minimal Dockerfile in Section 6 doesn't include on purpose — running SSH inside every
> application container is a real production anti-pattern (it widens the attack surface
> and duplicates what the container orchestrator's exec functionality already gives you).
> For this tutorial, treat `simulate_outage_via_ssh()` as a **documented capability** and
> prefer the safer, equally realistic alternative below for actually running the chaos
> demo:
> ```bash
> podman exec catalog-api pkill -f uvicorn
> ```
> This achieves the identical "kill the process from outside the container" effect
> your notes call for, using Podman's own remote-exec mechanism instead of maintaining a
> separate SSH daemon per container — worth knowing as the realistic trade-off real chaos
> tooling (including Netflix's own) has moved towards as well, favoring orchestrator APIs
> over SSH fleets where the platform provides one.

---

## 10. Container 7 — northbound

Your note said **"Northbound Interface"** — in networking and systems architecture,
*northbound* means an interface exposed **upward**, to something that monitors or
manages this system (a dashboard, an NOC, a partner integration), as opposed to
*southbound*, which would mean this system talking down to the things it manages. This
container is exactly that: a read-only status/metrics feed for external consumers.

`northbound/requirements.txt`:
```
fastapi==0.115.0
uvicorn==0.30.6
psycopg2-binary==2.9.9
requests==2.32.3
```

`northbound/main.py`:
```python
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
```

`northbound/Dockerfile`:
```dockerfile
FROM python:3.12-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY main.py .
EXPOSE 9000
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "9000"]
```

---

## 11. Wiring It Together: podman-compose.yml

```yaml
version: "3.8"

networks:
  streamflix-net:
    driver: bridge

volumes:
  psql-data:

services:
  database:
    image: docker.io/library/postgres:16
    container_name: database
    environment:
      POSTGRES_DB: streamflix
      POSTGRES_USER: streamflix
      POSTGRES_PASSWORD: streamflixpass
    volumes:
      - psql-data:/var/lib/postgresql/data
      - ./database/init.sql:/docker-entrypoint-initdb.d/init.sql
    networks: [streamflix-net]
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U streamflix"]
      interval: 10s
      timeout: 5s
      retries: 5
    ports:
      - "5432:5432"

  core-engine:
    build: ./core-engine
    container_name: core-engine
    depends_on:
      database:
        condition: service_healthy
    networks: [streamflix-net]
    ports:
      - "8981:8981"

  catalog-api:
    build: ./catalog-api
    container_name: catalog-api
    depends_on:
      database:
        condition: service_healthy
      core-engine:
        condition: service_started
    networks: [streamflix-net]

  web-ui:
    build: ./web-ui
    container_name: web-ui
    networks: [streamflix-net]

  edge-lb:
    build: ./edge-lb
    container_name: edge-lb
    depends_on: [catalog-api, web-ui]
    networks: [streamflix-net]
    ports:
      - "8080:8080"

  chaos-agent:
    build: ./chaos-agent
    container_name: chaos-agent
    depends_on: [catalog-api, core-engine]
    networks: [streamflix-net]

  northbound:
    build: ./northbound
    container_name: northbound
    depends_on: [core-engine, database]
    networks: [streamflix-net]
    ports:
      - "9000:9000"
```

Seven services, one shared network, matching the architecture diagram in Section 2
exactly.

---

## 12. Running the Whole Thing

```bash
cd streamflix-stack
podman-compose up -d --build
podman-compose logs -f core-engine catalog-api
```

Give Postgres and core-engine a few seconds to become healthy, then:

```bash
# The customer-facing site, through the load balancer
curl -I http://localhost:8080/

# The catalog, through the load balancer's /api/ path
curl http://localhost:8080/api/titles

# The core engine directly (normally only other containers would call this)
curl http://localhost:8981/alarms

# The northbound status feed, as an external dashboard would poll it
curl http://localhost:9000/status
curl http://localhost:9000/metrics
```

Or just open `http://localhost:8080` in a browser — that's `web-ui`, reverse-proxied
through `edge-lb`, calling `catalog-api`.

---

## 13. Walkthrough: A Customer Watches a Show, Then Something Breaks

This is the full, real request path, end to end:

1. **Browser → `web-ui`** — you open `http://localhost:8080`, nginx (`edge-lb`) proxies
   `/` to `web-ui`, which serves the HTML/JS page.
2. **`web-ui` → `edge-lb` → `catalog-api`** — the page's JS calls `/api/titles`;
   `edge-lb` proxies that to `catalog-api`, which queries Postgres and returns the
   catalog.
3. **Click a title → `catalog-api` → `core-engine`** — `POST /api/play/1` inserts a
   `watch_history` row, then reports a `streamflix/playback/started` event to
   `core-engine` over HTTP — exactly the REST pattern from our OpenNMS work, just talking
   to our own service instead.
4. **Something breaks** — run:
   ```bash
   podman exec chaos-agent python chaos.py check
   podman exec catalog-api pkill -f uvicorn   # or chaos.py's SSH path, per the note above
   podman exec chaos-agent python chaos.py check
   ```
   The second `check` will report `streamflix/chaos/healthcheckFailed` to `core-engine`.
5. **`core-engine` turns it into an alarm** — check:
   ```bash
   curl http://localhost:8981/alarms
   ```
   You'll see a `streamflix/chaos/healthcheckFailed:stream-chaos-test` entry — the exact
   reduction-key mechanic from our OpenNMS `alarm-data` discussion, just running as plain
   Java instead of XML configuration.
6. **`northbound` surfaces it externally** —
   ```bash
   curl http://localhost:9000/status
   ```
   shows the active alarm count — this is what an external status page or ops dashboard
   would actually poll, without ever needing direct access to `core-engine` or the
   database.
7. **Recover it:**
   ```bash
   podman-compose restart catalog-api
   ```
   Run `chaos.py check` again — a healthy check doesn't automatically clear the alarm in
   this simplified version (a real system would send an explicit "recovered" event,
   exactly like the `serviceUp`/`clear-key` pairing from the OpenNMS guide) — try sending
   one yourself:
   ```bash
   podman exec chaos-agent python -c "
import chaos
chaos.report_event('streamflix/chaos/healthcheckPassedRecovered', 'stream-chaos-test', 'recovered')
"
   ```
   Check `/alarms` again — it's cleared, using the exact same "Recovered"-suffix pairing
   logic written into `AlarmStore.recordEvent()`.

---

## 14. What Makes This "Real Life" and What's Still Simplified

**Genuinely real-world patterns you just built:**
- Edge load balancer in front of independently deployable services
- A dedicated events/alarms pipeline, decoupled from the services that emit events
- Chaos engineering as its own separate, deliberately-breaks-things component
- A northbound interface so external tooling never needs direct database or internal
  service access
- Services split by responsibility (catalog vs. core engine vs. status) rather than one
  monolith

**Deliberately simplified vs. a real platform like Netflix:**
- One Postgres instance instead of a globally-distributed, sharded datastore
- No TLS, no auth beyond the basics — a real platform authenticates every hop, including
  service-to-service
- `catalog-api` isn't actually horizontally scaled here beyond nginx's config allowing
  for it — a real deployment would run many replicas across many hosts, with the load
  balancer doing real health-check-based routing, not just round robin
- Alarms live in memory in `core-engine` (lost on restart) — a real system persists them,
  exactly like the `alarms` table already sitting unused in `database/init.sql`, ready
  for you to wire up as a next step
- No actual video encoding/CDN/streaming protocol anywhere — "playback" here is a
  database row and an event, not real video

---

## 15. Troubleshooting & Security

- **`catalog-api` can't reach `database`** — confirm `depends_on: condition:
  service_healthy` is present; Postgres needs a few seconds after container start before
  `pg_isready` passes.
- **`edge-lb` returns 502** — check `catalog-api`/`web-ui` are actually up
  (`podman ps`); nginx can't proxy to a container that hasn't started yet, and this
  compose file doesn't currently make `edge-lb` wait for full *health*, just container
  *start* — a good exercise is adding real healthchecks to `catalog-api` and switching
  `edge-lb`'s `depends_on` to `condition: service_healthy`.
- **Alarms never appear** — confirm `core-engine` actually started (check
  `podman-compose logs core-engine` for the "listening on :8981" line) before
  `catalog-api` or `chaos-agent` try to reach it.
- **Don't run this shape in production as-is** — no TLS, default/weak passwords in plain
  env vars, and (per Section 9) no real SSH daemon in `catalog-api`, all deliberate
  simplifications for a learning exercise, not production defaults.
