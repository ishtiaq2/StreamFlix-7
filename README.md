# StreamFlix-7

A real, runnable 7-container streaming-service architecture, built with **Podman**, so
each piece of a whiteboard system-design sketch becomes something concrete instead of
abstract. It's deliberately small — seven containers instead of thousands of
microservices — but the *relationships* between the pieces mirror a real platform like
Netflix: an edge layer absorbing traffic, independently deployable services behind it, a
database of record, an events/alarms pipeline, automated chaos testing, and a
northbound feed for external dashboards.

See [`docs/STEP-BY-STEP-GUIDE.md`](docs/STEP-BY-STEP-GUIDE.md) for the full build-it-
yourself walkthrough.

---

## The seven containers

| # | Concept | Container | What it does |
|---|---|---|---|
| 1 | Embedded Jetty + Jersey (EventD, Alarmd) | `core-engine` | Java service that receives playback/health events over REST and turns repeated ones into deduplicated alarms |
| 2 | FastAPI | `catalog-api` | Python service for browsing content and starting playback |
| 3 | Load balancer | `edge-lb` | nginx — the single front door for all customer traffic |
| 4 | PostgreSQL | `database` | Users, subscriptions, watch history, events, alarms |
| 5 | Python script → HTTP + SSH service | `chaos-agent` | A Chaos-Monkey-style resilience tester that deliberately breaks things and reports what it finds |
| 6 | Web interface | `web-ui` | The customer-facing site |
| 7 | Northbound interface | `northbound` | A read-only status/metrics API for external dashboards |

---

## Architecture

```
                              customer's browser
                                     │
                                     ▼
                        ┌──────────────────────────┐
                        │   web-ui (nginx+static)  │   :8090 (internal)
                        └────────────┬─────────────┘
                                     │ API calls
                                     ▼
                        ┌──────────────────────────┐
                        │  edge-lb (nginx)         │   :8080
                        │  reverse-proxies web-ui  │
                        │  and load-balances       │
                        │  catalog-api             │
                        └────────────┬─────────────┘
                                     │
                                     ▼
                        ┌──────────────────────────┐
                        │  catalog-api (FastAPI)   │   :8000 (internal)
                        └──────────┬───────────────┘
                                   │ playback events (HTTP)
                                   ▼
                     ┌────────────────────────────────────┐
                     │  core-engine (Jetty + Jersey)       │  :8981
                     │  /events  -- receive events         │
                     │  /alarms  -- deduplicated, active   │
                     └───────────────┬────────────────────┘
                                     │ JDBC
                                     ▼
                        ┌──────────────────────────┐
                        │  database (PostgreSQL)   │  :5432
                        └────────────┬─────────────┘
                                     ▲
                       ┌─────────────┴──────────────┐
                       │                            │
            ┌───────────────────────┐     ┌────────────────────────┐
            │ chaos-agent           │     │ northbound (FastAPI)   │
            │ HTTP health checks +  │     │ /status  /metrics      │  :9000
            │ SSH-style fault       │     │ external dashboards    │
            │ injection             │     │ poll this, nothing else│
            └───────────────────────┘     └────────────────────────┘
```

All seven containers share one Podman network (`streamflix-net`) and reach each other
by service name.

---

## Repository layout

```
.
├── README.md
├── docs/
│   └── STEP-BY-STEP-GUIDE.md
└── streamflix-stack/
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

## Prerequisites

- Podman + `podman-compose` (`pip install podman-compose --break-system-packages`)
- Java 17 and Maven are **not** required on your host — `core-engine`'s Dockerfile is a
  multi-stage build that compiles inside the container

## Quickstart

```bash
git clone <your-repo-url>
cd streamflix-repo/streamflix-stack
podman-compose up -d --build
podman-compose logs -f core-engine catalog-api
```

Once `database` and `core-engine` report healthy:

```bash
curl -I http://localhost:8080/            # web-ui, through edge-lb
curl http://localhost:8080/api/titles     # catalog-api, through edge-lb
curl http://localhost:8981/alarms         # core-engine directly
curl http://localhost:9000/status         # northbound status feed
```

Or just open `http://localhost:8080` in a browser. Full walkthrough, including how to
break something on purpose and watch the alarm appear and clear, is in
[`docs/STEP-BY-STEP-GUIDE.md`](docs/STEP-BY-STEP-GUIDE.md).

## What's genuinely "real life" here, and what's simplified

**Real patterns:** an edge load balancer in front of independently deployable services;
a dedicated events/alarms pipeline decoupled from the services that emit events; chaos
engineering as its own component; a northbound interface so external tooling never
touches the database or internal services directly; services split by responsibility
rather than one monolith.

**Deliberately simplified:** one Postgres instance instead of a distributed datastore;
no TLS and only basic auth; `catalog-api` isn't actually horizontally scaled beyond
nginx's config allowing for it; alarms live in `core-engine`'s memory and are lost on
restart (the `alarms` table in `database/init.sql` is there, unused, as a next step);
no real video encoding, CDN, or streaming protocol — "playback" is a database row and an
event.

## Security notes

- No TLS, and default/weak passwords sit in plain environment variables — fine for a
  local learning exercise, not for anything you expose beyond your own machine.
- `chaos-agent`'s SSH fault-injection path is documented but intentionally not wired up
  by default (see the step-by-step guide) — running an SSH daemon inside every
  application container is a real production anti-pattern.
- Never commit real credentials to version control if you adapt this beyond a lab.

## License

Add your license of choice here (e.g. MIT) before publishing.
