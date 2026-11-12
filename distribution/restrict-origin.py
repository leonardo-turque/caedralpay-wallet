#!/usr/bin/env python3
"""Restrict only the managed wallet TLS virtual host to Cloudflare peers/loopback."""
from pathlib import Path
import ipaddress, os, re, subprocess, time

if os.geteuid()!=0:raise SystemExit('Run on the authorized origin as root.')
target=Path('/etc/nginx/conf.d/caedral-wallet-download.conf')
before=target.read_text()
if not before.startswith('# Managed CaedralPay wallet download'):raise SystemExit('Unrecognized virtual host.')
host=re.search(r'server_name ([a-z0-9.-]+);',before).group(1)
include=Path('/etc/nginx/snippets/caedral-wallet-cloudflare-peers.conf')
old_include=include.read_bytes() if include.exists() else None
networks=[]
for version in ['v4','v6']:
    result=subprocess.run(['curl','--fail','--silent','--show-error','--max-time','15','https://www.cloudflare.com/ips-'+version],
        check=True,capture_output=True,text=True,timeout=20)
    if len(result.stdout)>16384:raise SystemExit('Peer list exceeds limit.')
    networks.extend(str(ipaddress.ip_network(line.strip())) for line in result.stdout.splitlines() if line.strip())
if not 10<=len(networks)<=100:raise SystemExit('Unexpected Cloudflare peer list.')
include.parent.mkdir(parents=True,exist_ok=True)
include.write_text('allow 127.0.0.1;\nallow ::1;\n'+''.join(f'allow {network};\n' for network in networks)+'deny all;\n')
marker='include /etc/nginx/snippets/caedral-wallet-cloudflare-peers.conf;'
if marker not in before:
    # Override inherited trust lists: allow/deny evaluates the actual edge peer.
    target.write_text(before.replace('server {\n','server {\n    set_real_ip_from 127.0.0.1;\n    real_ip_header CF-Connecting-IP;\n    '+marker+'\n',1))
current=target.read_text()
current=current.replace('wallet_download_requests','wallet_download_clients_v2')
if 'satisfy all;' not in current:
    current=current.replace('server {\n','server {\n    satisfy all;\n',1)
if 'X-Caedral-Edge-Policy' not in current:
    current=current.replace('server {\n','server {\n    add_header X-Caedral-Edge-Policy "cf-peer-v2" always;\n',1)
if 'location / {\n        satisfy all;' not in current:
    current=current.replace('location / {\n','location / {\n        satisfy all;\n        '+marker+'\n',1)
target.write_text(current)
result=subprocess.run(['nginx','-t'],capture_output=True)
if result.returncode:
    target.write_text(before)
    if old_include is None:include.unlink(missing_ok=True)
    else:include.write_bytes(old_include)
    raise SystemExit('Configuration rejected; prior virtual host restored.')
subprocess.run(['systemctl','reload','nginx'],check=True)
for attempt in range(8):
    probe=subprocess.run(['curl','--fail','--silent','--max-time','5','--resolve',f'{host}:443:127.0.0.1',
        '-I',f'https://{host}/config.json'],capture_output=True,text=True)
    if probe.returncode==0 and 'x-caedral-edge-policy: cf-peer-v2' in probe.stdout.lower():break
    time.sleep(1)
else:raise SystemExit('A configuração foi escrita, mas o reload efetivo não foi confirmado.')
print('Managed wallet TLS host restricted to '+str(len(networks))+' Cloudflare networks and loopback.')
