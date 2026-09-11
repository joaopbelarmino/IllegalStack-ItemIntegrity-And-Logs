package main.java.me.dniym.identity.conflict;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import main.java.me.dniym.identity.config.ItemIntegrityConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.LongSupplier;

public final class DiscordWebhookNotifier {
    private static final Logger LOGGER = LogManager.getLogger("IllegalStack/ItemIntegrity");
    private static final Gson JSON = new Gson();
    private static final int CAPACITY = 25;
    private static final int MAX_ATTEMPTS = 3;
    private static final long MAX_RETRY_DELAY_MS = 3_600_000L;

    private final Function<Boolean, String> endpoint;
    private final LongSupplier interval;
    private final LongSupplier clock;
    private final HttpClient httpClient;
    private final ScheduledExecutorService executor;
    private final ArrayDeque<QueuedWebhook> queue = new ArrayDeque<>();
    private final Map<AlertKind, String> disabledEndpoints = new EnumMap<>(AlertKind.class);
    private boolean busy;
    private boolean stopped;
    private long nextSendAt;
    private long lastWarningAt = -60_000L;
    private int suppressedWarnings;
    private int dropped;
    private CompletableFuture<?> inFlight;

    public DiscordWebhookNotifier(ItemIntegrityConfig config) {
        this(confirmed -> confirmed ? (config.webhookConfirmedEnabled() ? config.webhookConfirmedUrl() : "")
                : (config.webhookPossibleEnabled() ? config.webhookPossibleUrl() : ""), config::webhookMinIntervalMs);
    }

