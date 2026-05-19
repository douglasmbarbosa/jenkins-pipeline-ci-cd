def remoteStages(params, env) {

    stage('Validate Parameters') {
        container("devops") {
            script {
                if (params.BRANCH?.trim() == '') {
                    error "BRANCH cannot be empty ''"
                }
                if (params.IMAGE_TAG?.trim() == '') {
                    error "IMAGE_TAG cannot be empty ''"
                }
                echo "Environment: ${params.ENVIRONMENT} | Service: ${params.SERVICE} | Branch: ${params.BRANCH} | Tag: ${params.IMAGE_TAG}"
            }
        }
    }

    stage('Assume Role') {
        container('devops') {
            script {
                if (['dev', 'hml'].contains(params.ENVIRONMENT)) {
                    def creds = sh(
                        script: """
                            aws sts assume-role \
                                --role-arn "arn:aws:iam::${env.AWS_ACCOUNT_ID}:role/access-prod-role" \
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

    stage('Start Docker') {
        container("devops") {
            script {
                sh 'dockerd > /var/log/dockerd.log 2>&1 &'
                sh 'sleep 3 && docker version'
            }
        }
    }

    stage('Update EKS Config') {
        container("devops") {
            script {
                sh 'aws eks update-kubeconfig --region ${AWS_DEFAULT_REGION} --name ${EKS_CLUSTER_NAME}'
            }
        }
    }

    stage('Checkout Source Code') {
        script {
            dir("${params.SERVICE}") {
                checkout([
                    $class: 'GitSCM',
                    branches: [[name: "${params.BRANCH}"]],
                    userRemoteConfigs: [[
                        url: "${params.GIT_REPO_URL}",
                        credentialsId: env.CREDENTIAL_ID
                    ]]
                ])
            }
        }
    }

    if (!params.SKIP_SAST) {
        stage('SAST Analysis') {
            container("devops") {
                script {
                    try {
                        def remoteSast = load "pipeline/shared-libraries/sast-default.groovy"
                        remoteSast.remoteSast(params, env, 4)
                    } catch (Exception e) {
                        echo "SAST analysis failed: ${e}"
                    }
                }
            }
        }
    }

    if (!params.SKIP_SONAR) {
        stage('Sonarqube Analysis') {
            container("devops") {
                script {
                    try {
                        def remoteSonar = load "pipeline/shared-libraries/sonar-default.groovy"
                        remoteSonar.remoteSonar(params, env)
                    } catch (Exception e) {
                        echo "SonarQube analysis failed: ${e}"
                    }
                }
            }
        }
    }

    stage('ECR Login') {
        container('devops') {
            script {
                sh """
                    aws ecr get-login-password --region ${env.AWS_DEFAULT_REGION} | \
                    docker login --username AWS --password-stdin \
                        ${env.AWS_ACCOUNT_ID}.dkr.ecr.${env.AWS_DEFAULT_REGION}.amazonaws.com
                """
            }
        }
    }

    stage('Setup Buildx Builder') {
        container("devops") {
            script {
                sh '''
                    docker buildx create --name cache-builder --driver docker-container --use || true
                    docker buildx inspect --bootstrap
                    docker buildx ls
                '''
            }
        }
    }

    stage('Build and Push with BuildKit') {
        container("devops") {
            script {
                if (params.BUILD_IMAGE) {
                    withCredentials([string(credentialsId: 'git-api-token', variable: 'BITBUCKET_TOKEN')]) {
                        sh """
                            cd ${params.SERVICE}/

                            CACHE_REPO="${env.AWS_ACCOUNT_ID}.dkr.ecr.${env.AWS_DEFAULT_REGION}.amazonaws.com/${params.ECR_REPO_NAME}"

                            docker buildx build \
                                -f ${env.PATH_DOCKERFILE} \
                                -t ${env.FULL_IMAGE_URI} \
                                --build-arg BITBUCKET_TOKEN=\${BITBUCKET_TOKEN} \
                                --build-arg ENVIRONMENT=${params.ENVIRONMENT} \
                                --cache-from type=registry,ref=\${CACHE_REPO}:buildcache \
                                --cache-to   type=registry,ref=\${CACHE_REPO}:buildcache,mode=max \
                                --push \
                                .
                        """
                    }
                }
            }
        }
    }

    stage('Deploy K8S') {
        container("devops") {
            script {
                sh """
                    kubectl get namespace ${env.K8S_NAMESPACE} || kubectl create namespace ${env.K8S_NAMESPACE}

                    for file in projects/${env.K8S_DEPLOYMENT_NAME}/manifests/${params.ENVIRONMENT}/*.yaml; do
                        sed -i 's|{{IMAGE_TAG}}|${params.IMAGE_TAG}|g' \$file
                        if [ \$file = "projects/${env.K8S_DEPLOYMENT_NAME}/manifests/${params.ENVIRONMENT}/ingress.yaml" ] \
                            && [ '${params.ENVIRONMENT}' = "prod" ]; then
                            sed -i 's|-{{STAGE}}||g' \$file
                        else
                            sed -i 's|{{STAGE}}|${params.ENVIRONMENT}|g' \$file
                        fi
                        sed -i 's|{{AWS_ACCOUNT_ID}}|${env.AWS_ACCOUNT_ID}|g' \$file
                    done

                    kubectl apply -f projects/${params.SERVICE}/manifests/${params.ENVIRONMENT}
                """
            }
        }
    }

    stage('Rollout and Wait for Pods') {
        container("devops") {
            script {
                sh """
                    kubectl rollout restart ${params.WORKLOAD_TYPE}/${env.K8S_DEPLOYMENT_NAME} \
                        -n ${env.K8S_NAMESPACE}

                    kubectl rollout status ${params.WORKLOAD_TYPE}/${env.K8S_DEPLOYMENT_NAME} \
                        -n ${env.K8S_NAMESPACE} --timeout=600s

                    kubectl get pods -n ${env.K8S_NAMESPACE} -l app=${env.K8S_DEPLOYMENT_NAME}
                """
            }
        }
    }
}

return this