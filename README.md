# Elementary — шлюз (gateway)

Единая точка входа в бэкенд игры **Elementary** на Spring Cloud Gateway (реактивный стек WebFlux, сервер Netty). Браузер обращается только к шлюзу, шлюз направляет запросы в нужный сервис.

| Путь | Куда | Задача |
|---|---|---|
| `/api/auth/**` | user-service (`:8082`) | Ш2 |
| `/api/game/**`, `/ws/**` | game-service (`:8081`) | Ш2 |

## Запуск локально

Требуется JDK 25.

```powershell
.\mvnw spring-boot:run
```

Шлюз стартует на порту **8080** с профилем `local`.

Проверка: http://localhost:8080/actuator/health → `"status": "UP"`.

## Профили

| Профиль | Где | Настройки |
|---|---|---|
| `local` (по умолчанию) | компьютер разработчика | `application-local.yaml` |
| `prod` | боевой сервер | `application-prod.yaml`, значения из переменных окружения |

Профиль задаётся переменной `SPRING_PROFILES_ACTIVE`.

## Сборка и тесты

```powershell
.\mvnw verify
```
