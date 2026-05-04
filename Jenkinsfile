// =============================================================================
//  emudoi-snelnieuws-api — k3s pipeline (build → bootstrap → deploy)
// =============================================================================
//  Runs on the cluster's Jenkins via the kubernetes plugin. The agent pod has:
//    - kaniko  : builds the image, pushes to GHCR (no Docker daemon needed)
//    - kubectl : alpine/k8s — kubectl + curl + jq for bootstrap + deploy
//    - vault   : hashicorp/vault — Vault CLI for k8s-auth login + role mgmt
//
//  Bootstrap stage is fully idempotent: namespace, GHCR pull-secret, app SA,
//  per-service Vault policy + k8s-auth role, GoDaddy A record. First run on
//  a new repo creates them; subsequent runs no-op.
//
//  Required cluster prerequisites (one-time, set by emudoi-k3s-infra):
//    - Vault Agent Injector running in `vault` namespace
//    - Vault kubernetes auth method enabled
//    - Vault role `jenkins-deployer` bound to jenkins/jenkins-agent SAs
//    - Jenkins SAs cluster-admin
//
//  Required Vault paths (seed once via bin/seed-vault.sh):
//    - secret/data/_global/godaddy { api_key, api_secret }
//    - secret/data/snelnieuws/api  { DB_HOST, DB_PORT, DB_NAME, DB_USER,
//                                     DB_PASSWORD, KAFKA_SUMMARIZED_IMPORT_ENABLED }
//
//  Required Jenkins credentials (already in JCasC):
//    - github-token-secret : GHCR push PAT
// =============================================================================

