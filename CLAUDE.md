# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a **local lifestyle review platform** (点评网站 / Dianping clone) built with:
- **Java 8** + **Spring Boot 2.7.3** + **MyBatis-Plus 3.4.3**
- **Redis** for caching with three strategies (pass-through, mutex, logical expire)
- **MySQL** database (hmdp)

## Common Commands

```bash
# Build & Run
mvn clean install              # Build project
mvn clean package              # Package as JAR
mvn spring-boot:run            # Run Spring Boot app
mvn test                       # Run tests

# Docker services (nginx on port 8080)
docker-compose up -d
```

**Configuration:** `src/main/resources/application.yaml`
- App port: `8081`
- MySQL: `127.0.0.1:3306/hmdp` (root/4190)
- Redis: `localhost:6379`

## Architecture

Standard Spring Boot layered architecture:
```
src/main/java/com/hmdp/
├── config/          # Configuration classes
├── controller/      # REST API endpoints
├── dto/             # Data Transfer Objects
├── entity/          # Database entities (MyBatis)
├── mapper/          # MyBatis mappers (DAO)
├── service/         # Business logic
│   └── impl/        # Service implementations
└── utils/           # Utility classes
```

## Key Components

### CacheClient (`utils/CacheClient.java`)
The core Redis caching utility with three strategies:

| Strategy | Method | Use Case |
|----------|--------|----------|
| **Pass-through** | `queryWithPassThrough()` | Basic queries, prevents cache penetration |
| **Mutex** | `queryWithMutex()` | Strong consistency (user data) |
| **Logical expire** | `queryWithLogicalExpire()` | Hot data, high concurrency (recommended for 90% of cases) |

**Important:** When using logical expire, pre-warm cache on startup with `@PostConstruct`:
```java
@PostConstruct
public void init() {
    cacheClient.batchWarmUpCache("cache:shop:", hotIds, Duration.ofMinutes(30), this::queryDB);
}
```

### Authentication
- `UserHolder` - ThreadLocal user context storage
- `LoginInterceptor` - Requires login for protected endpoints
- `RefreshTokenInterceptor` - Refreshes user session on each request

### Redis Constants (`RedisConstants`)
All Redis key prefixes and TTL definitions. Always use these constants instead of raw strings.

## Main Modules

| Module | Description |
|--------|-------------|
| **User** | Authentication, login/code verification, token management |
| **Shop** | Shop/merchant management with all three cache strategies |
| **ShopType** | Shop category management |
| **Blog** | Blog posts, comments, likes |
| **Voucher** | Regular vouchers + seckill vouchers |
| **VoucherOrder** | Voucher order management |
| **Follow** | User follow system |

## Development Notes

- When updating data: **update database first, then delete cache** (never reverse)
- Use Lua scripts for atomic distributed lock operations (`src/main/resources/lua/`)
- Shop entity includes x/y coordinates for geolocation features
