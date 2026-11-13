#!/usr/bin/env python3
"""Origin E2E on local Anvil + existing isolated emulator. Never targets a phone.

Prerequisites: forge build --root protocol, isolated app/test APKs installed,
Foundry (anvil/cast), Android SDK, emulator-5554 running. Public fixture only.
"""
import json
import os
from pathlib import Path
import shutil
import sys
import subprocess
import tempfile
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
SDK = Path(os.environ.get("ANDROID_HOME", Path.home() / "Android/Sdk"))
ADB = [str(SDK / "platform-tools/adb"), "-s", "emulator-5554"]
APP = "com.caedralpay.wallet.validation"
TOKEN = "0x337610d27c682E347C9cD60BD4b3b107C9d34dDd"
PHRASE = " ".join(["abandon"] * 11 + ["about"])


def binary(name):
    return shutil.which(name) or str(Path.home() / ".foundry/bin" / name)


def cast(*args):
    return subprocess.check_output([binary("cast"), *args], text=True).strip()


def rpc(method, params):
    body = json.dumps({"jsonrpc": "2.0", "id": 1, "method": method, "params": params}).encode()
    request = urllib.request.Request("http://127.0.0.1:18797", data=body,
                                     headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=10) as response:
        result = json.load(response)
    if "error" in result:
        raise RuntimeError("Local RPC rejected " + method)
    return result["result"]


def mine_transaction(transaction):
    tx_hash = rpc("eth_sendTransaction", [transaction])
    for _ in range(50):
        receipt = rpc("eth_getTransactionReceipt", [tx_hash])
        if receipt:
            if int(receipt["status"], 16) != 1:
                raise RuntimeError("Local setup transaction reverted")
            return receipt
        time.sleep(.1)
    raise RuntimeError("Local setup receipt unavailable")


def main():
    keeper_only = "--keeper-only" in sys.argv
    # Refuse to borrow an existing RPC, even if it claims chain 97.
    try:
        rpc("eth_chainId", [])
    except Exception:
        pass
    else:
        raise RuntimeError("Port 18797 is already in use")
    with tempfile.TemporaryDirectory(prefix="origin-evm-") as directory:
        with open(Path(directory) / "anvil.log", "w") as log:
            node = subprocess.Popen([binary("anvil"), "--host", "127.0.0.1", "--port", "18797",
                                     "--chain-id", "97", "--silent"], stdout=log, stderr=log)
            try:
                for _ in range(50):
                    try:
                        if rpc("eth_chainId", []) == "0x61":
                            break
                    except Exception:
                        time.sleep(.1)
                token = json.loads((ROOT / "protocol/out/CaedralOriginPayments.t.sol/OriginToken.json").read_text())
                rpc("anvil_setCode", [TOKEN, token["deployedBytecode"]["object"]])
                payer, merchant = [cast("wallet", "address", "--mnemonic", PHRASE,
                                        "--mnemonic-index", str(i)) for i in range(2)]
                for address in (payer, merchant):
                    rpc("anvil_setBalance", [address, hex(1000 * 10**18)])
                deployer = rpc("eth_accounts", [])[0]
                mine_transaction({"from": deployer, "to": TOKEN,
                                  "data": cast("calldata", "mint(address,uint256)", payer, str(1000 * 10**18))})
                artifact = json.loads((ROOT / "protocol/out/CaedralOriginPayments.sol/CaedralOriginPayments.json").read_text())
                constructor = deployer[2:].lower().zfill(64) + TOKEN[2:].lower().zfill(64)
                deployed = mine_transaction({"from": deployer, "data": artifact["bytecode"]["object"] + constructor})
                address = deployed["contractAddress"]
                config = {"address": address, "runtimeHash": cast("keccak", rpc("eth_getCode", [address, "latest"]))}
                rec = json.loads((ROOT / "protocol/out/CaedralRecurringPayments.sol/CaedralRecurringPayments.json").read_text())
                settlement = mine_transaction({"from": deployer, "data": rec["bytecode"]["object"] + constructor})["contractAddress"]
                account = json.loads((ROOT / "protocol/out/CaedralRecurringPayments.sol/CaedralRecurringAccount.json").read_text())
                delegate = mine_transaction({"from": deployer, "data": account["bytecode"]["object"] + settlement[2:].lower().zfill(64) + TOKEN[2:].lower().zfill(64)})["contractAddress"]
                config["recurring"] = {"address": settlement, "hash": cast("keccak", rpc("eth_getCode", [settlement,"latest"])), "account": delegate, "accountHash": cast("keccak", rpc("eth_getCode", [delegate,"latest"]))}
                if not keeper_only:
                    subprocess.run(ADB + ["reverse", "tcp:18797", "tcp:18797"], check=True, capture_output=True)
                    subprocess.run(ADB + ["shell", f"run-as {APP} sh -c 'cat > files/origin-local-fixture.json'"],
                                   input=json.dumps(config).encode(), check=True, capture_output=True)
                    result = subprocess.run(ADB + ["shell", "am", "instrument", "-w", "-e", "originLocal", "true",
                                            "-e", "class", "com.caedralpay.wallet.OriginIntegrationTest,com.caedralpay.wallet.RecurringIntegrationTest",
                                            APP + ".test/androidx.test.runner.AndroidJUnitRunner"],
                                            check=True, timeout=360, capture_output=True, text=True)
                    print(result.stdout)
                    if "OK (3 tests)" not in result.stdout or "FAILURES!!!" in result.stdout:
                        raise RuntimeError("Origin E2E failed")
                fixture = Path(directory) / "fixture.json"
                fixture.write_text(json.dumps(config))
                subprocess.run(["node", str(ROOT / "qa/recurring-keeper-test.mjs"), str(fixture)], check=True, timeout=90)
            finally:
                if not keeper_only:
                    subprocess.run(ADB + ["shell", "run-as", APP, "rm", "-f", "files/origin-local-fixture.json"], capture_output=True)
                    subprocess.run(ADB + ["reverse", "--remove", "tcp:18797"], capture_output=True)
                node.terminate()
                node.wait(timeout=10)


if __name__ == "__main__":
    main()
