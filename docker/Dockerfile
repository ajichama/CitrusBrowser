FROM ubuntu:22.04

ENV DEBIAN_FRONTEND=noninteractive

# Install OpenJDK 11 (for legacy Gradle 6.x baseline) and OpenJDK 17 (for modern Gradle 8.x / AGP 8.x)
RUN apt-get update && apt-get install -y --no-install-recommends \
    openjdk-11-jdk \
    openjdk-17-jdk \
    curl \
    unzip \
    git \
    ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# Default to Java 17 (can be overridden via JAVA_HOME env var for legacy baseline builds)
ENV JAVA_HOME_11=/usr/lib/jvm/java-11-openjdk-amd64
ENV JAVA_HOME_17=/usr/lib/jvm/java-17-openjdk-amd64
ENV JAVA_HOME=${JAVA_HOME_17}

# Setup Android SDK
ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_SDK_ROOT=${ANDROID_HOME}
ENV PATH="${JAVA_HOME}/bin:${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${PATH}"

RUN mkdir -p ${ANDROID_HOME}/cmdline-tools && \
    curl -fsSL https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -o /tmp/cmdtools.zip && \
    unzip -q /tmp/cmdtools.zip -d ${ANDROID_HOME}/cmdline-tools && \
    mv ${ANDROID_HOME}/cmdline-tools/cmdline-tools ${ANDROID_HOME}/cmdline-tools/latest && \
    rm /tmp/cmdtools.zip

# Accept SDK licenses and install both baseline (API 30) and modern (API 35 / Android 15) SDKs
RUN yes | sdkmanager --licenses > /dev/null && \
    sdkmanager \
        "platform-tools" \
        "platforms;android-30" \
        "build-tools;30.0.3" \
        "platforms;android-35" \
        "build-tools;35.0.0"

WORKDIR /workspace
