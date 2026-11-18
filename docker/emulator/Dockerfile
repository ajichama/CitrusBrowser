# CitrusBrowser 検証用 Android エミュレーター（ヘッドレス・x86_64・KVM 必須）
# WSL Ubuntu 内の独立 Docker Engine（docker/emulator/wsl-engine.sh）で構築・実行する。
# 鍵・AVD・スナップショットはイメージへ含めず、実行時に Named Volume (/root/.android) へ作る。
FROM ubuntu:24.04

ENV DEBIAN_FRONTEND=noninteractive

# sdkmanager 用の JRE と、-no-window でもエミュレーター本体が読み込む共有ライブラリ
RUN apt-get update && apt-get install -y --no-install-recommends \
        openjdk-17-jre-headless \
        ca-certificates \
        curl \
        unzip \
        libasound2t64 \
        libbsd0 \
        libdbus-1-3 \
        libdrm2 \
        libegl1 \
        libfontconfig1 \
        libgbm1 \
        libgl1 \
        libnss3 \
        libpulse0 \
        libx11-xcb1 \
        libxcomposite1 \
        libxcursor1 \
        libxdamage1 \
        libxi6 \
        libxkbcommon-x11-0 \
        libxkbfile1 \
        libxrandr2 \
        libxtst6 \
    && rm -rf /var/lib/apt/lists/*

ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_SDK_ROOT=${ANDROID_HOME}
ENV ANDROID_EMULATOR_HOME=/root/.android
ENV ANDROID_AVD_HOME=/root/.android/avd
# 他の adb サーバー（実機用コンテナ等）と衝突しないよう既定 5037 を避ける
ENV ANDROID_ADB_SERVER_PORT=5039
ENV PATH="${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${ANDROID_HOME}/emulator:${PATH}"

# cmdline-tools 23.0。12.0 の avdmanager は android-37.0 のような小数付き API で AVD に target=android-0 を書き、
# エミュレーターが API を誤認して描画機能（GLDirectMem 等）を有効にせず SurfaceFlinger が異常終了する
# （ReactiveCircus/android-emulator-runner#482）。SHA-256 は公式 repository2-3.xml の SHA-1 照合後に算出した値。
ARG CMDLINE_TOOLS_ZIP=commandlinetools-linux-16111833_latest.zip
ARG CMDLINE_TOOLS_SHA256=0877a1d048fe4a24efe2eff536ca4223f7adeb58648bb81909d33c446918cfa8
RUN mkdir -p ${ANDROID_HOME}/cmdline-tools && \
    curl -fsSL "https://dl.google.com/android/repository/${CMDLINE_TOOLS_ZIP}" -o /tmp/cmdtools.zip && \
    echo "${CMDLINE_TOOLS_SHA256}  /tmp/cmdtools.zip" | sha256sum -c - && \
    unzip -q /tmp/cmdtools.zip -d ${ANDROID_HOME}/cmdline-tools && \
    mv ${ANDROID_HOME}/cmdline-tools/cmdline-tools ${ANDROID_HOME}/cmdline-tools/latest && \
    rm /tmp/cmdtools.zip

# 本体 targetSdk 37 に合わせた 4KB ページの Google APIs 版（既定 AVD）と、
# 最新安定版 37.2（x86_64 は 16KB ページ版のみ提供）、比較用に安定した API 36 を入れる。
# パッケージの整合性は sdkmanager がリポジトリ記載のチェックサムで検証する。
ARG SYSTEM_IMAGES="system-images;android-37.0;google_apis;x86_64 system-images;android-37.2;google_apis_ps16k;x86_64 system-images;android-36;google_apis;x86_64"
RUN yes | sdkmanager --licenses > /dev/null && \
    sdkmanager --install "platform-tools" "emulator" > /dev/null && \
    for image in ${SYSTEM_IMAGES}; do sdkmanager --install "${image}" > /dev/null; done && \
    sdkmanager --list_installed && \
    rm -rf /root/.android /root/.cache /tmp/*

COPY entrypoint.sh /usr/local/bin/citrus-emulator
RUN sed -i 's/\r$//' /usr/local/bin/citrus-emulator && chmod 755 /usr/local/bin/citrus-emulator

VOLUME ["/root/.android"]
ENTRYPOINT ["/usr/local/bin/citrus-emulator"]
