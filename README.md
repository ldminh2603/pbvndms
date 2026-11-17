# WebCoreJava

Solution riêng cho phần Java của WebCore, tách từ `50.web/Runtime`.

| Thư mục | Công nghệ | Mô tả |
|---|---|---|
| `WebCore.SpringApi` | Spring Boot 4.1, Java 21, Maven | Backend API |
| `WebCore.ClientNew` | `@adminlte/react` (tsup) + `cbs` (Next.js 16, React 19), pnpm | Thư viện UI và ứng dụng demo |

Mở solution trong VS Code: `File > Open Workspace from File...` → `WebCoreJava.code-workspace`.

## Yêu cầu

- JDK 21, Maven 3.9+
- Node.js LTS, pnpm (`npm install -g pnpm`)
- PostgreSQL (database `web_core`)

## Chạy Spring API

```powershell
cd WebCore.SpringApi

$env:WEBCORE_SERVER_PORT = '51030'
$env:WEBCORE_DATABASE_PROVIDER = 'POSTGRES'
$env:WEBCORE_DATABASE_CONNECTION_STRING = 'Host=127.0.0.1;Port=5432;Database=web_core;Username=app_user;Password=<password>;Search Path=core,public;Timeout=120;Command Timeout=120;Pooling=true;'
$env:WEBCORE_CORS_ALLOWED_ORIGINS = 'http://localhost:3001,http://localhost:3005,http://localhost:5173'
$env:WEBCORE_AUTH_COOKIE_SECURE = 'false'
$env:WEBCORE_AUTH_COOKIE_SAME_SITE = 'Lax'

mvn spring-boot:run
```

Kiểm tra: `Invoke-RestMethod http://localhost:51030/health/ready`

Chi tiết biến môi trường: [WebCore.SpringApi/docs/database-connectivity.md](WebCore.SpringApi/docs/database-connectivity.md).

## Chạy ClientNew

```powershell
cd WebCore.ClientNew
pnpm install
pnpm build      # build thư viện @adminlte/react vào dist/
pnpm cbs        # chạy app Next.js cbs ở http://localhost:3001
```
