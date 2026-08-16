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
