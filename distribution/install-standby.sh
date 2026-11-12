#!/usr/bin/env bash
# Run only on the authorized server, from a staging directory containing the
# signed candidate, release.json and these distribution files. Never approves beta.
set -euo pipefail
[[ "$EUID" == 0 ]] || { echo 'Execute no servidor como root.' >&2; exit 1; }
cd "$(dirname "$0")"
node --input-type=module - <<'JS'
import {readFile} from 'node:fs/promises';import {createHash} from 'node:crypto';
const m=JSON.parse(await readFile('./release.json','utf8'));
if(m.betaApproved!==false || !/^caedralpay-wallet-[0-9A-Za-z.-]+\.apk$/.test(m.file))throw Error('Only blocked candidates accepted');
const hash=createHash('sha256').update(await readFile(m.file)).digest('hex');
if(hash!==m.sha256)throw Error('Candidate hash mismatch');
JS
if [[ -e /etc/systemd/system/caedral-wallet-download.service || -e /opt/caedral-wallet/distribution/server.mjs ]]; then
    echo 'Instalação existente: revise a atualização; nada foi sobrescrito.' >&2; exit 1
fi
if ss -H -lnt 'sport = :8097' | grep -q .; then echo 'Porta 8097 já ocupada.' >&2; exit 1; fi
if ! id caedral-download >/dev/null 2>&1; then useradd --system --no-create-home --shell /usr/sbin/nologin caedral-download; fi
install -d -o root -g root -m 755 /opt/caedral-wallet/distribution /srv/caedral-wallet-beta
install -d -o root -g root -m 700 /etc/caedral-wallet
for file in server.mjs index.html app.js style.css privacy.html; do install -o root -g root -m 644 "$file" /opt/caedral-wallet/distribution/; done
candidate=$(node -e 'process.stdout.write(JSON.parse(require("fs").readFileSync("release.json")).file)')
install -o root -g root -m 644 "$candidate" release.json /srv/caedral-wallet-beta/
install -o root -g root -m 600 download.env.example /etc/caedral-wallet/download.env
install -o root -g root -m 644 caedral-wallet-download.service /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now caedral-wallet-download.service
curl --fail --silent --retry 5 --retry-connrefused --retry-delay 1 -H 'Host: download.caedral.com' http://127.0.0.1:8097/config.json
echo
echo 'Standby local instalado. Sem DNS/Nginx público, sem beta aprovado, sem download liberado.'
