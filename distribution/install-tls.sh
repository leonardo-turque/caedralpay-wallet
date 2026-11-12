#!/usr/bin/env bash
set -euo pipefail
[[ "$EUID" == 0 ]] || exit 1
host=${1:-download.caedral.com}
[[ "$host" =~ ^[a-z0-9]+([.-][a-z0-9]+)*\.[a-z]{2,}$ ]] || exit 1
cd "$(dirname "$0")"
target=/etc/nginx/conf.d/caedral-wallet-download.conf
if [[ -e "$target" ]] && ! head -1 "$target" | grep -q 'Managed CaedralPay wallet download'; then
    echo 'Configuração existente não reconhecida; nada foi sobrescrito.' >&2; exit 1
fi
install -d -m 755 /var/lib/caedral-wallet-acme/.well-known/acme-challenge
if [[ ! -f "/etc/letsencrypt/live/$host/fullchain.pem" ]]; then
    cat > "$target" <<EOF
# Managed CaedralPay wallet download
server {
    listen 80;
    server_name $host;
    location /.well-known/acme-challenge/ { root /var/lib/caedral-wallet-acme; }
    location / { return 503; }
}
EOF
    nginx -t
    systemctl reload nginx
    challenge="wallet-check-$(date +%s)"
    printf 'caedral-wallet-acme-check' > "/var/lib/caedral-wallet-acme/.well-known/acme-challenge/$challenge"
    local_response=$(curl --fail --silent --max-time 15 --retry 6 --retry-all-errors --retry-delay 1 --resolve "$host:80:127.0.0.1" "http://$host/.well-known/acme-challenge/$challenge")
    public_response=$(curl --fail --silent --max-time 15 --retry 6 --retry-all-errors --retry-delay 1 "http://$host/.well-known/acme-challenge/$challenge")
    rm -f "/var/lib/caedral-wallet-acme/.well-known/acme-challenge/$challenge"
    [[ "$local_response" == 'caedral-wallet-acme-check' && "$public_response" == 'caedral-wallet-acme-check' ]] || {
        echo 'Desafio HTTP não está acessível; emissão não iniciada.' >&2; exit 1
    }
    certbot certonly --webroot -w /var/lib/caedral-wallet-acme -d "$host" --cert-name "$host" --non-interactive --agree-tos
fi
if [[ ! -f "$target" ]]; then echo '# Managed CaedralPay wallet download' > "$target"; fi
backup=$(mktemp)
cp "$target" "$backup"
{
    echo '# Managed CaedralPay wallet download'
    sed "s/download.caedral.com/$host/g" nginx-download.conf
    cat <<EOF
server {
    listen 80;
    server_name $host;
    location /.well-known/acme-challenge/ { root /var/lib/caedral-wallet-acme; }
    location / { return 301 https://\$host\$request_uri; }
}
EOF
} > "$target"
if ! nginx -t; then cp "$backup" "$target"; rm -f "$backup"; exit 1; fi
rm -f "$backup"
systemctl reload nginx
curl --fail --silent --max-time 20 --resolve "$host:443:127.0.0.1" "https://$host/config.json"
echo
