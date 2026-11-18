#!/usr/bin/env bash
# エミュレーターコンテナの起動処理。AVD が無ければ Named Volume 上に作成し、ヘッドレスで起動する。
# 設定は環境変数で受け取る（scripts/emulator.ps1 が指定する）。
set -euo pipefail

AVD_NAME="${AVD_NAME:-citrus-api37}"
SYSTEM_IMAGE="${SYSTEM_IMAGE:-system-images;android-37.0;google_apis;x86_64}"
EMU_DEVICE="${EMU_DEVICE:-pixel_7}"
EMU_PORT="${EMU_PORT:-5570}"
EMU_MEMORY="${EMU_MEMORY:-4096}"
EMU_CORES="${EMU_CORES:-4}"
EMU_DATA_MB="${EMU_DATA_MB:-8192}"
EMU_BOOT="${EMU_BOOT:-quick}"   # quick: スナップショットから起動・終了時に保存 / cold: 毎回コールドブート
EMU_WIPE="${EMU_WIPE:-0}"       # 1: ユーザーデータを初期化して起動

if [ ! -r /dev/kvm ] || [ ! -w /dev/kvm ]; then
    echo "[citrus-emulator] /dev/kvm を読み書きできません。--device /dev/kvm を付けて起動してください。" >&2
    exit 1
fi

case "${EMU_BOOT}" in quick|cold) ;; *) echo "[citrus-emulator] EMU_BOOT は quick か cold です。" >&2; exit 2 ;; esac
for value in "${EMU_PORT}" "${EMU_MEMORY}" "${EMU_CORES}" "${EMU_DATA_MB}"; do
    [[ "${value}" =~ ^[0-9]+$ ]] || { echo "[citrus-emulator] 数値設定が不正です: ${value}" >&2; exit 2; }
done
[[ "${AVD_NAME}" =~ ^[A-Za-z0-9._-]+$ ]] || { echo "[citrus-emulator] AVD 名が不正です。" >&2; exit 2; }

mkdir -p "${ANDROID_AVD_HOME}"
avd_dir="${ANDROID_AVD_HOME}/${AVD_NAME}.avd"
image_dir="${ANDROID_HOME}/$(echo "${SYSTEM_IMAGE}" | tr ';' '/')"
if [ ! -d "${image_dir}" ]; then
    echo "[citrus-emulator] イメージに含まれない system image です: ${SYSTEM_IMAGE}" >&2
    exit 1
fi

# 旧 cmdline-tools で作った AVD は target=android-0 となり、API を誤認して描画機能が欠ける。
# その場合だけ作り直す（中身は壊れた AVD のユーザーデータのみで、鍵や他の AVD は消さない）。
avd_ini="${ANDROID_AVD_HOME}/${AVD_NAME}.ini"
if [ -f "${avd_ini}" ] && grep -q '^target=android-0$' "${avd_ini}"; then
    echo "[citrus-emulator] ${AVD_NAME} は target=android-0（旧 avdmanager 製）のため作り直します。"
    rm -rf "${avd_dir}" "${avd_ini}"
fi

if [ ! -f "${avd_ini}" ]; then
    echo "[citrus-emulator] AVD ${AVD_NAME} を作成します (${SYSTEM_IMAGE}, ${EMU_DEVICE})"
    echo no | avdmanager --silent create avd --name "${AVD_NAME}" --package "${SYSTEM_IMAGE}" --device "${EMU_DEVICE}" --force
    {
        echo "hw.ramSize=${EMU_MEMORY}"
        echo "hw.cpu.ncore=${EMU_CORES}"
        echo "disk.dataPartition.size=${EMU_DATA_MB}M"
        echo "hw.keyboard=yes"
        echo "hw.gpu.enabled=yes"
        echo "hw.audioInput=no"
        echo "hw.audioOutput=no"
    } >> "${avd_dir}/config.ini"
    if grep -q '^target=android-0$' "${avd_ini}"; then
        echo "[citrus-emulator] avdmanager が target=android-0 を書きました。cmdline-tools の版を確認してください。" >&2
        exit 1
    fi
    echo "[citrus-emulator] $(grep '^target=' "${avd_ini}")"
fi

# 保存済み AVD も現在の描画方式へ揃え、同じキーを重複させない。
if [ -f "${avd_dir}/config.ini" ]; then
    sed -i '/^[[:space:]]*hw\.gpu\.mode[[:space:]]*=/d' "${avd_dir}/config.ini"
    echo "hw.gpu.mode=swiftshader" >> "${avd_dir}/config.ini"
fi

# 前回コンテナが強制終了した場合に残るロックを除去する（このコンテナは AVD を単独で使う）。
find "${avd_dir}" -maxdepth 2 -name '*.lock' -exec rm -rf {} + 2>/dev/null || true

args=(
    -avd "${AVD_NAME}"
    -port "${EMU_PORT}"
    -no-window
    -no-audio
    -no-boot-anim
    -gpu swiftshader
    -accel on
    -memory "${EMU_MEMORY}"
    -cores "${EMU_CORES}"
    -no-metrics
)
if [ "${EMU_BOOT}" = cold ]; then
    args+=(-no-snapshot)
fi
if [ "${EMU_WIPE}" = 1 ]; then
    args+=(-wipe-data)
fi

adb start-server >/dev/null 2>&1 || true
echo "[citrus-emulator] 起動: emulator ${args[*]}"
exec emulator "${args[@]}"
