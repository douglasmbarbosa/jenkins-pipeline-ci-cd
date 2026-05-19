# Guia de Referência

Templates genéricos para criação de pipelines de microserviços com Jenkins, Kubernetes e AWS ECR. Todos os arquivos servem como base para o `setup-pipelines.Jenkinsfile`, que os copia e substitui os placeholders automaticamente ao onboarding de um novo serviço.

---

## Estrutura de Diretórios

```
pipeline/
├── docker/
│   └── Dockerfile                        # Imagem do agente Jenkins (devops container)
├── jenkinsfiles/
│   ├── ms-pipeline-default.Jenkinsfile   # Jenkinsfile padrão de microserviço
│   └── setup-pipelines.Jenkinsfile       # Pipeline de onboarding de novos serviços
├── shared-libraries/
│   ├── pipeline-ms-default.groovy              # Shared library principal (stages do deploy)
│   ├── sast-default.groovy               # Análise SAST com Semgrep + DefectDojo
│   └── sonar-default.groovy              # Análise de qualidade com SonarQube
├── job-config/
│   └── job-config.xml                    # Configuração do job Jenkins (criado via API)
└── manifests-k8s/
    ├── deployment.yaml                   # Deployment Kubernetes
    ├── service.yaml                      # Service (ClusterIP)
    ├── ingress.yaml                      # HTTPRoute (Envoy Gateway / Gateway API)
    ├── configmap.yaml                    # ConfigMap (variáveis não-sensíveis)
    ├── secret.yaml                       # Secret (variáveis sensíveis em base64)
    ├── serviceaccount.yaml               # ServiceAccount com IRSA annotation
    ├── hpa.yaml                          # HorizontalPodAutoscaler
    └── vpa.yaml                          # VerticalPodAutoscaler
```

---

## Placeholders

Os arquivos de template utilizam placeholders no formato `{{NOME}}` substituídos pelo `sed` durante a execução do `setup-pipelines.Jenkinsfile`:

| Placeholder                       | Descrição                                                              |
|-----------------------------------|------------------------------------------------------------------------|
| `{{PROJECT_NAME}}`                | Nome do projeto/serviço (ex: `my-service`)                             |
| `{{NAMESPACE}}`                   | Namespace Kubernetes (ex: `my-namespace`)                              |
| `{{ECR_REPO_NAME}}`               | Nome do repositório ECR (ex: `my-service`)                             |
| `{{GIT_REPO_URL}}`                | URL do repositório Git da aplicação                                    |
| `{{STAGE}}`                       | Ambiente atual: `dev`, `hml` ou `prod`                                 |
| `{{IMAGE_TAG}}`                   | Tag da imagem Docker (ex: `1.0.0`, `latest`)                           |
| `{{AWS_ACCOUNT_ID}}`              | ID da conta AWS do ambiente alvo                                       |
| `{{AWS_ACCOUNT_ID_DEV}}`          | ID da conta AWS de desenvolvimento                                     |
| `{{AWS_ACCOUNT_ID_HML}}`          | ID da conta AWS de homologação                                         |
| `{{AWS_ACCOUNT_ID_PROD}}`         | ID da conta AWS de produção                                            |
| `{{EKS_CLUSTER_DEV}}`             | Nome do cluster EKS de desenvolvimento                                 |
| `{{EKS_CLUSTER_HML}}`             | Nome do cluster EKS de homologação                                     |
| `{{EKS_CLUSTER_PROD}}`            | Nome do cluster EKS de produção                                        |
| `{{CREDENTIAL_ID_BITBUCKET}}`     | ID da credencial Git no Jenkins (para o repositório DevOps)            |
| `{{CREDENTIAL_ID_BITBUCKET_DEV_HML}}` | ID da credencial Git para dev/hml (checkout da aplicação)          |
| `{{CREDENTIAL_ID_BITBUCKET_PROD}}`    | ID da credencial Git para prod (checkout da aplicação)             |
| `{{DEVOPS_REPO_URL}}`             | URL do repositório DevOps (ex: `https://github.com/org/devops.git`)    |
| `{{DEVOPS_REPO_BRANCH}}`          | Branch do repositório DevOps (ex: `main`, `pipelines`)                 |
| `{{BASE_DOMAIN}}`                 | Domínio base para os ingresses (ex: `mycompany.com`)                   |
| `{{SONARQUBE_ENV_NAME}}`          | Nome da instância SonarQube no Jenkins (ex: `sonarqube`)               |
| `{{DEFECTDOJO_HOST}}`             | Hostname do DefectDojo (ex: `defectdojo.mycompany.com`)                |

