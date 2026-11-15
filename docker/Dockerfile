FROM ubuntu:22.04

ENV DEBIAN_FRONTEND=noninteractive

# Install OpenJDK 11 (legacy baseline), OpenJDK 17, and OpenJDK 21 (modern Gradle 8/9 & AGP 8/9)
RUN apt-get update && apt-get install -y --no-install-recommends \
    openjdk-11-jdk \
    openjdk-17-jdk \
    openjdk-21-jdk \
    curl \
    unzip \
    git \
    ca-certificates \
    && rm -rf /var/lib/apt/lists/*

ENV JAVA_HOME_11=/usr/lib/jvm/java-11-openjdk-amd64
ENV JAVA_HOME_17=/usr/lib/jvm/java-17-openjdk-amd64
ENV JAVA_HOME_21=/usr/lib/jvm/java-21-openjdk-amd64
ENV JAVA_HOME=${JAVA_HOME_21}

# Setup Android SDK
ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_SDK_ROOT=${ANDROID_HOME}
ENV PATH="${JAVA_HOME}/bin:${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${PATH}"

RUN mkdir -p ${ANDROID_HOME}/cmdline-tools && \
    curl -fsSL https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -o /tmp/cmdtools.zip && \
    unzip -q /tmp/cmdtools.zip -d ${ANDROID_HOME}/cmdline-tools && \
    mv ${ANDROID_HOME}/cmdline-tools/cmdline-tools ${ANDROID_HOME}/cmdline-tools/latest && \
    rm /tmp/cmdtools.zip

# Accept SDK licenses and install baseline (API 30) + Android 16 (API 36) + Android 17 (API 37) SDKs
RUN yes | sdkmanager --licenses > /dev/null && \
    sdkmanager \
        "platform-tools" \
        "platforms;android-30" \
        "build-tools;30.0.3" \
        "build-tools;35.0.0" \
        "platforms;android-36" \
        "build-tools;36.0.0" \
        "platforms;android-37.0" \
        "platforms;android-37.2" \
        "build-tools;37.0.0"

WORKDIR /workspace
