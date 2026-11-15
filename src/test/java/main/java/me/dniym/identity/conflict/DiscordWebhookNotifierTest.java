package main.java.me.dniym.identity.conflict;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DiscordWebhookNotifierTest {
    private final AtomicLong now = new AtomicLong(100_000);
    private final ArrayDeque<Job> jobs = new ArrayDeque<>();
    private final ArrayDeque<CompletableFuture<HttpResponse<String>>> responses = new ArrayDeque<>();
    private final List<HttpRequest> requests = new ArrayList<>();
    private DiscordWebhookNotifier notifier;
    private String endpoint = "https://example.invalid/hook";

    @BeforeEach
    void setup() {
        HttpClient client = mock(HttpClient.class);
        ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
        when(executor.schedule(any(Runnable.class), anyLong(), eq(TimeUnit.MILLISECONDS))).thenAnswer(call -> {
            jobs.add(new Job(call.getArgument(0), call.getArgument(1)));
            return null;
        });
        when(client.sendAsync(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenAnswer(call -> {
                    requests.add(call.getArgument(0));
                    return responses.isEmpty() ? CompletableFuture.completedFuture(response(204, Map.of(), ""))
                            : responses.removeFirst();
                });
        notifier = new DiscordWebhookNotifier(ignored -> endpoint, () -> 2500L, client, executor, now::get);
    }

    @AfterEach void close() { notifier.shutdown(); }

    @Test void jsonRoundTripsControlCharactersAndDisablesMentions() {
        String text = "test\t\b\f\r\n" + (char) 1 + " \"quoted\" \\ @everyone";
        var json = JsonParser.parseString(DiscordWebhookNotifier.embedPayload(
                DiscordWebhookNotifier.AlertKind.CONFIRMED, text, now.get())).getAsJsonObject();
        assertEquals(text, json.getAsJsonArray("embeds").get(0).getAsJsonObject().get("description").getAsString());
        assertTrue(json.getAsJsonObject("allowed_mentions").getAsJsonArray("parse").isEmpty());
    }

    @Test void slowRequestKeepsSingleFlightAndEnforcesInterval() {
        var future = new CompletableFuture<HttpResponse<String>>();
        responses.add(future);
        notifier.sendConfirmed("first");
        runNext();
        notifier.sendConfirmed("second");
        assertTrue(jobs.isEmpty());
        future.complete(response(204, Map.of(), ""));
        assertEquals(2500, jobs.peek().delay);
        runNext();
        assertEquals(2, requests.size());
        assertEquals(15, requests.getFirst().timeout().orElseThrow().toSeconds());
    }

    @Test void rateLimitHonorsHeaderAndRetriesSameRequest() {
        respond(429, Map.of("Retry-After", List.of("12.5")), "{\"retry_after\":1}");
        notifier.sendConfirmed("case A");
        runNext();
        assertEquals(12_500, jobs.peek().delay);
        runNext();
        assertEquals(2, requests.size());
        assertEquals(requests.get(0).uri(), requests.get(1).uri());
    }

    @Test void rateLimitBodyAndMalformedResponsesHaveSafeFallback() {
        assertEquals(7500, DiscordWebhookNotifier.retryDelayMs(response(429, Map.of(), "{\"retry_after\":7.5}")));
        assertEquals(5000, DiscordWebhookNotifier.retryDelayMs(response(429, Map.of("Retry-After", List.of("NaN")), "bad")));
    }

    @Test void serverFailuresHaveBoundedRetries() {
        for (int i = 0; i < 3; i++) respond(503, Map.of(), "");
        notifier.sendConfirmed("case");
        drain();
        assertEquals(3, requests.size());
        notifier.sendConfirmed("next");
        drain();
        assertEquals(4, requests.size());
    }

    @Test void transportFailureDoesNotStallFollowingRequests() {
        responses.add(CompletableFuture.failedFuture(new RuntimeException("sensitive URL must not be logged")));
        notifier.sendConfirmed("case");
        drain();
        assertEquals(2, requests.size());
    }

    @Test void invalidPayloadIsNotRetriedAndQueueContinues() {
        respond(400, Map.of(), "");
        notifier.sendConfirmed("first");
        notifier.sendPossible("second");
        drain();
        assertEquals(2, requests.size());
    }

    @Test void deletedEndpointIsSuspendedUntilUrlChanges() {
        respond(404, Map.of(), "");
        notifier.sendConfirmed("first");
        notifier.sendConfirmed("second");
        drain();
        notifier.sendConfirmed("third");
        assertTrue(jobs.isEmpty());
        assertEquals(1, requests.size());
        endpoint = "https://example.invalid/replacement";
        notifier.sendConfirmed("fourth");
        drain();
        assertEquals(2, requests.size());
    }

    @Test void malformedUrlDoesNotEscapeOrBlockLaterValidEndpoint() {
        endpoint = "not a URL";
        notifier.sendConfirmed("bad");
        assertDoesNotThrow(this::drain);
        assertTrue(requests.isEmpty());
        endpoint = "https://example.invalid/replacement";
        notifier.sendConfirmed("good");
        drain();
        assertEquals(1, requests.size());
    }

    @Test void fullQueueStaysBoundedAndMakesRoomForConfirmedCase() {
        for (int i = 0; i < 40; i++) notifier.sendPossible("possible " + i);
        notifier.sendConfirmed("confirmed");
        assertEquals(1, jobs.size());
        drain();
        assertEquals(25, requests.size());
    }

    @Test void shutdownCancelsInflightAndRejectsFurtherWork() {
        var future = new CompletableFuture<HttpResponse<String>>();
        responses.add(future);
        notifier.sendConfirmed("first");
        runNext();
        notifier.shutdown();
        notifier.sendConfirmed("second");
        assertTrue(future.isCancelled());
        assertTrue(jobs.isEmpty());
    }

    private void respond(int status, Map<String, List<String>> headers, String body) {
        responses.add(CompletableFuture.completedFuture(response(status, headers, body)));
    }
    @SuppressWarnings("unchecked")
    private static HttpResponse<String> response(int status, Map<String, List<String>> headers, String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.headers()).thenReturn(HttpHeaders.of(headers, (a, b) -> true));
        when(response.body()).thenReturn(body);
        return response;
    }
    private void runNext() {
        Job job = jobs.removeFirst();
        now.addAndGet(job.delay);
        job.task.run();
    }
    private void drain() {
        int count = 0;
        while (!jobs.isEmpty()) {
            assertTrue(++count < 100, "unbounded scheduling");
            runNext();
        }
    }
    private record Job(Runnable task, long delay) {}
}