---

## Arquivos em Detalhe

### `docker/Dockerfile`

Imagem Docker utilizada como **agente Jenkins** (`devops` container) nas pipelines. Baseada em `ubuntu:24.04`, inclui:

| Ferramenta       | Versão (ARG)           | Uso                                              |
|------------------|------------------------|--------------------------------------------------|
| AWS CLI v2       | latest                 | Interação com ECR, EKS, IAM, STS                 |
| kubectl          | stable                 | Deploy de manifests no Kubernetes                |
| Docker + Buildx  | `BUILDX_VERSION`       | Build multi-plataforma com cache remoto no ECR   |
| Terraform        | `TERRAFORM_VERSION`    | Provisionamento de infraestrutura                |
| Node.js (NVM)    | 20, 22, 24             | Build de aplicações Node.js                      |
| SonarScanner     | `SONAR_SCANNER_VERSION`| Análise de qualidade de código                   |
| Semgrep          | `SEMGREP_VERSION`      | Análise SAST                                     |

**Build e push da imagem:**
```bash
docker build \
  -t <AWS_ACCOUNT_ID>.dkr.ecr.<REGION>.amazonaws.com/<REPO>:latest \
  -f pipeline/docker/Dockerfile .

aws ecr get-login-password --region <REGION> | \
  docker login --username AWS --password-stdin \
  <AWS_ACCOUNT_ID>.dkr.ecr.<REGION>.amazonaws.com

docker push <AWS_ACCOUNT_ID>.dkr.ecr.<REGION>.amazonaws.com/<REPO>:latest
```

---

### `jenkinsfiles/ms-pipeline-default.Jenkinsfile`

Template do **Jenkinsfile** copiado para cada novo microserviço em `projects/<PROJECT_NAME>/jenkinsfile/`. Responsável por:

1. Receber parâmetros de deploy (ambiente, branch, tag, flags de skip)
2. Configurar variáveis dinâmicas por ambiente (AWS Account ID, EKS Cluster)
3. Carregar e executar a shared library `pipeline-ms-default.groovy`

**Parâmetros disponíveis:**

| Parâmetro       | Tipo    | Descrição                                           |
|-----------------|---------|-----------------------------------------------------|
| `ENVIRONMENT`   | choice  | Ambiente de deploy: `dev`, `hml`, `prod`            |
| `SERVICE`       | choice  | Nome do serviço (fixo após setup)                   |
| `GIT_REPO_URL`  | choice  | URL do repositório Git da aplicação                 |
| `ECR_REPO_NAME` | choice  | Nome do repositório ECR                             |
| `WORKLOAD_TYPE` | choice  | Tipo de workload K8s: `deployment` ou `statefulset` |
| `BRANCH`        | string  | Branch a ser utilizada no checkout                  |
| `IMAGE_TAG`     | string  | Tag da imagem Docker                                |
| `BUILD_IMAGE`   | boolean | Fazer novo build da imagem? (padrão: `true`)        |
| `SKIP_SONAR`    | boolean | Pular análise SonarQube? (padrão: `false`)          |
| `SKIP_SAST`     | boolean | Pular análise SAST/Semgrep? (padrão: `false`)       |

---

### `jenkinsfiles/setup-pipelines.Jenkinsfile`

Pipeline de **onboarding** de novos serviços. Executado **uma única vez** ao criar um novo microserviço. Realiza:

1. **Validação de parâmetros** — garante que campos obrigatórios foram preenchidos
2. **Clone do repositório DevOps** — branch configurável via `DEVOPS_REPO_BRANCH`
3. **Setup DEV** — cria estrutura de arquivos, repositório ECR e Service Account no cluster dev
4. **Setup HML** — idem para homologação (com assume-role cross-account)
5. **Setup PROD** — idem para produção
6. **Push dos arquivos** — commit e push dos arquivos gerados no repositório DevOps
7. **Criação do projeto no SonarQube** — via API REST
8. **Criação do job no Jenkins** — via API REST com o `job-config.xml`

**Credenciais necessárias no Jenkins:**

| Credential ID           | Tipo              | Uso                                    |
|-------------------------|-------------------|----------------------------------------|
| `bitbucket-api-token`   | Secret text       | Clone e push no repositório DevOps     |
| `<git-credential-id>`   | Username/Password | Checkout do código da aplicação        |
| `jenkins-cli-api-token` | Username/Password | Criação de jobs via API REST           |
| `defectdojo-token`      | Secret text       | Upload de relatórios SAST              |

