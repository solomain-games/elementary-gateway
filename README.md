# Elementary — шлюз (gateway)

Единая точка входа в бэкенд игры **Elementary** на Spring Cloud Gateway (реактивный стек WebFlux, сервер Netty). Браузер обращается только к шлюзу, шлюз направляет запросы в нужный сервис.

| Путь | Куда |
|---|---|
| `/api/auth/**` | user-service (`:8082`) |
| `/api/game/**` | game-service (`:8081`) |
| `/ws` (WebSocket) | game-service (`:8081`) |

Остальные пути шлюз не пересылает и отвечает `404`. Путь, параметры и заголовки (в том числе `Authorization`) уходят в сервис без изменений: токен шлюз не проверяет, это делают сами сервисы.

Маршруты описаны в `application.yaml`, адреса сервисов — в профилях:

| Свойство | `local` | `prod` |
|---|---|---|
| `elementary.services.user-service` | `http://localhost:8082` | `USER_SERVICE_URI` |
| `elementary.services.game-service` | `http://localhost:8081` | `GAME_SERVICE_URI` |

## CORS

В профиле `local` разрешены запросы со страницы `http://localhost:5173` (фронтенд на Vite): методы `GET` и `POST`, заголовки `Authorization` и `Content-Type`. Обычно фронтенд ходит через прокси Vite и CORS не задействован; настройка нужна, если обращаться к шлюзу `:8080` напрямую. В `prod` фронтенд и API отдаются с одного домена, CORS не настроен.

## Запуск локально

Требуется JDK 25.

```powershell
.\mvnw spring-boot:run
```

Шлюз стартует на порту **8080** с профилем `local`.

Проверка: http://localhost:8080/actuator/health → `"status": "UP"`.

Запросы через шлюз — в `http/gateway.http` (для них должны быть запущены user-service и game-service).

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
