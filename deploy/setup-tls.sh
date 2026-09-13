#!/bin/bash
# TLS 인증서 발급 및 nginx 설정 스크립트 (가비아 서버용)
# 사용법: ssh <사용자>@<서버 IP> 후 실행
#   chmod +x deploy/setup-tls.sh
#   ./deploy/setup-tls.sh your-domain.com

set -euo pipefail

DOMAIN=${1:?"사용법: $0 <도메인명> (예: api.ensom.app)"}
CERTBOT_EMAIL=${CERTBOT_EMAIL:-admin@ensom.app}
PROJECT_DIR=$(pwd -P)
COMPOSE_FILE="$PROJECT_DIR/docker-compose.yml"
NGINX_CONFIG="$PROJECT_DIR/nginx/default.conf"
ACME_WEBROOT=/var/www/certbot

if [[ ! "$DOMAIN" =~ ^([A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?\.)+[A-Za-z]{2,63}$ ]]; then
    echo "오류: 유효한 DNS 도메인명을 입력하세요: $DOMAIN" >&2
    exit 2
fi

if [[ ! -f "$COMPOSE_FILE" || ! -f "$NGINX_CONFIG" ]]; then
    echo "오류: 프로젝트 루트에서 실행해야 합니다." >&2
    exit 2
fi

BACKUP_CONFIG=$(mktemp)
GENERATED_CONFIG=$(mktemp)
CONFIG_REPLACED=false
cleanup() {
    status=$?
    if [[ $status -ne 0 && "$CONFIG_REPLACED" == true ]]; then
        echo "TLS 설정 적용 실패 — 기존 nginx 설정을 복구합니다." >&2
        cp "$BACKUP_CONFIG" "$NGINX_CONFIG"
        docker compose -f "$COMPOSE_FILE" exec -T nginx nginx -s reload 2>/dev/null || true
    fi
    rm -f "$BACKUP_CONFIG" "$GENERATED_CONFIG"
    exit "$status"
}
trap cleanup EXIT

echo "=== 1. certbot 설치 ==="
apt-get update -qq
apt-get install -y certbot
mkdir -p "$ACME_WEBROOT"

echo "=== 2. HTTP nginx 시작 및 webroot 인증서 발급 ==="
# nginx는 중지하지 않는다. 기본 HTTP 설정의 ACME location이 challenge 파일을 제공한다.
docker compose -f "$COMPOSE_FILE" up -d nginx
certbot certonly \
    --webroot \
    --webroot-path "$ACME_WEBROOT" \
    -d "$DOMAIN" \
    --non-interactive \
    --agree-tos \
    --keep-until-expiring \
    --email "$CERTBOT_EMAIL"

echo "=== 3. nginx TLS 설정 생성 및 검증 ==="
cat > "$GENERATED_CONFIG" <<NGINX
limit_req_zone \$binary_remote_addr zone=api:10m rate=30r/s;

upstream api {
    server api:8080;
}

server {
    listen 80;
    server_name $DOMAIN;
    server_tokens off;

    location /.well-known/acme-challenge/ {
        root /var/www/certbot;
    }

    location / {
        return 301 https://\$host\$request_uri;
    }
}

server {
    listen 443 ssl;
    server_name $DOMAIN;
    server_tokens off;

    ssl_certificate /etc/letsencrypt/live/$DOMAIN/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/$DOMAIN/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_ciphers HIGH:!aNULL:!MD5;

    client_max_body_size 10m;

    add_header X-Frame-Options "DENY" always;
    add_header X-Content-Type-Options "nosniff" always;
    add_header Referrer-Policy "strict-origin-when-cross-origin" always;
    add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;

    # 현재 배포 계약은 CDN/LB 없는 단독 서버 직접 접속이다.
    # 프록시 도입 전에는 신뢰할 CIDR과 real_ip_header를 별도로 구성해야 한다.
    location / {
        limit_req zone=api burst=60 nodelay;
        proxy_pass http://api;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_connect_timeout 10s;
        proxy_read_timeout 30s;
    }

    location /v1/ {
        limit_req zone=api burst=60 nodelay;
        proxy_pass http://api/;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_connect_timeout 10s;
        proxy_read_timeout 30s;
    }

    location = /health {
        proxy_pass http://api/actuator/health;
        proxy_set_header Host \$host;
    }
}
NGINX

cp "$NGINX_CONFIG" "$BACKUP_CONFIG"
cp "$GENERATED_CONFIG" "$NGINX_CONFIG"
CONFIG_REPLACED=true

docker compose -f "$COMPOSE_FILE" exec -T nginx nginx -t
docker compose -f "$COMPOSE_FILE" exec -T nginx nginx -s reload
CONFIG_REPLACED=false

echo "=== 4. 자동 갱신 deploy hook 설치 ==="
HOOK_DIR=/etc/letsencrypt/renewal-hooks/deploy
HOOK_FILE="$HOOK_DIR/reload-ensom-nginx.sh"
mkdir -p "$HOOK_DIR"
printf '#!/bin/bash\nset -euo pipefail\ncd %q\ndocker compose -f docker-compose.yml exec -T nginx nginx -s reload\n' \
    "$PROJECT_DIR" > "$HOOK_FILE"
chmod 700 "$HOOK_FILE"

if command -v systemctl >/dev/null 2>&1 && systemctl enable --now certbot.timer; then
    echo "certbot systemd timer를 활성화했습니다."
else
    echo '17 3 * * * root /usr/bin/certbot renew --quiet' > /etc/cron.d/ensom-certbot-renew
    chmod 644 /etc/cron.d/ensom-certbot-renew
    echo "certbot 갱신 cron을 등록했습니다."
fi

echo "=== 5. 갱신 dry-run ==="
certbot renew --dry-run

echo "TLS 설정 완료: https://$DOMAIN"