> **Adapte os IDs de credencial** conforme o seu ambiente Jenkins.

---

### `shared-libraries/pipeline-ms-default.groovy`

Shared library principal carregada pelo Jenkinsfile via `load`. Contém todos os **stages do deploy**:

| Stage                          | Descrição                                                                  |
|--------------------------------|----------------------------------------------------------------------------|
| `Validate Parameters`          | Valida que `BRANCH` e `IMAGE_TAG` não estão vazios                         |
| `Assume Role`                  | Assume role cross-account para dev/hml via `aws sts assume-role`           |
| `Start Docker`                 | Inicia o daemon Docker no container devops                                 |
| `Update EKS Config`            | Atualiza o kubeconfig para o cluster do ambiente selecionado               |
| `Checkout Source Code`         | Faz checkout do repositório da aplicação na branch especificada            |
| `SAST Analysis`                | Executa Semgrep e envia relatório ao DefectDojo (skippável)                |
| `Sonarqube Analysis`           | Executa sonar-scanner com as configurações do projeto (skippável)          |
| `Create .env file`             | Gera `.env.temp` a partir do ConfigMap e Secret K8s (para build-time)      |
| `ECR Login`                    | Autentica no Amazon ECR via `aws ecr get-login-password`                   |
| `Setup Buildx Builder`         | Configura Docker Buildx com driver `docker-container` para cache remoto    |
| `Build and Push with BuildKit` | Build multi-stage com cache no ECR e push da imagem                        |
| `Deploy K8S`                   | Substitui placeholders nos manifests e aplica via `kubectl apply`          |
| `Rollout and Wait for Pods`    | Executa `rollout restart` e aguarda pods ficarem prontos (timeout: 10 min) |

**Fluxo de cache Docker (BuildKit + ECR):**
```
ECR Registry
└── <repo>:buildcache   ← layers armazenados no ECR
    ↑ --cache-from / --cache-to (mode=max)
```

---

### `shared-libraries/sast-default.groovy`

