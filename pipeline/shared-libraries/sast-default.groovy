def remoteSast(params, env, prod_type, sast_config = [:]) {
    def scan_dir    = sast_config.get('scan_dir',    prod_type == 5 ? '.' : params.SERVICE)
    def report_path = sast_config.get('report_path', prod_type == 4 ? "${params.SERVICE}/semgrep-report.json" : "semgrep-report.json")

    stage('SAST Scanner') {
        container("devops") {
            script {
                try {
                    sh "semgrep scan --config auto --json ${scan_dir} > ${report_path}"
                } catch (Exception e) {
                    error("SAST analysis failed: ${e.message}")
                }
            }
        }
    }

    stage('Upload DefectDojo') {
        container("devops") {
            script {
                withCredentials([string(credentialsId: 'defectdojo-token', variable: 'defectdojo_token')]) {
                    try {
                        sh """
                            curl -s -X POST "https://{{DEFECTDOJO_HOST}}/api/v2/products/" \
                                -H "Content-Type: application/json" \
                                -H "Authorization: Token ${defectdojo_token}" \
                                --data '{
                                    "name": "${params.SERVICE}",
                                    "description": "${params.SERVICE}",
                                    "prod_type": ${prod_type}
                                }' || true
                        """
                    } finally {
                        try {
                            sh """
                                curl -X POST "https://{{DEFECTDOJO_HOST}}/api/v2/reimport-scan/" \
                                    -H "Authorization: Token ${defectdojo_token}" \
                                    -F "scan_type=Semgrep JSON Report" \
                                    -F "product_name=${params.SERVICE}" \
                                    -F "engagement_name=semgrep-scan-${params.SERVICE}" \
                                    -F "file=@${report_path}" \
                                    -F "skip_duplicates=true" \
                                    -F "active=true" \
                                    -F "verified=true" \
                                    -F "auto_create_context=true" \
                                    -F "close_old_findings=true" \
                                    -F "deduplication_on_engagement=true"
                            """
                        } catch (Exception e) {
                            echo "DefectDojo upload failed: ${e}"
                        }
                    }
                }
            }
        }
    }

    stage('SAST Quality Gate') {
        container("devops") {
            script {
                try {
                    def report = readJSON(file: report_path)
                    def counts = [
                        high:   report.results.count { it.extra.severity == 'ERROR'   },
                        medium: report.results.count { it.extra.severity == 'WARNING' },
                        low:    report.results.count { it.extra.severity == 'INFO'    }
                    ]

                    echo "SAST Results — High: ${counts.high} | Medium: ${counts.medium} | Low: ${counts.low}"

                    counts.each { severity, count ->
                        def limit = sast_config.get(severity, 999)
                        if (count > limit) {
                            error("SAST Quality Gate failed: ${severity} vulnerabilities ${count} > limit ${limit}")
                        }
                    }

                    echo "SAST Quality Gate passed!"
                } catch (Exception e) {
                    error("SAST Quality Gate failed: ${e.message}")
                }
            }
        }
    }
}

return this