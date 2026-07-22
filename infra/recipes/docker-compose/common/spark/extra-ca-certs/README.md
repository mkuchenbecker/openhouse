# Extra CA certificates trusted at Spark-image build time.
# Drop *.crt (PEM) files here to have the builder stage trust them (system + JDK
# truststore). Used to trust a TLS-terminating egress proxy so the builder can
# fetch Spark, Maven artifacts, and the Livy source over HTTPS. Real *.crt files
# are gitignored (environment-specific); this keeps the dir present so COPY works.
