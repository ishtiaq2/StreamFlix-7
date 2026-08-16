# Step-by-Step: Building StreamFlix-7

This walks through building the whole stack from nothing, in the order that actually
makes sense to build it in (database schema first, then the service everything else
depends on, then outward to the edge). See the main [README](../README.md) for the
finished architecture and repo layout.

---

## Part 1 — The idea, in one paragraph

Real streaming platforms — Netflix chief among them — are built from this shape: an
edge layer that absorbs traffic, a set of independently deployable services behind it, a
database of record, an events/alarms pipeline watching for trouble, automated
resilience testing that deliberately breaks things (Netflix's own **Chaos Monkey**,
part of their "Simian Army" tooling, is the direct namesake for `chaos-agent` here), and
a **northbound** feed — an interface exposed *upward* to something that monitors this
system (a dashboard, an NOC), as opposed to *southbound*, which would mean this system
talking down to the things it manages. StreamFlix-7 is a small, runnable version of
that shape.

---

## Part 2 — Project layout

```bash
mkdir -p streamflix-stack/{database,core-engine/src/main/java/com/streamflix/core,catalog-api,web-ui,edge-lb,chaos-agent,northbound}
cd streamflix-stack
```

You'll fill in each directory in the order below.

---

## Part 3 — Build the database first (everything else depends on this schema)

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

No Dockerfile needed here — the official `postgres` image runs any `.sql` file it finds
under `/docker-entrypoint-initdb.d/` on first boot, and the compose file (Part 10) bind-
mounts this file straight into that directory.

Note the `events` and `alarms` tables exist now but aren't wired up to `core-engine`
yet — that's intentional (see Part 12's "what's simplified" note) and a good follow-up
exercise once the rest of the stack works.

---

## Part 4 — core-engine: the embedded Jetty + Jersey service

This is the capstone service: a standalone embedded Jetty server running a
Jersey-powered REST API, implementing the same reduction-key alarm-deduplication logic
that a monitoring system's event configuration does declaratively — just written
directly in Java here.

### Step 1 — `core-engine/pom.xml`

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

### Step 2 — `Main.java` (the embedded Jetty server)

`core-engine/src/main/java/com/streamflix/core/Main.java`:

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

### Step 3 — `AlarmStore.java` (the reduction-key logic)

`core-engine/src/main/java/com/streamflix/core/AlarmStore.java`:

