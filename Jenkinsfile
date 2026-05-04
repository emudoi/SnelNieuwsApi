// =============================================================================
//  emudoi-snelnieuws-api — k3s pipeline (sbt → image → bootstrap → deploy)
// =============================================================================
//  Inherits the agent pod (sbt + kaniko + kubectl + vault containers and the
//  shared dep cache PVC) from the cluster-wide `scala-build` JCasC pod
//  template defined in emudoi-k3s-infra/ansible/roles/jenkins/. This file
//  owns only the *stages* — what the build actually does for THIS service —
//  not the toolchain shape. To bump JDK / sbt / kaniko versions, edit the
//  template in emudoi-k3s-infra; this file picks it up on the next build.
//
//  Bootstrap stage is fully idempotent: namespace, GHCR pull-secret, app SA,
//  per-service Vault policy + k8s-auth role. First run on a new repo creates
//  them; subsequent runs no-op. No DNS work — the cluster's wildcard A record
//  *.v1.emudoi.com (managed by the infra repo's deploy.yml) covers every
//  service hostname for free.
//
//  Required cluster prerequisites (set by emudoi-k3s-infra; zero per-service
//  config — adding a new service is just dropping a Jenkinsfile + k8s/ dir):
//    - `scala-build` JCasC pod template registered with Jenkins
//    - Vault Agent Injector running in `vault` namespace
//    - Vault kubernetes auth method enabled, jenkins-deployer role bound
//    - Jenkins SAs cluster-admin
//    - DB credentials at secret/<service-vault-path> auto-seeded by the
//      infra repo's postgres role (no manual `vault kv put` ever)
//    - *.v1.emudoi.com wildcard A record for cluster-wide hostname routing
//
//  Required Jenkins credentials (already in JCasC):
//    - github-token-secret : GHCR push PAT
// =============================================================================

pipeline {
  agent {
    kubernetes {
      inheritFrom 'scala-build'
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
          // env.GIT_COMMIT is populated by `checkout scm`; take the first 7
          // chars instead of shelling out to git (which would need careful
          // container/UID handling for the workspace).
          env.GIT_SHORT_SHA = (env.GIT_COMMIT ?: '').take(7)
          env.IMAGE_TAG     = env.GIT_SHORT_SHA
          echo "Building ${IMAGE_REPO}:${IMAGE_TAG}"
        }
      }
    }

    stage('Build (sbt assembly)') {
      when { branch 'main' }
      steps {
        container('sbt') {
          sh '''
            set -e
            # -Dfile.encoding=UTF-8 avoids stray locale issues on minimal images.
            # No daemon — short-lived agent, daemon shutdown noise is just clutter.
            sbt -Dfile.encoding=UTF-8 -batch clean assembly
            ls -lh target/scala-2.13/emudoi-snelnieuws-api.jar
          '''
        }
      }
    }

    stage('Image (kaniko push)') {
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

    stage('Bootstrap (Vault auth + namespace + GHCR pull-secret)') {
      when { branch 'main' }
      steps {
        // Wire Vault: per-service policy + k8s-auth role so the pod's Vault
        // Agent Injector can read THIS service's KV path. DNS isn't here —
        // the cluster's *.v1.emudoi.com wildcard A record (managed by infra
        // repo's deploy.yml) covers every service hostname for free.
        // DB credentials at secret/<vault_path> are seeded by the infra
        // postgres role; this stage doesn't touch them.
        container('vault') {
          sh '''
            export VAULT_ADDR=${VAULT_ADDR}
            VAULT_TOKEN=$(vault write -field=token auth/kubernetes/login \
                            role=jenkins-deployer \
                            jwt=$(cat /var/run/secrets/kubernetes.io/serviceaccount/token))

            # Per-service ACL policy: read-only on this service's KV path.
            cat > ${WORKSPACE}/.svc-policy.hcl <<EOF
path "${VAULT_PATH}" {
  capabilities = ["read"]
}
path "${VAULT_PATH}/*" {
  capabilities = ["read"]
}
EOF
            VAULT_TOKEN=$VAULT_TOKEN vault policy write ${VAULT_POLICY} ${WORKSPACE}/.svc-policy.hcl

            # Per-service k8s-auth role: bind <ns>/<sa> → policy.
            VAULT_TOKEN=$VAULT_TOKEN vault write auth/kubernetes/role/${VAULT_ROLE} \
              bound_service_account_names=${SERVICE} \
              bound_service_account_namespaces=${NAMESPACE} \
              policies=${VAULT_POLICY} \
              ttl=1h
          '''
        }
        // Namespace + app SA + GHCR pull-secret (idempotent).
        container('kubectl') {
          withCredentials([string(credentialsId: 'github-token-secret', variable: 'GHCR_TOKEN')]) {
            sh '''
              set -e
              kubectl create namespace ${NAMESPACE} \
                --dry-run=client -o yaml | kubectl apply -f -
              kubectl -n ${NAMESPACE} create serviceaccount ${SERVICE} \
                --dry-run=client -o yaml | kubectl apply -f -
              kubectl -n ${NAMESPACE} create secret docker-registry ghcr-creds \
                --docker-server=ghcr.io \
                --docker-username=emudoi \
                --docker-password="${GHCR_TOKEN}" \
                --dry-run=client -o yaml | kubectl apply -f -
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
