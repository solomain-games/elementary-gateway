package com.solomain.elementary.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;

import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

/**
 * Маршрутизация шлюза. Вместо настоящих сервисов подняты заглушки на Reactor Netty:
 * они отвечают, кто они и какой запрос получили. Так видно, куда шлюз отправил запрос
 * и не исказил ли он путь, параметры и заголовки.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")   // CORS для :5173 настроен в профиле local
class GatewayRoutingTest {

    private static final String FRONTEND = "http://localhost:5173";

    // Заглушки стартуют до контекста Spring: их порты нужны в @DynamicPropertySource
    private static final DisposableServer USER_SERVICE = HttpServer.create()
            .host("localhost").port(0)
            .route(routes -> routes.route(request -> true, (req, res) -> echo("user-service", req, res)))
            .bindNow();

    private static final DisposableServer GAME_SERVICE = HttpServer.create()
            .host("localhost").port(0)
            .route(routes -> routes
                    // WebSocket-эхо: на каждое сообщение отвечает "game-service: <текст>"
                    .ws("/ws", (in, out) -> out.sendString(
                            in.receive().asString().map(text -> "game-service: " + text)))
                    .route(request -> true, (req, res) -> echo("game-service", req, res)))
            .bindNow();

    @DynamicPropertySource
    static void services(DynamicPropertyRegistry registry) {
        registry.add("elementary.services.user-service", () -> "http://localhost:" + USER_SERVICE.port());
        registry.add("elementary.services.game-service", () -> "http://localhost:" + GAME_SERVICE.port());
    }

    @AfterAll
    static void stopServices() {
        USER_SERVICE.disposeNow();
        GAME_SERVICE.disposeNow();
    }

    @Value("${local.server.port}")
    private int port;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void authRequestsGoToUserService() {
        client.post().uri("/api/auth/guest")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"name\":\"Ватсон\"}")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("user-service POST /api/auth/guest");
    }

    @Test
    void gameRequestsGoToGameServiceWithQueryAndToken() {
        client.get().uri("/api/game/rooms/K7Q2PX?details=true")
                .header(HttpHeaders.AUTHORIZATION, "Bearer test-token")
                .exchange()
                .expectStatus().isOk()
                // Токен шлюз не проверяет и не трогает: это сделает сам game-service (Г2)
                .expectHeader().valueEquals("X-Echo-Authorization", "Bearer test-token")
                .expectBody(String.class).isEqualTo("game-service GET /api/game/rooms/K7Q2PX?details=true");
    }

    @Test
    void unknownPathsAreNotForwarded() {
        client.get().uri("/api/unknown").exchange().expectStatus().isNotFound();
        // Ключ JWKS нужен только сервисам внутри сети, наружу шлюз его не отдаёт
        client.get().uri("/.well-known/jwks.json").exchange().expectStatus().isNotFound();
    }

    @Test
    void webSocketIsProxiedToGameService() {
        var reply = new AtomicReference<String>();

        new ReactorNettyWebSocketClient()
                .execute(URI.create("ws://localhost:" + port + "/ws"), session -> {
                    Mono<Void> send = session.send(Mono.just(session.textMessage("ping")));
                    Mono<Void> receive = session.receive()
                            .map(WebSocketMessage::getPayloadAsText)
                            .next()
                            .doOnNext(reply::set)
                            .then();
                    // Слушаем и отправляем одновременно, чтобы не пропустить быстрый ответ
                    return Mono.when(send, receive);
                })
                .block(Duration.ofSeconds(5));

        assertThat(reply.get()).isEqualTo("game-service: ping");
    }

    @Test
    void corsPreflightFromFrontendIsAllowed() {
        client.options().uri("/api/auth/guest")
                .header(HttpHeaders.ORIGIN, FRONTEND)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type, authorization")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, FRONTEND);
    }

    @Test
    void corsResponseExposesLocationToFrontend() {
        client.get().uri("/api/game/rooms/K7Q2PX")
                .header(HttpHeaders.ORIGIN, FRONTEND)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, FRONTEND)
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, "Location");
    }

    @Test
    void corsFromForeignOriginIsRejected() {
        client.options().uri("/api/auth/guest")
                .header(HttpHeaders.ORIGIN, "https://evil.example")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .exchange()
                .expectStatus().isForbidden();
    }

    private static Publisher<Void> echo(String service, HttpServerRequest req, HttpServerResponse res) {
        String auth = req.requestHeaders().get(HttpHeaders.AUTHORIZATION);
        return res.header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN_VALUE)
                .header("X-Echo-Authorization", auth == null ? "none" : auth)
                .sendString(Mono.just(service + " " + req.method().name() + " " + req.uri()));
    }
}
