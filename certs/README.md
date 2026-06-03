# Corporate CA certificates (vendored, pinned)

Drop the corporate **root CA** (PEM, `*.crt`/`*.pem`) here so the image trusts corp HTTPS
endpoints (e.g. Postgres TLS). The `Dockerfile` imports every cert into the JRE truststore.
Public CA cert — safe to commit; never put private keys here.
