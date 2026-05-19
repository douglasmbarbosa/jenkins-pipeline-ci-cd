pipeline {
    agent {
        label "microservices"
    }

    options {
        skipDefaultCheckout(true)
    }

    parameters {
        choice(
            name: 'TYPE_SERVICE',
            choices: ['microservice', 'node_frontend'],
            description: 'Tipo de pipeline que será criada'
        )

        string(name: 'PROJECT_NAME',  defaultValue: '', description: 'Nome do projeto (sem espaços, minúsculo). Ex: meu-servico')
        string(name: 'NAMESPACE',     defaultValue: '', description: 'Namespace Kubernetes do projeto (obrigatório para microservice)')
        string(name: 'ECR_REPO_NAME', defaultValue: '', description: 'Nome do repositório AWS ECR (obrigatório para microservice)')
        string(name: 'GIT_REPO_URL',  defaultValue: '', description: 'URL do repositório Git. Ex: https://bitbucket.org/org/repo.git')

        booleanParam(name: 'CREATE_ECR_REPO',   defaultValue: true, description: 'Criar repositório no ECR?')
        booleanParam(name: 'CREATE_SVC_ACCOUNT', defaultValue: true, description: 'Criar Service Account no EKS?')
    }

    environment {
        AWS_DEFAULT_REGION = 'us-east-1'

        // AWS Account IDs por ambiente — substitua pelos IDs das suas contas AWS
        AWS_ACCOUNT_ID_DEV  = '{{AWS_ACCOUNT_ID_DEV}}'
        AWS_ACCOUNT_ID_HML  = '{{AWS_ACCOUNT_ID_HML}}'
        AWS_ACCOUNT_ID_PROD = '{{AWS_ACCOUNT_ID_PROD}}'

        // EKS Cluster Names — substitua pelos nomes dos seus clusters
        EKS_CLUSTER_DEV  = '{{EKS_CLUSTER_DEV}}'
        EKS_CLUSTER_HML  = '{{EKS_CLUSTER_HML}}'
        EKS_CLUSTER_PROD = '{{EKS_CLUSTER_PROD}}'

        // ID da credencial Git no Jenkins — substitua pelo ID configurado no seu Jenkins
        CREDENTIAL_ID = '{{CREDENTIAL_ID}}'

        // Repositório DevOps — substitua pela URL do seu repositório de infraestrutura
        DEVOPS_REPO_URL    = '{{DEVOPS_REPO_URL}}'
        DEVOPS_REPO_BRANCH = '{{DEVOPS_REPO_BRANCH}}'

        // Domínio base para os ingresses (ex: mycompany.com)
        BASE_DOMAIN = '{{BASE_DOMAIN}}'
    }

    stages {

        stage('Validate Parameters') {
            steps {
                container("devops") {
                    script {
                        echo "Validating parameters..."
                        if (params.PROJECT_NAME.trim() == '') {
                            error "PROJECT_NAME cannot be empty ''"
                        }
                        if (params.GIT_REPO_URL.trim() == '') {
                            error "GIT_REPO_URL cannot be empty ''"
                        }
                        if (params.TYPE_SERVICE == 'microservice') {
                            if (params.NAMESPACE.trim() == '') {
                                error "NAMESPACE cannot be empty '' for microservice type!"
                            }
                            if (params.ECR_REPO_NAME.trim() == '') {
                                error "ECR_REPO_NAME cannot be empty '' for microservice type!"
                            }
                        }
                        echo "Service type:  ${params.TYPE_SERVICE}"
                        echo "Project name:  ${params.PROJECT_NAME}"
                        echo "Namespace:     ${params.NAMESPACE}"
                        echo "ECR Repo:      ${params.ECR_REPO_NAME}"
                        echo "Git Repo:      ${params.GIT_REPO_URL}"
                    }
                }
            }
        }

        stage('Git Clone DevOps Files') {
            steps {
                container('devops') {
                    script {
                        echo "Setup Git Config..."
                        sh '''
                            git config --global user.name "jenkins-agent"
                            git config --global user.email "jenkins-agent@jenkins.com"
                        '''
                        withCredentials([string(credentialsId: 'git-api-token', variable: 'GIT_TOKEN')]) {
                            sh """
                                git clone --branch ${DEVOPS_REPO_BRANCH} --single-branch \
                                    ${DEVOPS_REPO_URL}
                            """
                        }
                    }
                }
            }
        }

        stage('Setup DEV') {
            steps {
                container('devops') {
                    script {
                        def ENVIRONMENT = 'dev'
                        assumeRole(ENVIRONMENT, env.AWS_ACCOUNT_ID_DEV)
                        setupFiles(ENVIRONMENT)
                        createECRRepo()
                        createServiceAccount(env.EKS_CLUSTER_DEV, env.AWS_ACCOUNT_ID_DEV)
                        cleanAssumeRole()
                    }
                }
            }
        }

        stage('Setup HML') {
            steps {
                container('devops') {
                    script {
                        def ENVIRONMENT = 'hml'
                        assumeRole(ENVIRONMENT, env.AWS_ACCOUNT_ID_HML)
                        setupFiles(ENVIRONMENT)
                        createECRRepo()
                        createServiceAccount(env.EKS_CLUSTER_HML, env.AWS_ACCOUNT_ID_HML)
                        cleanAssumeRole()
                    }
                }
            }
        }

        stage('Setup PROD') {
            steps {
                container('devops') {
                    script {
                        def ENVIRONMENT = 'prod'
                        setupFiles(ENVIRONMENT)
                        createECRRepo()
                        createServiceAccount(env.EKS_CLUSTER_PROD, env.AWS_ACCOUNT_ID_PROD)
                    }
                }
            }
        }

        stage('Push Files') {
            steps {
                container('devops') {
                    script {
                        sh """
                            cd devops-repo
                            if [ -n "\$(git status --porcelain)" ]; then
                                echo "Push Files..."
                                git add projects/${PROJECT_NAME}/
                                git commit -m "feat: add ${PROJECT_NAME} pipeline files"
                                git push
                            else
                                echo "Sem mudanças para commit"
                            fi
                        """
                    }
                }
            }
        }

        stage('Setup SonarQube Project') {
            steps {
                container("devops") {
                    script {
                        // Nome da instância SonarQube configurada no Jenkins (Manage Jenkins > Configure System)
                        withSonarQubeEnv('{{SONARQUBE_ENV_NAME}}') {
                            echo "Creating SonarQube project..."
                            sh """
                                curl -u \$SONAR_AUTH_TOKEN: \
                                -X POST "\${SONAR_HOST_URL}/api/projects/create" \
                                -d "project=${PROJECT_NAME}" \
                                -d "name=${PROJECT_NAME}"
                            """
                        }
                    }
                }
            }
        }

        stage('Setup Jenkins Job via API') {
            steps {
                container('devops') {
                    script {
                        echo "Setup Jenkins Job via REST API..."
                        sh """
                            sed -i 's|{{CREDENTIAL_ID}}|${CREDENTIAL_ID}|g' \
                                devops-repo/projects/${PROJECT_NAME}/job-config/job-config.xml
                            sed -i 's|{{DEVOPS_REPO_URL}}|${DEVOPS_REPO_URL}|g' \
                                devops-repo/projects/${PROJECT_NAME}/job-config/job-config.xml
                            sed -i 's|{{DEVOPS_REPO_BRANCH}}|${DEVOPS_REPO_BRANCH}|g' \
                                devops-repo/projects/${PROJECT_NAME}/job-config/job-config.xml
                        """

                        def JENKINS_URL = "http://jenkins-service.jenkins.svc:8080"
                        withCredentials([usernamePassword(credentialsId: 'jenkins-cli-api-token', usernameVariable: 'JENKINS_USER', passwordVariable: 'JENKINS_TOKEN')]) {
                            sh """
                                COOKIE_JAR=\$(mktemp /tmp/jenkins-cookies.XXXXXX)

                                echo "==> Obtendo CSRF crumb do Jenkins..."
                                CRUMB_RESPONSE=\$(curl -s \
                                    -c "\$COOKIE_JAR" \
                                    -b "\$COOKIE_JAR" \
                                    -u "\${JENKINS_USER}:\${JENKINS_TOKEN}" \
                                    "${JENKINS_URL}/crumbIssuer/api/xml?xpath=concat(//crumbRequestField,\\\":\\\",//crumb)")

                                if echo "\$CRUMB_RESPONSE" | grep -q "data-crumb-value"; then
                                    CRUMB_VALUE=\$(echo "\$CRUMB_RESPONSE" | grep -o 'data-crumb-value="[^"]*"' | cut -d'"' -f2)
                                    CRUMB="Jenkins-Crumb:\$CRUMB_VALUE"
                                else
                                    CRUMB="\$CRUMB_RESPONSE"
                                fi

                                if [ -z "\$CRUMB" ]; then
                                    echo "❌ Erro: Não foi possível obter o CSRF crumb"
                                    rm -f "\$COOKIE_JAR"
                                    exit 1
                                fi

                                echo "==> Criando job '${PROJECT_NAME}' no Jenkins..."
                                HTTP_CODE=\$(curl -s -w "%{http_code}" -o /tmp/jenkins-response.txt \
                                    -c "\$COOKIE_JAR" \
                                    -b "\$COOKIE_JAR" \
                                    -X POST \
                                    -u "\${JENKINS_USER}:\${JENKINS_TOKEN}" \
                                    -H "\$CRUMB" \
                                    -H "Content-Type: application/xml" \
                                    --data-binary @devops-repo/projects/${PROJECT_NAME}/job-config/job-config.xml \
                                    "${JENKINS_URL}/createItem?name=${PROJECT_NAME}")

                                rm -f "\$COOKIE_JAR"
                                echo "==> HTTP Status Code: \${HTTP_CODE}"

                                if [ "\$HTTP_CODE" -eq 200 ] || [ "\$HTTP_CODE" -eq 201 ]; then
                                    echo "✅ Job '${PROJECT_NAME}' criado com sucesso!"
                                    echo "🔗 URL: ${JENKINS_URL}/job/${PROJECT_NAME}"
                                elif [ "\$HTTP_CODE" -eq 400 ]; then
                                    echo "❌ Erro 400: Job já existe ou XML inválido"
                                    cat /tmp/jenkins-response.txt
                                    rm -f /tmp/jenkins-response.txt
                                    exit 1
                                else
                                    echo "❌ Erro inesperado (HTTP \${HTTP_CODE})"
                                    cat /tmp/jenkins-response.txt
                                    rm -f /tmp/jenkins-response.txt
                                    exit 1
                                fi

                                rm -f /tmp/jenkins-response.txt
                            """
                        }
                    }
                }
            }
        }
    }

    post {
        success {
            container("devops") {
                script {
                    echo "✅ Project created successfully!"
                    echo "📦 Service: ${params.PROJECT_NAME}"
                    echo "🔧 Type:    ${params.TYPE_SERVICE}"
                }
            }
        }
        failure {
            container("devops") {
                script {
                    echo "❌ Project setup failed!"
                    echo "🔍 Check the logs above for error details"
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Funções auxiliares
// ─────────────────────────────────────────────────────────────────────────────

def assumeRole(ENVIRONMENT, AWS_ACCOUNT_ID) {
    container('devops') {
        script {
            if (ENVIRONMENT == 'dev' || ENVIRONMENT == 'hml') {
                def creds = sh(
                    script: """
                        aws sts assume-role \
                            --role-arn "arn:aws:iam::${AWS_ACCOUNT_ID}:role/access-prod-role" \
                            --role-session-name "CrossAccountAccess" \
                            --query 'Credentials.[AccessKeyId,SecretAccessKey,SessionToken]' \
                            --output text
                    """,
                    returnStdout: true
                ).trim().split()
                env.AWS_ACCESS_KEY_ID     = creds[0]
                env.AWS_SECRET_ACCESS_KEY = creds[1]
                env.AWS_SESSION_TOKEN     = creds[2]
            }
        }
    }
}

def cleanAssumeRole() {
    container('devops') {
        script {
            env.AWS_ACCESS_KEY_ID     = ''
            env.AWS_SECRET_ACCESS_KEY = ''
            env.AWS_SESSION_TOKEN     = ''
        }
    }
}

def setupFiles(ENVIRONMENT) {
    container('devops') {
        script {
            echo "Create Directory Project..."
            if (params.TYPE_SERVICE == 'microservice') {
                sh """
                    cd devops-repo
                    mkdir -p projects/${PROJECT_NAME}/manifests/$ENVIRONMENT
                    mkdir -p projects/${PROJECT_NAME}/jenkinsfile
                    mkdir -p projects/${PROJECT_NAME}/job-config

                    # Copia e substitui placeholders no Jenkinsfile
                    if [ ! -f projects/${PROJECT_NAME}/jenkinsfile/${PROJECT_NAME}.Jenkinsfile ]; then
                        cp generic-pipeline/jenkinsfiles/ms-pipeline-default.Jenkinsfile \
                            projects/${PROJECT_NAME}/jenkinsfile/${PROJECT_NAME}.Jenkinsfile
                        sed -i 's|{{PROJECT_NAME}}|${PROJECT_NAME}|g'  projects/${PROJECT_NAME}/jenkinsfile/${PROJECT_NAME}.Jenkinsfile
                        sed -i 's|{{NAMESPACE}}|${NAMESPACE}|g'        projects/${PROJECT_NAME}/jenkinsfile/${PROJECT_NAME}.Jenkinsfile
                        sed -i 's|{{ECR_REPO_NAME}}|${ECR_REPO_NAME}|g' projects/${PROJECT_NAME}/jenkinsfile/${PROJECT_NAME}.Jenkinsfile
                        sed -i 's|{{GIT_REPO_URL}}|${GIT_REPO_URL}|g'  projects/${PROJECT_NAME}/jenkinsfile/${PROJECT_NAME}.Jenkinsfile
                    else
                        echo "Jenkinsfile já existe, não será sobrescrito."
                    fi

                    # Copia e substitui placeholders no job-config.xml
                    if [ ! -f projects/${PROJECT_NAME}/job-config/job-config.xml ]; then
                        cp generic-pipeline/job-config/job-config.xml projects/${PROJECT_NAME}/job-config/
                        sed -i 's|{{PROJECT_NAME}}|${PROJECT_NAME}|g' projects/${PROJECT_NAME}/job-config/job-config.xml
                    else
                        echo "job-config.xml já existe, não será sobrescrito."
                    fi

                    # Copia e substitui placeholders nos manifests K8s
                    for file in generic-pipeline/manifests-k8s/*.yaml; do
                        dest="projects/${PROJECT_NAME}/manifests/$ENVIRONMENT/\$(basename \"\$file\")"
                        if [ ! -f "\$dest" ]; then
                            cp "\$file" "\$dest"
                            sed -i 's|{{PROJECT_NAME}}|${PROJECT_NAME}|g'   \$dest
                            sed -i 's|{{NAMESPACE}}|${NAMESPACE}|g'         \$dest
                            sed -i 's|{{ECR_REPO_NAME}}|${ECR_REPO_NAME}|g' \$dest
                            sed -i 's|{{BASE_DOMAIN}}|${BASE_DOMAIN}|g'     \$dest
                            cat \$dest
                        else
                            echo "Ignorado! Arquivo já existe: \$dest"
                        fi
                    done
                """
            }
            sh "ls -la && ls -la devops-repo/projects/${PROJECT_NAME}"
        }
    }
}

def createECRRepo() {
    container('devops') {
        script {
            if (params.TYPE_SERVICE == 'microservice' && params.CREATE_ECR_REPO) {
                try {
                    sh """
                        echo "Criando repositório ECR: ${params.ECR_REPO_NAME}"
                        aws ecr create-repository \
                            --repository-name ${params.ECR_REPO_NAME} \
                            --image-tag-mutability MUTABLE \
                            --region ${AWS_DEFAULT_REGION} || echo "Repositório já existe."
                    """
                } catch (Exception e) {
                    echo "Aviso ao criar repositório ECR: ${e}"
                }
            }
        }
    }
}

def createServiceAccount(EKS_CLUSTER_NAME, AWS_ACCOUNT_ID) {
    container('devops') {
        script {
            if (params.TYPE_SERVICE == 'microservice' && params.CREATE_SVC_ACCOUNT) {
                sh """
                    echo "Cluster: $EKS_CLUSTER_NAME"
                    echo "Account: ${AWS_ACCOUNT_ID}"

                    OIDC_URL=\$(aws eks describe-cluster \
                        --name ${EKS_CLUSTER_NAME} \
                        --region \$AWS_DEFAULT_REGION \
                        --query "cluster.identity.oidc.issuer" \
                        --output text)
                    OIDC_HOST=\$(echo \$OIDC_URL | sed 's/^https:\\/\\///')

                    echo "{
                        \\"Version\\": \\"2012-10-17\\",
                        \\"Statement\\": [
                            {
                                \\"Effect\\": \\"Allow\\",
                                \\"Principal\\": {
                                    \\"Federated\\": \\"arn:aws:iam::\${AWS_ACCOUNT_ID}:oidc-provider/\${OIDC_HOST}\\"
                                },
                                \\"Action\\": \\"sts:AssumeRoleWithWebIdentity\\",
                                \\"Condition\\": {
                                    \\"StringEquals\\": {
                                        \\"\${OIDC_HOST}:aud\\": \\"sts.amazonaws.com\\",
                                        \\"\${OIDC_HOST}:sub\\": \\"system:serviceaccount:\${NAMESPACE}:\${PROJECT_NAME}-svc-account\\"
                                    }
                                }
                            }
                        ]
                    }" > trust-policy.json

                    echo "Criando IAM Role: $PROJECT_NAME-role"
                    if ! aws iam get-role --role-name $PROJECT_NAME-role > /dev/null 2>&1; then
                        aws iam create-role \
                            --role-name $PROJECT_NAME-role \
                            --assume-role-policy-document file://trust-policy.json \
                            --description "IRSA Role for $PROJECT_NAME-svc-account in $NAMESPACE"
                        echo "Role criada com sucesso."
                    else
                        echo "Role $PROJECT_NAME-role já existe."
                    fi
                """
            }
        }
    }
}