```java
package com.streamflix.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class AlarmStore {
    public static final AlarmStore INSTANCE = new AlarmStore();

    // key = reduction key (uei + streamId) -- same concept as a monitoring
    // system's reduction-key: it's how repeated events collapse into one alarm.
    private final Map<String, Alarm> alarms = new ConcurrentHashMap<>();

    public void recordEvent(String uei, String streamId, String message) {
        String reductionKey = uei + ":" + streamId;

        alarms.compute(reductionKey, (key, existing) -> {
            if (uei.endsWith("Recovered")) {
                // "recovered" events clear the matching "down" alarm --
                // same pairing mechanic as a clear-key.
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

### Step 4 — the two Jersey resources

`core-engine/src/main/java/com/streamflix/core/EventResource.java`:

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

### Step 5 — `core-engine/Dockerfile` (multi-stage, so the final image skips the Maven toolchain)

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

`EventResource` plays the role a monitoring system's REST events endpoint plays;
`AlarmStore`'s reduction-key logic is a hand-rolled version of what declarative
alarm-reduction config does automatically.

---

## Part 5 — catalog-api: the customer-facing FastAPI service

This browses titles and starts playback, and reports playback events to `core-engine`
over plain HTTP — the same integration pattern as any external service reporting into
an events pipeline.

### Step 1 — `catalog-api/requirements.txt`

```
fastapi==0.115.0
uvicorn==0.30.6
psycopg2-binary==2.9.9
requests==2.32.3
```

### Step 2 — `catalog-api/main.py`

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

    # Report a normal playback-start event to the core engine.
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

### Step 3 — `catalog-api/Dockerfile`

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

## Part 6 — web-ui: the customer-facing page

Plain HTML/JS is enough to demonstrate the full request path without a frontend build
toolchain.

### Step 1 — `web-ui/index.html`

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
    // web-ui talks to edge-lb, never to catalog-api directly --
    // the "one front door" principle.
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

### Step 2 — `web-ui/Dockerfile`

```dockerfile
FROM docker.io/library/nginx:alpine
COPY index.html /usr/share/nginx/html/index.html
EXPOSE 80
```

---

## Part 7 — edge-lb: the load balancer and single front door

nginx sits in front of `catalog-api` and reverse-proxies `web-ui`. It doesn't know or
care that FastAPI is Python behind it — that's the point of a load balancer.

### Step 1 — `edge-lb/nginx.conf`

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

### Step 2 — `edge-lb/Dockerfile`

```dockerfile
FROM docker.io/library/nginx:alpine
COPY nginx.conf /etc/nginx/nginx.conf
EXPOSE 8080
```

---

## Part 8 — chaos-agent: HTTP health checks + SSH-style fault injection

This is a small Chaos-Monkey-style agent: it checks `catalog-api`'s health over HTTP,
and documents (without enabling by default) using SSH to reach into a target container
and cause a controlled failure — then reports whatever it finds to `core-engine`, the
same way any monitoring integration would.

### Step 1 — `chaos-agent/requirements.txt`

```
requests==2.32.3
paramiko==3.4.0
```

### Step 2 — `chaos-agent/chaos.py`

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
    the target container (see Part 8, Step 4 below for why that's off by default).
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
        # Deliberately induce a failure via SSH.
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

### Step 3 — `chaos-agent/Dockerfile`

```dockerfile
FROM python:3.12-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY chaos.py .
CMD ["sleep", "infinity"]
```

### Step 4 — why the SSH path is documented but not wired up

For `simulate_outage_via_ssh()` to actually work, the *target* container
(`catalog-api`) needs its own SSH server running inside it, which its Dockerfile
(Part 5, Step 3) doesn't include, on purpose: running SSH inside every application
container is a real production anti-pattern — it widens the attack surface and
duplicates what the container orchestrator's own exec functionality already gives you.

For this guide, treat `simulate_outage_via_ssh()` as a **documented capability** and use
the safer, equally realistic alternative for actually running the demo (Part 11):

```bash
podman exec catalog-api pkill -f uvicorn
```

This achieves the identical "kill the process from outside the container" effect,
using Podman's own remote-exec mechanism instead of maintaining a separate SSH daemon
per container — the trade-off real chaos tooling, including Netflix's own, has largely
moved toward as well, favoring orchestrator APIs over SSH fleets where the platform
provides one.

---

## Part 9 — northbound: the read-only status/metrics feed

*Northbound* means an interface exposed **upward**, to something that monitors or
manages this system (a dashboard, an NOC, a partner integration) — as opposed to
*southbound*, which would mean this system talking down to the things it manages. This
container is exactly that: external tools poll it and never touch `core-engine` or the
database directly.

### Step 1 — `northbound/requirements.txt`

```
fastapi==0.115.0
uvicorn==0.30.6
psycopg2-binary==2.9.9
requests==2.32.3
```

### Step 2 — `northbound/main.py`

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

### Step 3 — `northbound/Dockerfile`

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

## Part 10 — Wire it all together: `podman-compose.yml`

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

Seven services, one shared network — matching the architecture diagram in the README
exactly.

> **Note on `depends_on`:** as with the OpenNMS lab, some `podman-compose` versions
> only wait for a container to *start*, not for its healthcheck to pass. `edge-lb`
> currently depends on `catalog-api`'s *start*, not its health — see Part 13's
> troubleshooting note for how to tighten this.

---

## Part 11 — Run the whole thing

```bash
cd streamflix-stack
podman-compose up -d --build
podman-compose logs -f core-engine catalog-api
```

Give Postgres and `core-engine` a few seconds to become healthy, then:

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

Or open `http://localhost:8080` in a browser — that's `web-ui`, reverse-proxied through
`edge-lb`, calling `catalog-api`.

