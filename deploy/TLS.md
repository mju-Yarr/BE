# TLS 배포 가이드

## 전제 조건

- 가비아 서버 (<서버 IP>)에 SSH 접근 가능
- 도메인이 해당 IP로 DNS A 레코드 설정됨
- Docker Compose가 설치됨

## 현재 상태 (TLS 미적용)

```
http://<서버 IP>  →  nginx(80)  →  api(8080)
```

JWT, 비밀번호 등 인증 데이터가 평문 전송됨.

## TLS 적용 후

```
https://api.ensom.app  →  nginx(443, TLS)  →  api(8080)
http://api.ensom.app   →  301 redirect   →  https://
```

## 적용 방법

### 1. 도메인 DNS 설정

가비아 DNS에서 A 레코드 추가:
```
api.ensom.app  →  <서버 IP>
```

### 2. 서버에서 스크립트 실행

운영 Compose에는 80/443 포트와 인증서·ACME webroot 볼륨이 선언되어 있다. 스크립트는 nginx를 중지하지 않고 webroot 방식으로 최초 인증서를 발급하고, 생성한 TLS 설정에 `nginx -t`를 통과시킨 뒤 reload한다. 발급 또는 설정 검증이 실패하면 기존 HTTP nginx는 계속 동작하며, 설정을 바꾼 뒤 실패한 경우에는 자동으로 원복한다.

```bash
ssh <사용자>@<서버 IP>
cd /path/to/project
chmod +x deploy/setup-tls.sh
CERTBOT_EMAIL=admin@ensom.app ./deploy/setup-tls.sh api.ensom.app
```

### 3. 자동 갱신

최초 발급과 갱신 모두 `/var/www/certbot` webroot를 사용한다. 스크립트는 인증서 갱신 성공 시에만 nginx를 reload하는 deploy hook을 설치하고, `certbot.timer`를 활성화한다. systemd timer를 사용할 수 없는 환경에서는 `/etc/cron.d/ensom-certbot-renew`를 생성한다.

```bash
systemctl status certbot.timer
certbot renew --dry-run
```

`--dry-run`과 실제 갱신 중에도 nginx는 80/443 포트를 계속 점유하며 서비스 중단이 발생하지 않는다.

## 도메인 없이 IP만 사용하는 경우

Let's Encrypt는 일반적인 IP 주소에 인증서를 발급하지 않는다. 공개 운영 환경에서는 DNS 도메인을 준비해야 하며, 자체 서명 인증서는 개발·내부 테스트에만 사용한다.

Cloudflare 같은 CDN/프록시를 도입한다면 **Flexible SSL은 사용하지 않는다**. origin에도 유효한 인증서를 설치한 **Full (strict)** 모드만 사용해야 한다. 또한 현재 rate-limit 키는 CDN/LB 없는 직접 접속을 전제로 `$binary_remote_addr`를 사용하므로, 프록시 도입 전 신뢰할 프록시 CIDR의 `set_real_ip_from`과 올바른 `real_ip_header`를 함께 구성·검증해야 한다.

## 확인

```bash
curl -I https://api.ensom.app/health
# HTTP/2 200, Strict-Transport-Security 헤더 확인
```

## 관련

- BE 이슈 #199
- nginx/default.conf — HTTP/HTTPS 설정
- deploy/setup-tls.sh — 자동화 스크립트