pipeline {
  agent {
    kubernetes {
      defaultContainer 'kubectl'
      yaml '''
apiVersion: v1
kind: Pod
spec:
  serviceAccountName: jenkins-agent
  containers:
    - name: jnlp
      resources:
        requests: { cpu: 100m, memory: 256Mi }
    - name: kaniko
      image: gcr.io/kaniko-project/executor:v1.23.2-debug
      command: ["/busybox/cat"]
      tty: true
      resources:
        requests: { cpu: 500m, memory: 1Gi }
        limits:   { memory: 3Gi }
      volumeMounts:
        - { name: docker-config, mountPath: /kaniko/.docker }
    - name: kubectl
      image: alpine/k8s:1.31.0
      command: ["cat"]
      tty: true
      resources:
        requests: { cpu: 50m, memory: 128Mi }
    - name: vault
      image: hashicorp/vault:1.18
      command: ["cat"]
      tty: true
      resources:
        requests: { cpu: 50m, memory: 64Mi }
  volumes:
    - name: docker-config
      emptyDir: {}
'''
    }
  }

  environment {
    SERVICE      = 'snelnieuws-api'
    NAMESPACE    = 'snelnieuws'
    HOSTNAME     = 'api.snel.v1.emudoi.com'
    DOMAIN       = 'emudoi.com'
    IMAGE_REPO   = 'ghcr.io/emudoi/emudoi-snelnieuws-api'
    VAULT_ADDR   = 'http://vault.vault.svc:8200'
    VAULT_PATH   = 'secret/data/snelnieuws/api'  // KV-v2 read path
    VAULT_ROLE   = 'snelnieuws-api'
    VAULT_POLICY = 'snelnieuws-api-read'
  }

  options {
    timeout(time: 30, unit: 'MINUTES')
    disableConcurrentBuilds()
    buildDiscarder(logRotator(numToKeepStr: '10'))
  }

  stages {

    stage('Checkout') {
      steps {
        checkout scm
        script {
          env.GIT_SHORT_SHA = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
          env.IMAGE_TAG     = env.GIT_SHORT_SHA
          echo "Building ${IMAGE_REPO}:${IMAGE_TAG}"
        }
      }
    }

    stage('Build & Push') {
      when { branch 'main' }
      steps {
        container('kaniko') {
          withCredentials([string(credentialsId: 'github-token-secret', variable: 'GHCR_TOKEN')]) {
            sh '''
              # Construct kaniko's docker config for GHCR auth (user "emudoi"
              # matches the JCasC seed for the GHCR PAT).
              AUTH=$(printf '%s' "emudoi:${GHCR_TOKEN}" | base64 -w0)
              cat > /kaniko/.docker/config.json <<EOF
{"auths":{"ghcr.io":{"auth":"${AUTH}"}}}
EOF
              /kaniko/executor \
                --context=. \
                --dockerfile=Dockerfile \
                --destination="${IMAGE_REPO}:${IMAGE_TAG}" \
                --destination="${IMAGE_REPO}:latest" \
                --label="com.emudoi.git.sha=${GIT_COMMIT}" \
                --label="com.emudoi.build.url=${BUILD_URL}" \
                --snapshot-mode=redo \
                --use-new-run
            '''
          }
        }
      }
    }

    stage('Bootstrap (cluster + Vault + DNS)') {
      when { branch 'main' }
      steps {
        // Login to Vault (k8s auth) and dump the token to a shared file the
        // kubectl container can read for subsequent API calls.
        container('vault') {
          sh '''
            export VAULT_ADDR=${VAULT_ADDR}
            VAULT_TOKEN=$(vault write -field=token auth/kubernetes/login \
                            role=jenkins-deployer \
                            jwt=$(cat /var/run/secrets/kubernetes.io/serviceaccount/token))
            echo -n "$VAULT_TOKEN" > /tmp/vault-token
            chmod 600 /tmp/vault-token

            # Per-service ACL policy: read-only on this service's KV path.
            cat > /tmp/svc-policy.hcl <<EOF
path "${VAULT_PATH}" {
  capabilities = ["read"]
}
path "${VAULT_PATH}/*" {
  capabilities = ["read"]
}
EOF
            VAULT_TOKEN=$VAULT_TOKEN vault policy write ${VAULT_POLICY} /tmp/svc-policy.hcl

            # Per-service k8s-auth role: bind <ns>/<sa> → policy.
            VAULT_TOKEN=$VAULT_TOKEN vault write auth/kubernetes/role/${VAULT_ROLE} \
              bound_service_account_names=${SERVICE} \
              bound_service_account_namespaces=${NAMESPACE} \
              policies=${VAULT_POLICY} \
              ttl=1h
          '''
        }
        // Cluster bootstrap + DNS via the alpine/k8s container.
        container('kubectl') {
          withCredentials([string(credentialsId: 'github-token-secret', variable: 'GHCR_TOKEN')]) {
            sh '''
              set -e
              VAULT_TOKEN=$(cat /tmp/vault-token)

              # 1. Namespace + app ServiceAccount (idempotent via apply).
              kubectl create namespace ${NAMESPACE} \
                --dry-run=client -o yaml | kubectl apply -f -
              kubectl -n ${NAMESPACE} create serviceaccount ${SERVICE} \
                --dry-run=client -o yaml | kubectl apply -f -

              # 2. GHCR pull secret in the target namespace, sourced from the
              #    same PAT JCasC already gave Jenkins. apply --dry-run=client
              #    pattern keeps it idempotent across re-runs and PAT rotations.
              kubectl -n ${NAMESPACE} create secret docker-registry ghcr-creds \
                --docker-server=ghcr.io \
                --docker-username=emudoi \
                --docker-password="${GHCR_TOKEN}" \
                --dry-run=client -o yaml | kubectl apply -f -

              # 3. DNS — pull GoDaddy creds from Vault, ensure A record points
              #    at the cluster's public IP. CLUSTER_IP comes from the
              #    well-known `kube.emudoi.com` record (already managed by the
              #    infra repo's deploy.yml, points at the control plane).
              GD_KEY=$(curl -sf -H "X-Vault-Token: ${VAULT_TOKEN}" \
                        "${VAULT_ADDR}/v1/secret/data/_global/godaddy" \
                        | jq -r '.data.data.api_key')
              GD_SECRET=$(curl -sf -H "X-Vault-Token: ${VAULT_TOKEN}" \
                           "${VAULT_ADDR}/v1/secret/data/_global/godaddy" \
                           | jq -r '.data.data.api_secret')
              if [ -z "$GD_KEY" ] || [ "$GD_KEY" = "null" ]; then
                echo "ERROR: secret/data/_global/godaddy missing or has no api_key." >&2
                echo "Seed it once via bin/seed-vault.sh on a workstation:" >&2
                echo "  _global/godaddy:" >&2
                echo "    api_key: <godaddy api key>" >&2
                echo "    api_secret: <godaddy api secret>" >&2
                exit 1
              fi
              CLUSTER_IP=$(getent hosts kube.${DOMAIN} | awk '{print $1}')
              [ -n "$CLUSTER_IP" ] || { echo "Failed to resolve kube.${DOMAIN}" >&2; exit 1; }
              SUBNAME=${HOSTNAME%.${DOMAIN}}
              echo "Ensuring DNS: ${HOSTNAME} → ${CLUSTER_IP}"
              curl -sf -X PUT \
                "https://api.godaddy.com/v1/domains/${DOMAIN}/records/A/${SUBNAME}" \
                -H "Authorization: sso-key ${GD_KEY}:${GD_SECRET}" \
                -H 'Content-Type: application/json' \
                -d "[{\\"data\\":\\"${CLUSTER_IP}\\",\\"ttl\\":600}]"
            '''
          }
        }
      }
    }

    stage('Deploy') {
      when { branch 'main' }
      steps {
        container('kubectl') {
          sh '''
            set -e
            # Substitute the image into the Deployment manifest. Single
            # placeholder (IMAGE_PLACEHOLDER) keeps the manifest readable and
            # avoids dragging in kustomize/helm for one swap.
            sed "s|IMAGE_PLACEHOLDER|${IMAGE_REPO}:${IMAGE_TAG}|" \
              k8s/deployment.yaml > /tmp/deployment.yaml

            kubectl apply -f /tmp/deployment.yaml
            kubectl apply -f k8s/service.yaml
            kubectl apply -f k8s/ingress.yaml

            kubectl -n ${NAMESPACE} rollout status \
              deployment/${SERVICE} --timeout=5m
          '''
        }
      }
    }
  }

  post {
    success {
      echo "✓ Deployed ${SERVICE} ${IMAGE_TAG} → https://${HOSTNAME}"
    }
    failure {
      echo "✗ Pipeline failed for ${SERVICE} (${env.BRANCH_NAME ?: 'unknown branch'})"
    }
  }
}