---

## Part 12 — Walkthrough: a customer watches a show, then something breaks

This is the full, real request path, end to end.

### Step 1 — Browser → web-ui

Open `http://localhost:8080`. `edge-lb` proxies `/` to `web-ui`, which serves the
HTML/JS page.

### Step 2 — web-ui → edge-lb → catalog-api

The page's JS calls `/api/titles`; `edge-lb` proxies that to `catalog-api`, which
queries Postgres and returns the catalog.

### Step 3 — Click a title → catalog-api → core-engine

`POST /api/play/1` inserts a `watch_history` row, then reports a
`streamflix/playback/started` event to `core-engine` over HTTP.

### Step 4 — Break something on purpose

```bash
podman exec chaos-agent python chaos.py check
podman exec catalog-api pkill -f uvicorn
podman exec chaos-agent python chaos.py check
```

The second `check` reports `streamflix/chaos/healthcheckFailed` to `core-engine`.

### Step 5 — core-engine turns it into an alarm

```bash
curl http://localhost:8981/alarms
```

You'll see a `streamflix/chaos/healthcheckFailed:stream-chaos-test` entry — the same
reduction-key mechanic from `AlarmStore`, running as plain Java.

### Step 6 — northbound surfaces it externally

```bash
curl http://localhost:9000/status
```

Shows the active alarm count — what an external status page or ops dashboard would
actually poll, without ever needing direct access to `core-engine` or the database.

### Step 7 — Recover it

```bash
podman-compose restart catalog-api
```

Run `chaos.py check` again — a healthy check does **not** automatically clear the
alarm in this simplified version (a real system would send an explicit "recovered"
event, the same `serviceUp`/clear-key pairing idea from the OpenNMS lab). Send one
yourself:

```bash
podman exec chaos-agent python -c "
import chaos
chaos.report_event('streamflix/chaos/healthcheckPassedRecovered', 'stream-chaos-test', 'recovered')
"
```

Check `/alarms` again — it's cleared, using the same "Recovered"-suffix pairing logic
written into `AlarmStore.recordEvent()`.

---

## Part 13 — Troubleshooting

- **`catalog-api` can't reach `database`** — confirm `depends_on: condition:
  service_healthy` is present for the `database` dependency; Postgres needs a few
  seconds after container start before `pg_isready` passes.
- **`edge-lb` returns 502** — check `catalog-api`/`web-ui` are actually up
  (`podman ps`); nginx can't proxy to a container that hasn't started yet, and this
  compose file doesn't currently make `edge-lb` wait for full *health*, just container
  *start*. A good exercise: add a real healthcheck to `catalog-api` and switch
  `edge-lb`'s `depends_on` to `condition: service_healthy`.
- **Alarms never appear** — confirm `core-engine` actually started (check
  `podman-compose logs core-engine` for the `listening on :8981` line) before
  `catalog-api` or `chaos-agent` try to reach it.
- **`chaos.py break` fails to connect over SSH** — expected; `catalog-api` doesn't run
  an SSH daemon by default (Part 8, Step 4). Use `podman exec catalog-api pkill -f
  uvicorn` instead, as Part 12 does.

---

## Part 14 — Security notes

- No TLS, and default/weak passwords sit in plain environment variables — acceptable
  for a local learning exercise, not for anything exposed beyond your own machine.
- `chaos-agent`'s SSH fault-injection path is documented but intentionally not enabled
  by default — see Part 8, Step 4.
- Never commit real credentials to version control if you adapt this beyond a lab.

---

## Part 15 — What's next

- Wire up the `events` and `alarms` tables in `database/init.sql` so `core-engine`
  persists state instead of losing it on restart.
- Add real healthchecks to `catalog-api` and tighten `edge-lb`'s `depends_on` (Part 13).
- Scale `catalog-api` to multiple replicas and confirm `edge-lb` actually load-balances
  across them.
- Add authentication between services, not just at the edge.
- Replace the in-memory `AlarmStore` with a Postgres-backed one using the existing
  `alarms` table's `reduction_key` primary key.
