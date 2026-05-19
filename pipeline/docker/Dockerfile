FROM public.ecr.aws/docker/library/ubuntu:24.04

RUN apt-get update && apt-get install -y \
    curl \
    zip \
    unzip \
    gnupg \
    ca-certificates \
    lsb-release \
    software-properties-common \
    apt-transport-https \
    git \
    docker.io \
    wget \
    openjdk-11-jre-headless \
    maven \
    python3-pip \
    python3-venv \
    qemu-user-static \
    binfmt-support \
    && rm -rf /var/lib/apt/lists/*

# Habilitar BuildKit e recursos avançados
ENV DOCKER_BUILDKIT=1
ENV COMPOSE_DOCKER_CLI_BUILD=1
ENV BUILDKIT_PROGRESS=plain

# Instalar docker buildx
ARG BUILDX_VERSION=v0.32.1
RUN mkdir -p /usr/local/lib/docker/cli-plugins && \
    curl -fsSL "https://github.com/docker/buildx/releases/download/${BUILDX_VERSION}/buildx-${BUILDX_VERSION}.linux-amd64" \
    -o /usr/local/lib/docker/cli-plugins/docker-buildx && \
    chmod +x /usr/local/lib/docker/cli-plugins/docker-buildx

# Configurar buildx como builder padrão e criar configuração avançada
RUN docker buildx install 2>/dev/null || true && \
    mkdir -p /etc/buildkit && \
    echo '[worker.oci]' > /etc/buildkit/buildkitd.toml && \
    echo '  max-parallelism = 4' >> /etc/buildkit/buildkitd.toml && \
    echo '[worker.containerd]' >> /etc/buildkit/buildkitd.toml && \
    echo '  enabled = true' >> /etc/buildkit/buildkitd.toml && \
    echo '  platforms = ["linux/amd64", "linux/arm64"]' >> /etc/buildkit/buildkitd.toml && \
    echo '[registry."docker.io"]' >> /etc/buildkit/buildkitd.toml && \
    echo '  mirrors = ["mirror.gcr.io"]' >> /etc/buildkit/buildkitd.toml

# Instalar AWS CLI v2
RUN curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o "/tmp/awscliv2.zip" && \
    unzip /tmp/awscliv2.zip -d /tmp && \
    /tmp/aws/install && \
    rm -rf /tmp/aws*

# Instalar Terraform
ARG TERRAFORM_VERSION=1.14.7
RUN curl -fsSL "https://releases.hashicorp.com/terraform/${TERRAFORM_VERSION}/terraform_${TERRAFORM_VERSION}_linux_amd64.zip" -o terraform.zip && \
    unzip terraform.zip && \
    mv terraform /usr/local/bin/ && \
    rm terraform.zip

# Instalar kubectl 
RUN curl -kLO "https://dl.k8s.io/release/$(curl -L -s https://dl.k8s.io/release/stable.txt)/bin/linux/amd64/kubectl" && \
    install -o root -g root -m 0755 kubectl /usr/local/bin/kubectl && \
    rm kubectl

# Instalar NVM e Node.js
ARG NVM_VERSION=v0.40.4
ENV NVM_DIR /usr/local/nvm

RUN mkdir -p ${NVM_DIR} && \
    curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/${NVM_VERSION}/install.sh | NVM_DIR=${NVM_DIR} bash && \
    . ${NVM_DIR}/nvm.sh && \
    nvm install 20 && \
    nvm install 22 && \
    nvm install 24 && \
    nvm alias default 20 && \
    DEFAULT_NODE_VERSION=$(nvm version default) && \
    ln -s "${NVM_DIR}/versions/node/${DEFAULT_NODE_VERSION}/bin/node" /usr/local/bin/node && \
    ln -s "${NVM_DIR}/versions/node/${DEFAULT_NODE_VERSION}/bin/npm" /usr/local/bin/npm && \
    ln -s "${NVM_DIR}/versions/node/${DEFAULT_NODE_VERSION}/bin/npx" /usr/local/bin/npx && \
    chmod -R go+rX ${NVM_DIR}

RUN echo "export NVM_DIR=${NVM_DIR}" > /etc/profile.d/nvm.sh && \
    echo '[ -s "${NVM_DIR}/nvm.sh" ] && . "${NVM_DIR}/nvm.sh" --no-use' >> /etc/profile.d/nvm.sh && \
    chmod a+r /etc/profile.d/nvm.sh

# Instalar SonarScanner
ARG SONAR_SCANNER_VERSION=8.0.1.6346
RUN wget --no-check-certificate https://binaries.sonarsource.com/Distribution/sonar-scanner-cli/sonar-scanner-cli-${SONAR_SCANNER_VERSION}-linux-x64.zip -O /tmp/sonar-scanner.zip \
    && unzip -o /tmp/sonar-scanner.zip -d /opt \
    && ln -sf /opt/sonar-scanner-${SONAR_SCANNER_VERSION}-linux-x64/bin/sonar-scanner /usr/local/bin/sonar-scanner \
    && rm /tmp/sonar-scanner.zip

# Instalar Semgrep (SAST)
ARG SEMGREP_VERSION=1.156.0
RUN pip install --no-cache-dir --break-system-packages semgrep==${SEMGREP_VERSION}

WORKDIR /root

CMD ["/bin/bash"]