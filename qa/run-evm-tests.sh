#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
export PATH="$HOME/.foundry/bin:$HOME/Android/Sdk/platform-tools:$PATH"
if adb shell dumpsys window policy | grep -q 'showing=true'; then
    echo 'Desbloqueie o aparelho antes de executar testes com Android Keystore.' >&2
    exit 1
fi
task_tmp=$(mktemp -d)
node56_pid=''
node97_pid=''
cleanup() {
    if [[ -n "$node56_pid" ]]; then kill "$node56_pid" 2>/dev/null || true; fi
    if [[ -n "$node97_pid" ]]; then kill "$node97_pid" 2>/dev/null || true; fi
    adb reverse --remove tcp:18556 >/dev/null 2>&1 || true
    adb reverse --remove tcp:18597 >/dev/null 2>&1 || true
    rm -rf "$task_tmp"
}
trap cleanup EXIT
forge build --root qa/evm
anvil --host 127.0.0.1 --port 18556 --chain-id 56 --silent >"$task_tmp/56.log" 2>&1 &
node56_pid=$!
anvil --host 127.0.0.1 --port 18597 --chain-id 97 --silent >"$task_tmp/97.log" 2>&1 &
node97_pid=$!
for port in 18556 18597; do
    for attempt in $(seq 1 30); do
        if cast block-number --rpc-url "http://127.0.0.1:$port" >/dev/null 2>&1; then break; fi
        sleep 0.1
    done
done
fixture_address=0x9858EfFD232B4033E47d90003D41EC34EcaEda94
dev_sender=0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266
runtime=$(python3 -c 'import json; print(json.load(open("qa/evm/out/BetaTestToken.sol/BetaTestToken.json"))["deployedBytecode"]["object"])')
for port in 18556 18597; do
    rpc_url="http://127.0.0.1:$port"
    if [[ "$port" == 18556 ]]; then contract=0x55d398326f99059fF775485246999027B3197955; else contract=0x337610d27c682E347C9cD60BD4b3b107C9d34dDd; fi
    cast rpc --rpc-url "$rpc_url" anvil_setCode "$contract" "$runtime" >/dev/null
    cast rpc --rpc-url "$rpc_url" anvil_setBalance "$fixture_address" 0x3635c9adc5dea00000 >/dev/null
    cast send --rpc-url "$rpc_url" --unlocked --from "$dev_sender" "$contract" 'mint(address,uint256)' "$fixture_address" 1000000000000000000000 >/dev/null
done
adb reverse tcp:18556 tcp:18556
adb reverse tcp:18597 tcp:18597
./gradlew --offline --no-configuration-cache -PcaedralIsolatedUiTest=true \
    -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
    -Pandroid.testInstrumentationRunnerArguments.caedralLocalEvm=true \
    -Pandroid.testInstrumentationRunnerArguments.class=com.caedralpay.wallet.EvmEndToEndTest :app:connectedDebugAndroidTest
