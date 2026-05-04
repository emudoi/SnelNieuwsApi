# =============================================================================
# emudoi-snelnieuws-api — runtime image
# =============================================================================
# Slim, single-stage. The fat JAR is built outside (Jenkinsfile `Build` stage,
# in an sbt agent container) and copied in here. Keeping sbt + apt-get out of
# the image build means kaniko doesn't depend on Ubuntu mirror availability.
# =============================================================================

FROM eclipse-temurin:17-jre

LABEL org.opencontainers.image.title="emudoi-snelnieuws-api" \
      org.opencontainers.image.description="SnelNieuws API Service" \
      org.opencontainers.image.vendor="emudoi" \
      com.emudoi.service="emudoi-snelnieuws-api" \
      com.emudoi.environment="production"

RUN groupadd -g 1001 emudoi && \
    useradd -u 1001 -g emudoi -s /bin/false emudoi && \
    mkdir -p /var/log/emudoi /opt/emudoi-snelnieuws-api && \
    chown -R emudoi:emudoi /var/log/emudoi /opt/emudoi-snelnieuws-api

WORKDIR /opt/emudoi-snelnieuws-api

COPY target/scala-2.13/emudoi-snelnieuws-api.jar /opt/emudoi-snelnieuws-api/app.jar
RUN chown emudoi:emudoi /opt/emudoi-snelnieuws-api/app.jar

USER emudoi
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
