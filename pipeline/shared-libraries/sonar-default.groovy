def remoteSonar(params, env) {
    stage('Sonarqube Scanner') {
        container("devops") {
            script {
                withSonarQubeEnv('{{SONARQUBE_ENV_NAME}}') {
                    try {
                        def remoteUnitTests = load "projects/${params.SERVICE}/sonar/sonar-unit-tests.groovy"
                        remoteUnitTests.remoteUnitTest(params, env)
                    } catch (Exception e) {
                        echo "Unit tests not configured or failed: ${e}"
                    }

                    sh """
                        sonar-scanner \
                            -Dsonar.projectKey=${params.SERVICE} \
                            -Dsonar.sources=${params.SERVICE} \
                            -Dsonar.scm.disabled=true \
                            -Dproject.settings=${params.SERVICE}/sonar-project.properties
                    """
                }
            }
        }
    }
}

return this