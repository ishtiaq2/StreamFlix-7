# Optional customization layer over the official PostgreSQL image.
#
# The compose file defaults to pulling docker.io/library/postgres:16 directly
# with `image:` -- that's the supported, recommended path, since init.sql is
# bind-mounted into /docker-entrypoint-initdb.d/ and runs automatically on
# first boot. Use this Dockerfile (switch the compose service to
# `build: ./database`) only if you want init.sql (and any future init
# scripts) baked into the image instead of bind-mounted, or need extensions
# installed.

FROM docker.io/library/postgres:16

# Bake the schema in instead of relying on the bind mount, if preferred:
# COPY init.sql /docker-entrypoint-initdb.d/init.sql

# Slightly stronger default auth method than Postgres's historical default.
ENV POSTGRES_HOST_AUTH_METHOD=scram-sha-256
