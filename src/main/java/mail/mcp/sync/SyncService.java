package mail.mcp.sync;

import mail.mcp.connections.ConnectionRegistry;
import mail.mcp.convert.Pandoc;
import mail.mcp.convert.PandocException;
import mail.mcp.index.IndexLockedException;
import mail.mcp.index.MailIndex;
import mail.mcp.source.MailSource;
import mail.mcp.store.MessageStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Owns background runs in this process; Lucene coordinates writers across processes. */
public final class SyncService {
    private static final Logger log = LoggerFactory.getLogger(SyncService.class);
    private final ConnectionRegistry registry;
    private final Supplier<Pandoc> pandocLocator;
    private final ObjectMapper mapper;
    private final Map<String, Thread> running = new HashMap<>();

    public SyncService(ConnectionRegistry registry, Supplier<Pandoc> pandocLocator, ObjectMapper mapper) {
        this.registry = registry;
        this.pandocLocator = pandocLocator;
        this.mapper = mapper;
    }

    /** Returns at once; the run continues on its own thread. */
    public synchronized SyncStart start(String connection) {
        var definition = registry.definition(connection);
        if (!definition.usable()) { throw new IllegalArgumentException(definition.configError()); }
        String name = definition.name();
        if (running.containsKey(name)) {
            return new SyncStart(SyncStart.Status.ALREADY_RUNNING, "Synchronization is already running here");
        }
        var store = new MessageStore(definition.connectionDir(), mapper);
        if (store.readSyncStatus().map(s -> s.signInRejected()).orElse(false)) {
            return new SyncStart(SyncStart.Status.REFUSED,
                    "Sign-in was rejected in the last run; fix the connection locally and run 'mail-sync "
                            + name + "' in a terminal");
        }
        final Pandoc pandoc;
        try { pandoc = pandocLocator.get(); }
        catch (PandocException e) {
            throw new IllegalStateException("pandoc not found; install pandoc and put it on PATH");
        }
        final MailIndex index;
        try { index = MailIndex.openWriter(definition.connectionDir()); }
        catch (IndexLockedException e) {
            return new SyncStart(SyncStart.Status.LOCKED_BY_OTHER_PROCESS, e.getMessage());
        }
        MailSource source = null;
        try {
            source = registry.resolve(name).openSource();
            MailSource session = source;
            Thread thread = Thread.ofPlatform().daemon().name("sync-" + name).unstarted(() -> {
                try (index; session) {
                    new SyncRunner(store, index, session, new MessageProcessor(store, pandoc)).run(p -> { });
                } catch (RuntimeException e) {
                    log.warn("Background sync failed: {}", e.getClass().getSimpleName());
                } finally {
                    synchronized (this) { running.remove(name); }
                }
            });
            running.put(name, thread);
            thread.start();
        } catch (RuntimeException | Error e) {
            running.remove(name);
            if (source != null) {
                try { source.close(); } catch (RuntimeException closeError) { e.addSuppressed(closeError); }
            }
            try { index.close(); } catch (RuntimeException closeError) { e.addSuppressed(closeError); }
            throw e;
        }
        return new SyncStart(SyncStart.Status.STARTED, "Synchronization started; use syncStatus for progress");
    }

    public synchronized boolean runningHere(String connection) {
        return running.containsKey(registry.resolveName(connection));
    }
}
