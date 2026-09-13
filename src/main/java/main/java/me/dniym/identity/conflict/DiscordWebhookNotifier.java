package main.java.me.dniym.identity.conflict;

import main.java.me.dniym.identity.config.ItemIntegrityConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class DiscordWebhookNotifier {

    private static final Logger LOGGER = LogManager.getLogger("IllegalStack/ItemIntegrity");

    private final java.util.function.Function<Boolean, String> endpoint;
    private final java.util.function.LongSupplier interval;
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final Queue<QueuedWebhook> queue = new ConcurrentLinkedQueue<>();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "IllegalStack-ItemIntegrity-Webhook");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean scheduled = new AtomicBoolean(false);
    private final AtomicInteger suppressed = new AtomicInteger();
    private volatile long lastSentAt;

    public DiscordWebhookNotifier(ItemIntegrityConfig config) {
        this(confirmed -> confirmed ? (config.webhookConfirmedEnabled() ? config.webhookConfirmedUrl() : "")
                : (config.webhookPossibleEnabled() ? config.webhookPossibleUrl() : ""), config::webhookMinIntervalMs);
    }

    public DiscordWebhookNotifier(java.util.function.Function<Boolean, String> endpoint,
                                   java.util.function.LongSupplier interval) {
        this.endpoint = endpoint;
        this.interval = interval;
    }

    public void sendPossible(String message) {
        send(AlertKind.POSSIBLE, message);
    }

    public void sendConfirmed(String message) {
        send(AlertKind.CONFIRMED, message);
    }

    private void send(AlertKind kind, String message) {
        String url = webhookUrl(kind);
        if (!webhookEnabled(kind) || url == null || url.isBlank()) {
            return;
        }
        if (queue.size() >= 25) {
            suppressed.incrementAndGet();
            return;
        }
        queue.offer(new QueuedWebhook(kind, message));
        scheduleDrain(0);
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    private void scheduleDrain(long delayMs) {
        if (!scheduled.compareAndSet(false, true)) {
            return;
        }
        executor.schedule(this::drainOne, delayMs, TimeUnit.MILLISECONDS);
    }

    private void drainOne() {
        scheduled.set(false);
        QueuedWebhook queued = queue.poll();
        if (queued == null) {
            return;
        }

        long waitMs = Math.max(0, interval.getAsLong() - (System.currentTimeMillis() - lastSentAt));
        if (waitMs > 0) {
            queue.offer(queued);
            scheduleDrain(waitMs);
            return;
        }

        String message = queued.message();
        int suppressedNow = suppressed.getAndSet(0);
        if (suppressedNow > 0) {
            message += "\n\n**Rate limit:** " + suppressedNow + " alerta(s) agrupado(s) nesta janela.";
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(webhookUrl(queued.kind())))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(embedPayload(queued.kind(), message), StandardCharsets.UTF_8))
                .build();

        lastSentAt = System.currentTimeMillis();
        httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .exceptionally(error -> {
                    LOGGER.warn("[ItemIntegrity] Falha ao enviar webhook: {}", error.getMessage());
                    return null;
                })
                .thenRun(() -> {
                    if (!queue.isEmpty()) {
                        scheduleDrain(interval.getAsLong());
                    }
                });
    }

    private String escapeJson(String text) {
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "")
                .replace("\n", "\\n");
    }

    private String embedPayload(AlertKind kind, String message) {
        String description = message.length() > 4000 ? message.substring(0, 3997) + "..." : message;
        int color = kind == AlertKind.CONFIRMED ? 15158332 : 16753920;
        String title = kind == AlertKind.CONFIRMED
                ? "Duplicacao confirmada"
                : "Possivel conflito de integridade";
        return """
                {"username":"Item Integrity","embeds":[{"title":"%s","color":%d,"description":"%s","footer":{"text":"Logs: item-integrity-cases.log, item-integrity-possible-cases.log e SQLite"},"timestamp":"%s"}]}
                """.formatted(escapeJson(title), color, escapeJson(description), Instant.now());
    }

    private boolean webhookEnabled(AlertKind kind) {
        String url = webhookUrl(kind);
        return url != null && !url.isBlank();
    }

    private String webhookUrl(AlertKind kind) {
        return endpoint.apply(kind == AlertKind.CONFIRMED);
    }

    public enum AlertKind {
        CONFIRMED,
        POSSIBLE
    }

    private record QueuedWebhook(AlertKind kind, String message) {
    }
}