    public DiscordWebhookNotifier(Function<Boolean, String> endpoint, LongSupplier interval) {
        this(endpoint, interval, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
                Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread thread = new Thread(r, "IllegalStack-ItemIntegrity-Webhook");
                    thread.setDaemon(true);
                    return thread;
                }), System::currentTimeMillis);
    }

    DiscordWebhookNotifier(Function<Boolean, String> endpoint, LongSupplier interval, HttpClient client,
                           ScheduledExecutorService executor, LongSupplier clock) {
        this.endpoint = endpoint;
        this.interval = interval;
        this.httpClient = client;
        this.executor = executor;
        this.clock = clock;
    }

    public void sendPossible(String message) { send(AlertKind.POSSIBLE, message); }
    public void sendConfirmed(String message) { send(AlertKind.CONFIRMED, message); }

    private synchronized void send(AlertKind kind, String message) {
        if (stopped) return;
        String url = endpoint.apply(kind == AlertKind.CONFIRMED);
        if (url == null || url.isBlank() || url.equals(disabledEndpoints.get(kind))) return;
        if (queue.size() >= CAPACITY) {
            QueuedWebhook possible = kind == AlertKind.CONFIRMED ? queue.stream()
                    .filter(entry -> entry.kind == AlertKind.POSSIBLE).findFirst().orElse(null) : null;
            dropped++;
            warn("fila cheia; notificacao descartada, caso preservado nos logs/SQLite");
            if (possible == null) return;
            queue.remove(possible);
        }
        queue.addLast(new QueuedWebhook(kind, url, truncate(message, 3800)));
        scheduleNext();
    }

    private synchronized void scheduleNext() {
        if (stopped || busy || queue.isEmpty()) return;
        busy = true;
        executor.schedule(this::drainOne, Math.max(0, nextSendAt - clock.getAsLong()), TimeUnit.MILLISECONDS);
    }

    private synchronized void drainOne() {
        if (stopped) return;
        QueuedWebhook entry = queue.removeFirst();
        String currentUrl = endpoint.apply(entry.kind == AlertKind.CONFIRMED);
        if (!entry.url.equals(currentUrl) || entry.url.equals(disabledEndpoints.get(entry.kind))) {
            busy = false;
            scheduleNext();
            return;
        }
        if (entry.payload == null) {
            String note = dropped == 0 ? "" : "\n\nNotificacoes descartadas por fila cheia: " + dropped
                    + ". Consulte os logs/SQLite.";
            entry.payload = embedPayload(entry.kind, entry.message + note, clock.getAsLong());
            dropped = 0;
        }
        HttpRequest request;
        try {
            URI uri = URI.create(entry.url);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
                throw new IllegalArgumentException("Invalid endpoint");
            }
            request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(entry.payload, StandardCharsets.UTF_8)).build();
        } catch (IllegalArgumentException error) {
            disabledEndpoints.put(entry.kind, entry.url);
            warn("URL invalida; endpoint suspenso ate alterar a configuracao/reiniciar");
            busy = false;
            scheduleNext();
            return;
        }
        entry.attempts++;
        nextSendAt = clock.getAsLong() + Math.max(0, interval.getAsLong());
        try {
            var requestFuture = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            inFlight = requestFuture;
            requestFuture.whenComplete((response, error) -> completed(entry, response, error));
        } catch (RuntimeException error) {
            completed(entry, null, error);
        }
    }

    private synchronized void completed(QueuedWebhook entry, HttpResponse<String> response, Throwable error) {
        if (stopped) return;
        int status = response == null ? 0 : response.statusCode();
        boolean success = error == null && status >= 200 && status < 300;
        boolean retryable = error != null || status == 429 || status >= 500;
        long delay = 1000L << Math.min(entry.attempts - 1, 5);
        if (response != null) {
            delay = Math.max(delay, retryDelayMs(response));
            if ("0".equals(response.headers().firstValue("X-RateLimit-Remaining").orElse("")) || status == 429) {
                nextSendAt = Math.max(nextSendAt, clock.getAsLong() + delay);
            }
        }
        if (!success) {
            // Never log exception messages, response bodies or URLs: they may contain webhook tokens.
            warn(error == null ? "HTTP " + status : "falha de transporte/timeout");
            if (status == 401 || status == 403 || status == 404) {
                disabledEndpoints.put(entry.kind, entry.url);
            }
            if (retryable && entry.attempts < MAX_ATTEMPTS && delay <= MAX_RETRY_DELAY_MS) {
                // Keep FIFO ordering and reserve the failed request even if producers filled the queue.
                if (queue.size() >= CAPACITY) {
                    queue.removeLast();
                    dropped++;
                    warn("fila cheia durante retry; notificacao descartada, consulte logs/SQLite");
                }
                queue.addFirst(entry);
                nextSendAt = Math.max(nextSendAt, clock.getAsLong() + delay);
            } else {
                warn("entrega abandonada; consulte o caso nos logs/SQLite");
            }
        }
        inFlight = null;
        busy = false;
        scheduleNext();
    }

    static long retryDelayMs(HttpResponse<String> response) {
        long delay = Math.max(secondsToMs(response.headers().firstValue("Retry-After").orElse("")),
                secondsToMs(response.headers().firstValue("X-RateLimit-Reset-After").orElse("")));
        if (response.statusCode() == 429) {
            try {
                var body = JsonParser.parseString(response.body()).getAsJsonObject();
                if (body.has("retry_after")) delay = Math.max(delay, secondsToMs(body.get("retry_after").getAsString()));
            } catch (RuntimeException ignored) {
                // A malformed body cannot stop the delivery queue.
            }
            if (delay == 0) delay = 5000;
        }
        return delay;
    }

    private static long secondsToMs(String value) {
        try {
            double seconds = Double.parseDouble(value);
            if (!Double.isFinite(seconds) || seconds < 0) return 0;
            return (long) Math.min(MAX_RETRY_DELAY_MS + 1, Math.ceil(seconds * 1000));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private void warn(String reason) {
        suppressedWarnings++;
        long now = clock.getAsLong();
        if (now - lastWarningAt < 60_000L) return;
        LOGGER.warn("[ItemIntegrity] Webhook: {}. {} ocorrencia(s) desde o ultimo resumo.",
                reason, suppressedWarnings);
        suppressedWarnings = 0;
        lastWarningAt = now;
    }

    static String embedPayload(AlertKind kind, String message, long now) {
        return JSON.toJson(Map.of("username", "Zetra Item Integrity",
                "allowed_mentions", Map.of("parse", List.of()),
                "embeds", List.of(Map.of(
                        "title", kind == AlertKind.CONFIRMED ? "Duplicacao confirmada" : "Possivel conflito de integridade",
                        "color", kind == AlertKind.CONFIRMED ? 15158332 : 16753920,
                        "description", truncate(message, 4000),
                        "footer", Map.of("text", "Logs: item-integrity-cases.log, item-integrity-possible-cases.log e SQLite"),
                        "timestamp", Instant.ofEpochMilli(now).toString()))));
    }

    private static String truncate(String text, int limit) {
        if (text.length() <= limit) return text;
        int end = limit - 3;
        if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end) + "...";
    }

    public synchronized void shutdown() {
        stopped = true;
        queue.clear();
        if (inFlight != null) inFlight.cancel(true);
        executor.shutdownNow();
    }

    public enum AlertKind { CONFIRMED, POSSIBLE }

    private static final class QueuedWebhook {
        final AlertKind kind;
        final String url;
        final String message;
        String payload;
        int attempts;
        QueuedWebhook(AlertKind kind, String url, String message) {
            this.kind = kind;
            this.url = url;
            this.message = message;
        }
    }
}
