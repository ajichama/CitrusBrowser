#!/usr/bin/env bash
# エミュレーター専用の独立 Docker Engine を WSL Ubuntu 内で管理する補助スクリプト。
# Docker Desktop の daemon・設定・Named Volume には一切触れず、
# 既定パス (/var/run/docker.sock, /var/lib/docker) と systemd の docker.service も使わない。
#   install : 静的バイナリを /opt/citrus-emu/docker へ展開する（SHA-256 固定）
#   run     : dockerd を前面実行する（Windows 側の wsl.exe が生存中だけ動く）
#   stop    : dockerd を停止する
#   status  : 導入・起動状態を表示する
# 実行には root 権限が必要（wsl -d Ubuntu -u root -- bash ... で呼び出す）。
set -euo pipefail

DOCKER_VERSION="29.10.0"
DOCKER_SHA256="7c1ebadc65c716cdc6d35fc78759ed0e4317debf43c21dd12f066f2006999def"
PREFIX="/opt/citrus-emu"
BIN_DIR="${PREFIX}/docker"
RUN_DIR="/run/citrus-emu"
SOCKET="${RUN_DIR}/docker.sock"
PID_FILE="${RUN_DIR}/dockerd.pid"
DATA_ROOT="/var/lib/citrus-emu-docker"
EXEC_ROOT="/run/citrus-emu-docker"
LOG_FILE="/var/log/citrus-emu-dockerd.log"

require_root() {
    if [ "$(id -u)" -ne 0 ]; then
        echo "root 権限で実行してください（wsl -d Ubuntu -u root）。" >&2
        exit 1
    fi
}

cmd_install() {
    require_root
    if [ -x "${BIN_DIR}/dockerd" ] && "${BIN_DIR}/dockerd" --version | grep -q "${DOCKER_VERSION}"; then
        echo "導入済み: $(${BIN_DIR}/dockerd --version)"
        return 0
    fi
    INSTALL_TMP="$(mktemp -d)"
    trap 'rm -rf "${INSTALL_TMP:-}"' EXIT
    curl -fsSL "https://download.docker.com/linux/static/stable/x86_64/docker-${DOCKER_VERSION}.tgz" -o "${INSTALL_TMP}/docker.tgz"
    echo "${DOCKER_SHA256}  ${INSTALL_TMP}/docker.tgz" | sha256sum -c -
    mkdir -p "${PREFIX}"
    rm -rf "${BIN_DIR}"
    tar -xzf "${INSTALL_TMP}/docker.tgz" -C "${PREFIX}"
    chown -R root:root "${BIN_DIR}"
    echo "導入完了: $(${BIN_DIR}/dockerd --version)"
}

is_running() {
    ENGINE_PID=""
    [ -f "${PID_FILE}" ] || return 1
    local pid executable
    # 存在確認の直後に dockerd が正常終了して PID ファイルを消すことがある。その場合は停止済みとして扱い、
    # ファイルが残っているのに中身が不正な場合だけ操作を拒否する。
    pid="$(cat "${PID_FILE}" 2>/dev/null)" || return 1
    if [ -z "${pid}" ] && [ ! -e "${PID_FILE}" ]; then
        return 1
    fi
    if [[ ! "${pid}" =~ ^[1-9][0-9]*$ ]] || [ "${pid}" = 1 ]; then
        echo "専用 dockerd の PID ファイルが不正です。操作を拒否します。" >&2
        exit 2
    fi
    kill -0 "${pid}" 2>/dev/null || return 1
    executable="$(readlink -f "/proc/${pid}/exe" 2>/dev/null)" || executable=""
    if [ "${executable}" != "${BIN_DIR}/dockerd" ]; then
        echo "PID ${pid} は専用 dockerd ではありません。操作を拒否します。" >&2
        exit 2
    fi
    local -a arguments expected
    if ! mapfile -d '' -t arguments < "/proc/${pid}/cmdline"; then
        echo "PID ${pid} の起動引数を確認できません。操作を拒否します。" >&2
        exit 2
    fi
    expected=(--host "unix://${SOCKET}" --data-root "${DATA_ROOT}" --pidfile "${PID_FILE}")
    local pair index count value
    for ((pair = 0; pair < ${#expected[@]}; pair += 2)); do
        count=0
        for ((index = 1; index < ${#arguments[@]}; index++)); do
            case "${arguments[index]}" in
                "${expected[pair]}") value="${arguments[index + 1]:-}" ;;
                "${expected[pair]}="*) value="${arguments[index]#*=}" ;;
                *) continue ;;
            esac
            count=$((count + 1))
            if [ "${value}" != "${expected[pair + 1]}" ]; then
                echo "PID ${pid} の ${expected[pair]} が専用設定と異なります。操作を拒否します。" >&2
                exit 2
            fi
        done
        if [ "${count}" -ne 1 ]; then
            echo "PID ${pid} の ${expected[pair]} を一意に確認できません。操作を拒否します。" >&2
            exit 2
        fi
    done
    # 検証済みの PID を使い、シグナル送信直前に別のファイル値を採用しない。
    ENGINE_PID="${pid}"
}

cmd_run() {
    require_root
    if [ ! -x "${BIN_DIR}/dockerd" ]; then
        echo "dockerd が未導入です。先に install を実行してください。" >&2
        exit 1
    fi
    if [ ! -e /dev/kvm ]; then
        echo "/dev/kvm がありません。WSL の入れ子仮想化を確認してください。" >&2
        exit 1
    fi
    if is_running; then
        echo "dockerd は起動済みです (pid ${ENGINE_PID})。"
        # wsl.exe の生存でディストリを維持するため、dockerd が終わるまで待つ。
        while is_running; do sleep 5; done
        exit 0
    fi
    mkdir -p "${RUN_DIR}" "${DATA_ROOT}" "${EXEC_ROOT}"
    chmod 700 "${RUN_DIR}"
    export PATH="${BIN_DIR}:${PATH}"
    # bridge・iptables を無効化し、共有ネットワーク名前空間のルールを変更しない。
    # コンテナは --network host でのみ起動する。
    exec "${BIN_DIR}/dockerd" \
        --host "unix://${SOCKET}" \
        --group root \
        --pidfile "${PID_FILE}" \
        --data-root "${DATA_ROOT}" \
        --exec-root "${EXEC_ROOT}" \
        --bridge none \
        --iptables=false \
        --ip6tables=false \
        --userland-proxy=false \
        >>"${LOG_FILE}" 2>&1
}

cmd_stop() {
    require_root
    if is_running; then
        kill -TERM "${ENGINE_PID}"
        for _ in $(seq 1 30); do
            is_running || { echo "dockerd を停止しました。"; return 0; }
            sleep 1
        done
        echo "dockerd が 30 秒以内に停止しませんでした。" >&2
        exit 1
    fi
    echo "dockerd は停止済みです。"
}

cmd_status() {
    if [ -x "${BIN_DIR}/dockerd" ]; then
        echo "installed=$(${BIN_DIR}/dockerd --version)"
    else
        echo "installed=no"
    fi
    if is_running; then
        echo "running=yes pid=${ENGINE_PID} socket=${SOCKET}"
    else
        echo "running=no"
    fi
}

case "${1:-}" in
    install) cmd_install ;;
    run) cmd_run ;;
    stop) cmd_stop ;;
    status) cmd_status ;;
    *) echo "使い方: $0 {install|run|stop|status}" >&2; exit 2 ;;
esac
