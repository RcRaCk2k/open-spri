# Container image "proxy": edge nginx for the all-in-one stack (TLS, routing, ACME challenge).
FROM nginx:1.30-alpine
RUN apk add --no-cache openssl
COPY deploy/nginx/snippets/ /etc/nginx/snippets/
COPY deploy/nginx/templates/ /etc/nginx/templates/
COPY deploy/proxy/40-openspri-tls.sh /docker-entrypoint.d/40-openspri-tls.sh
RUN chmod +x /docker-entrypoint.d/40-openspri-tls.sh && rm /etc/nginx/conf.d/default.conf
