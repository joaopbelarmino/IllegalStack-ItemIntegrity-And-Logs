package main.java.me.dniym.identity.conflict;

import main.java.me.dniym.IllegalStack;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class CaseFileLogger {

    private static final Logger LOGGER = LogManager.getLogger("IllegalStack/ItemIntegrity");

    private final Path confirmedFile;
    private final Path possibleFile;
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong lastWarning = new AtomicLong();
    private final ExecutorService executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(512), r -> {
        Thread thread = new Thread(r, "IllegalStack-ItemIntegrity-CaseFile");
        thread.setDaemon(true);
        return thread;
    }, (task, pool) -> {
        long count = dropped.incrementAndGet();
        long now = System.currentTimeMillis(), last = lastWarning.get();
        if (now - last >= 60000 && lastWarning.compareAndSet(last, now))
            LOGGER.warn("[ItemIntegrity] Fila de arquivo de casos indisponivel; {} entradas nao gravadas. Consulte SQLite.", count);
    });

    public CaseFileLogger(IllegalStack plugin) {
        this.confirmedFile = plugin.getDataFolder().toPath().resolve("item-integrity-cases.log");
        this.possibleFile = plugin.getDataFolder().toPath().resolve("item-integrity-possible-cases.log");
    }

    public void append(String caseId, String details, boolean confirmed) {
        executor.execute(() -> {
            Path file = confirmed ? confirmedFile : possibleFile;
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, entry(caseId, details), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                LOGGER.warn("[ItemIntegrity] Falha ao gravar {} para {}: {}",
                        file.getFileName(), caseId, e.getMessage());
            }
        });
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    private String entry(String caseId, String details) {
        return "\n============================================================\n"
                + "CASE " + caseId + "\n"
                + "============================================================\n"
                + details.stripTrailing()
                + "\n";
    }
}
