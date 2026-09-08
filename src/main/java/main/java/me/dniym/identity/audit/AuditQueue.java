package main.java.me.dniym.identity.audit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fila entre a server thread (que só enfileira, nunca espera) e o writer
 * async do DatabaseService (que consome em lote). Limitada em tamanho -
 * se o writer não conseguir acompanhar (ex: SQLite lento/travado), a fila
 * descarta o mais antigo e loga um aviso, em vez de crescer sem limite e
 * estourar memória. Perder algumas entradas de auditoria é aceitável;
 * travar o servidor não é (FAIL_OPEN).
 */
public final class AuditQueue {

    private static final Logger LOGGER = LogManager.getLogger("IllegalStack/ItemIntegrity");

    private final LinkedBlockingQueue<AuditTask> queue;
    private final int capacity;
    private final AtomicLong droppedCount = new AtomicLong();
    private final AtomicLong lastWarningMs = new AtomicLong();

    public AuditQueue(int capacity) {
        this.capacity = capacity;
        this.queue = new LinkedBlockingQueue<>(capacity);
    }

    /** Nunca bloqueia. Se a fila estiver cheia, descarta a entrada mais antiga e loga (uma vez a cada 1000 descartes, pra não spammar). */
    public void offer(AuditTask task) {
        if (!queue.offer(task)) {
            queue.poll(); // descarta o mais antigo pra abrir espaço
            queue.offer(task);
            long dropped = droppedCount.incrementAndGet();
            long now = System.currentTimeMillis();
            long last = lastWarningMs.get();
            if (now - last >= 60_000L && lastWarningMs.compareAndSet(last, now)) {
                LOGGER.warn("[ItemIntegrity] AuditQueue cheia (capacidade {}) - descartando entradas antigas. Total descartado até agora: {}",
                        capacity, dropped);
            }
        }
    }

    /** Bloqueia até timeoutMs esperando pelo menos uma tarefa, depois drena o resto que já estiver disponível sem esperar mais. Usado só pelo writer thread. */
    public List<AuditTask> takeBatch(int maxBatchSize, long timeoutMs) throws InterruptedException {
        List<AuditTask> batch = new ArrayList<>(maxBatchSize);
        AuditTask first = queue.poll(timeoutMs, TimeUnit.MILLISECONDS);
        if (first == null) {
            return batch;
        }
        batch.add(first);
        queue.drainTo(batch, maxBatchSize - 1);
        return batch;
    }

    public long droppedCount() {
        return droppedCount.get();
    }

    public int size() {
        return queue.size();
    }
}
