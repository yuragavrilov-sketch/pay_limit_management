FROM harbor.online.tkbbank.ru/custom-base-images/openjre-alpine-musl:21.0.8
RUN addgroup -S app && adduser -S -G app -h /app app
RUN apk add --no-cache curl

# Trust the corporate CA chain so HTTPS calls validate (Vault, Postgres TLS, corp endpoints). The CA
# PEM is placed in certs/ by the CI docker_build (from the $CERT variable). Each file may hold a full
# chain (root + intermediate) — keytool imports only the FIRST cert from a multi-cert file, so we
# split and import every certificate. Fail loudly if none was found (e.g. empty $CERT).
COPY certs/ /usr/local/share/corp-ca/
RUN set -eu; \
    : "${JAVA_HOME:?JAVA_HOME must be set}"; \
    imported=0; \
    for f in /usr/local/share/corp-ca/*.crt /usr/local/share/corp-ca/*.pem; do \
        [ -f "$f" ] || continue; \
        rm -f /tmp/corpca-*.pem; i=0; \
        while IFS= read -r line || [ -n "$line" ]; do \
            printf '%s\n' "$line" >> "/tmp/corpca-$i.pem"; \
            case "$line" in *"-----END CERTIFICATE-----"*) i=$((i+1)) ;; esac; \
        done < "$f"; \
        for c in /tmp/corpca-*.pem; do \
            [ -s "$c" ] || continue; \
            grep -q "BEGIN CERTIFICATE" "$c" || continue; \
            keytool -importcert -trustcacerts -noprompt -alias "corp-$(basename "$f")-$imported" \
                -keystore "$JAVA_HOME/lib/security/cacerts" -storepass changeit -file "$c"; \
            imported=$((imported+1)); \
        done; \
        rm -f /tmp/corpca-*.pem; \
    done; \
    echo "Imported $imported corporate CA certificate(s)"; \
    [ "$imported" -gt 0 ] || { echo "ERROR: no corporate CA certificate found in certs/ (is the CERT CI variable populated in docker_build?)"; exit 1; }

WORKDIR /app
COPY deploy/pay-limit-management-*.jar /app/app.jar
RUN chown -R app:app /app
USER app
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -Djava.security.egd=file:/dev/./urandom -Duser.timezone=GMT+3 -jar /app/app.jar"]