Executa análise **SAST (Static Application Security Testing)** com [Semgrep](https://semgrep.dev) e envia os resultados ao [DefectDojo](https://defectdojo.com).

**Stages:**
1. **SAST Scanner** — `semgrep scan --config auto --json`
2. **Upload DefectDojo** — cria produto e faz reimport do relatório JSON
3. **SAST Quality Gate** — verifica limites de vulnerabilidades por severidade

**`prod_type` (tipo de produto no DefectDojo):**

| Valor | Tipo de serviço         |
|-------|-------------------------|
| `4`   | Microservices           |
| `5`   | Frontend                |
| `6`   | Lambda / Serverless     |
| `7`   | Lambda (monorepo)       |

**Configuração de Quality Gate (opcional):**
```groovy
// Bloquear pipeline se houver mais de 0 vulnerabilidades HIGH
def remoteSast = load "pipeline/shared-libraries/sast-default.groovy"
remoteSast.remoteSast(params, env, 4, [high: 0, medium: 10, low: 999])
```

> Adapte a URL do DefectDojo (`https://your-defectdojo-host`) no arquivo `sast-default.groovy`.

---

### `shared-libraries/sonar-default.groovy`

Executa análise de qualidade de código com **SonarQube** via `sonar-scanner`.

**Pré-requisitos na aplicação:**
- Arquivo `sonar-project.properties` na raiz do repositório da aplicação
- (Opcional) Script `projects/<SERVICE>/sonar/sonar-unit-tests.groovy` para testes unitários

**Exemplo de `sonar-project.properties`:**
```properties
sonar.projectKey=my-service
sonar.sources=src
sonar.language=js
sonar.javascript.lcov.reportPaths=coverage/lcov.info
```

> Adapte o placeholder `{{SONARQUBE_ENV_NAME}}` no arquivo `sonar-default.groovy` conforme o nome da instância SonarQube configurada no Jenkins.

---

### `job-config/job-config.xml`

Configuração XML do job Jenkins criado via **API REST** pelo `setup-pipelines.Jenkinsfile`. Define:

- **Repositório SCM**: repositório DevOps (branch configurável)
- **Script path**: `projects/{{PROJECT_NAME}}/jenkinsfile/{{PROJECT_NAME}}.Jenkinsfile`
- **Credencial**: `{{CREDENTIAL_ID_BITBUCKET}}` (substituído durante o setup)

> Adapte a URL do repositório DevOps (`<url>`) no arquivo `job-config.xml`.

---

### `manifests-k8s/`

Templates de manifests Kubernetes copiados para `projects/<PROJECT_NAME>/manifests/<ENVIRONMENT>/` durante o setup. Todos os valores dinâmicos são substituídos via `sed` na pipeline.

| Arquivo              | Recurso K8s                  | Descrição                                              |
|----------------------|------------------------------|--------------------------------------------------------|
| `deployment.yaml`    | `Deployment`                 | Workload principal com liveness/readiness probes       |
| `service.yaml`       | `Service` (ClusterIP)        | Exposição interna do serviço na porta 3000             |
| `ingress.yaml`       | `HTTPRoute` (Envoy Gateway)  | Roteamento HTTP via Gateway API                        |
| `configmap.yaml`     | `ConfigMap`                  | Variáveis de ambiente não-sensíveis                    |
| `secret.yaml`        | `Secret` (Opaque)            | Variáveis sensíveis em base64                          |
| `serviceaccount.yaml`| `ServiceAccount`             | SA com IRSA annotation para acesso a serviços AWS      |
| `hpa.yaml`           | `HorizontalPodAutoscaler`    | Escalonamento horizontal (CPU/memória, min:2, max:5)   |
| `vpa.yaml`           | `VerticalPodAutoscaler`      | Ajuste automático de requests/limits de CPU e memória  |

**Padrão de hostname do Ingress por ambiente:**

| Ambiente | Hostname                                        |
|----------|-------------------------------------------------|
| `dev`    | `<PROJECT_NAME>.dev.<seu-dominio>`              |
| `hml`    | `<PROJECT_NAME>.hml.<seu-dominio>`              |
| `prod`   | `<PROJECT_NAME>.<seu-dominio>` (sem stage)      |

> Adapte o padrão de hostname no arquivo `ingress.yaml` conforme o domínio do seu ambiente.

---

## Fluxo Completo de Onboarding

```
1. Executar setup-pipelines.Jenkinsfile
        │
        ├─► Valida parâmetros
        ├─► Clona repositório DevOps
        │
        ├─► Para cada ambiente (dev / hml / prod):
        │       ├─► Assume role cross-account (dev/hml)
        │       ├─► Cria diretórios em projects/<PROJECT_NAME>/
        │       ├─► Copia e parametriza Jenkinsfile
        │       ├─► Copia e parametriza job-config.xml
        │       ├─► Copia e parametriza manifests K8s
        │       ├─► Cria repositório ECR
        │       ├─► Cria IAM Role (IRSA) + trust policy
        │       └─► Limpa credenciais temporárias
        │
        ├─► Push dos arquivos gerados no repositório DevOps
        ├─► Cria projeto no SonarQube via API
        └─► Cria job no Jenkins via API REST

2. Resultado: projects/<PROJECT_NAME>/ criado com:
        ├─► jenkinsfile/<PROJECT_NAME>.Jenkinsfile
        ├─► job-config/job-config.xml
        └─► manifests/<dev|hml|prod>/*.yaml
```

---

## Adaptações Necessárias

Antes de utilizar estes templates em um novo projeto, ajuste os seguintes pontos:

| Arquivo                              | O que adaptar                                                   |
|--------------------------------------|-----------------------------------------------------------------|
| `jenkinsfiles/ms-pipeline-default.Jenkinsfile` | AWS Account IDs, nomes dos clusters EKS, IDs de credenciais |
| `jenkinsfiles/setup-pipelines.Jenkinsfile`     | URL do repositório DevOps, branch, IDs de credenciais       |
| `shared-libraries/sast-default.groovy`         | URL do DefectDojo                                           |
| `shared-libraries/sonar-default.groovy`        | Nome da instância SonarQube no Jenkins                      |
| `job-config/job-config.xml`                    | URL do repositório DevOps, branch                           |
| `manifests-k8s/ingress.yaml`                   | Padrão de hostname e gateway                                |
| `manifests-k8s/deployment.yaml`                | Porta da aplicação, probes de health check                  |

---

## Segurança

- **Credenciais**: nunca hardcoded — todas gerenciadas via Jenkins Credentials Store
- **IRSA**: Service Accounts com IAM Roles vinculadas via OIDC (sem chaves estáticas nos pods)
- **Cross-account**: acesso a dev/hml via `sts:AssumeRole` a partir da conta prod
- **Secrets K8s**: valores em base64 — considere integração com AWS Secrets Manager ou [External Secrets Operator](https://external-secrets.io) para ambientes produtivos
- **SAST**: Semgrep executado em todo build; relatórios centralizados no DefectDojo
- **SonarQube**: Quality Gate configurável por projeto