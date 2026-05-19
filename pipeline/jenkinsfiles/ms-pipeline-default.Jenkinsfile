pipeline {
    agent {
        label "microservices"
    }

    parameters {
        choice(
            name: 'ENVIRONMENT',
            choices: ['dev', 'hml', 'prod'],
            description: 'Environment to deploy'
        )
        choice(
            name: 'SERVICE',
            choices: ['{{PROJECT_NAME}}'],
            description: 'Service to deploy'
        )

        choice(
            name: 'GIT_REPO_URL',
            choices: ['{{GIT_REPO_URL}}'],
            description: 'Git URL for deploy'
        )

        choice(
            name: 'ECR_REPO_NAME',
            choices: ['{{ECR_REPO_NAME}}'],
            description: 'ECR Repo for deploy'
        )

        choice(
            name: 'WORKLOAD_TYPE',
            choices: ['deployment', 'statefulset'],
            description: 'Tipo de workload Kubernetes (deployment ou statefulset)'
        )

        string(name: 'BRANCH', defaultValue: '', description: 'Digite o NOME DA BRANCH que será utilizada no deploy')
        string(name: 'IMAGE_TAG', defaultValue: '', description: 'Digite a TAG DA IMAGEM do projeto. Ex: 1.0.0, latest')
        booleanParam(name: 'BUILD_IMAGE', defaultValue: true, description: 'Deseja fazer um novo BUILD?')
        booleanParam(name: 'SKIP_SONAR', defaultValue: false, description: 'Pular análise do SonarQube?')
        booleanParam(name: 'SKIP_SAST', defaultValue: false, description: 'Pular análise SAST (Semgrep)?')
    }

    environment {

        AWS_DEFAULT_REGION = 'us-east-1'
        K8S_NAMESPACE = '{{NAMESPACE}}'

        // AWS Account IDs por ambiente
        AWS_ACCOUNT_ID_DEV  = '{{AWS_ACCOUNT_ID_DEV}}'
        AWS_ACCOUNT_ID_HML  = '{{AWS_ACCOUNT_ID_HML}}'
        AWS_ACCOUNT_ID_PROD = '{{AWS_ACCOUNT_ID_PROD}}'

        // EKS Cluster Names
        EKS_CLUSTER_DEV  = '{{EKS_CLUSTER_DEV}}'
        EKS_CLUSTER_HML  = '{{EKS_CLUSTER_HML}}'
        EKS_CLUSTER_PROD = '{{EKS_CLUSTER_PROD}}'

        // ID das credenciais Git no Jenkins — substitua pelos ID configurado no seu Jenkins
        CREDENTIAL_ID = '{{CREDENTIAL_ID}}'

        // Variáveis dinâmicas baseadas no ambiente selecionado
        AWS_ACCOUNT_ID   = "${params.ENVIRONMENT == 'dev' ? env.AWS_ACCOUNT_ID_DEV : params.ENVIRONMENT == 'hml' ? env.AWS_ACCOUNT_ID_HML : env.AWS_ACCOUNT_ID_PROD}"
        EKS_CLUSTER_NAME = "${params.ENVIRONMENT == 'dev' ? env.EKS_CLUSTER_DEV  : params.ENVIRONMENT == 'hml' ? env.EKS_CLUSTER_HML  : env.EKS_CLUSTER_PROD}"

        // Configuração do serviço
        ECR_REPO_NAME      = '{{ECR_REPO_NAME}}'
        K8S_DEPLOYMENT_NAME = '{{PROJECT_NAME}}'

        // URI completa da imagem Docker
        FULL_IMAGE_URI = "${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_DEFAULT_REGION}.amazonaws.com/${params.ECR_REPO_NAME}:${params.IMAGE_TAG}"

        // Caminho para a shared library principal
        PATH_SHARED_LIBRARIES = 'pipeline/shared-libraries/pipeline-ms-default.groovy'

        // Caminho do Dockerfile dentro do repositório da aplicação
        PATH_DOCKERFILE = 'Dockerfile'
    }

    stages {
        stage('Remote Pipeline') {
            steps {
                script {
                    def remotePipeline = load "${PATH_SHARED_LIBRARIES}"
                    remotePipeline.remoteStages(params, env)
                }
            }
        }
    }

    post {
        always {
            echo "✅ Pipeline execution completed!"
        }

        success {
            container("devops") {
                script {
                    echo "✅ Deployment completed successfully!"
                    echo "🚀 Image deployed: ${FULL_IMAGE_URI}"
                    echo "🎯 Environment: ${params.ENVIRONMENT}"
                    echo "📦 Service: ${params.SERVICE}"
                }
            }
        }

        failure {
            container("devops") {
                script {
                    echo "❌ Deployment failed!"
                    echo "🔍 Check the logs above for error details"

                    sh """
                        echo "Getting recent pod logs for debugging..."
                        aws eks update-kubeconfig --region ${AWS_DEFAULT_REGION} --name ${EKS_CLUSTER_NAME}
                        kubectl logs -l app=${K8S_DEPLOYMENT_NAME} -n ${K8S_NAMESPACE} --tail=50 || true
                        kubectl describe deployment ${K8S_DEPLOYMENT_NAME} -n ${K8S_NAMESPACE} || true
                    """
                }
            }
        }
    }
}