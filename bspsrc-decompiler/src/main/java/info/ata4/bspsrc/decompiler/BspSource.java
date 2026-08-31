/*
** 2011 April 5
**
** The author disclaims copyright to this source code.  In place of
** a legal notice, here is a blessing:
**    May you do good and not evil.
**    May you find forgiveness for yourself and forgive others.
**    May you share freely, never taking more than you give.
*/

package info.ata4.bspsrc.decompiler;

import info.ata4.bspsrc.decompiler.modules.BspDecompiler;
import info.ata4.bspsrc.decompiler.modules.texture.TextureSource;
import info.ata4.bspsrc.lib.BspFile;
import info.ata4.bspsrc.lib.BspFileReader;
import info.ata4.bspsrc.lib.PakFile;
import info.ata4.bspsrc.lib.app.SourceAppDB;
import info.ata4.bspsrc.lib.app.SourceAppId;
import info.ata4.bspsrc.lib.exceptions.BspException;
import info.ata4.bspsrc.lib.nmo.NmoException;
import info.ata4.bspsrc.lib.nmo.NmoFile;
import org.apache.logging.log4j.CloseableThreadContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNull;

/**
 * Main control class for all decompiling modules.
 * 
 * <i>"A simple decompiler for HL2 bsp files"</i>
 * 
 * Original class name: unmap.Vmex
 * Original author: Rof
 *
 * @author Nico Bergemann <barracuda415 at yahoo.de>
 */
public class BspSource {

    private static final Logger L = LogManager.getLogger();
    public static final String DECOMPILE_TASK_ID_IDENTIFIER = "decompile_id";

    public static final String VERSION = "1.4.9-DEV";

    /**
     * How long {@link #run} waits for the next signal before rechecking if cancellation made
     * the signals it is still missing impossible to arrive.
     */
    private static final long SIGNAL_POLL_TIMEOUT_MS = 200;

    private final BspSourceConfig config;
    private final List<BspFileEntry> entries;
    private final List<UUID> entryUuids;

    /**
     * Set by {@link #cancel()} and never reset. Read by the entry tasks to abandon their work.
     */
    private volatile boolean cancelled;

    /**
     * Executor of the currently running {@link #run}, published so that {@link #cancel()} can
     * shut it down from another thread. {@code null} while nothing is running.
     */
    private volatile ExecutorService executor;

    public BspSource(BspSourceConfig config, List<BspFileEntry> entries) {
        this.config = requireNonNull(config);
        this.entries = List.copyOf(entries);
        this.entryUuids = Stream.generate(UUID::randomUUID)
                .limit(entries.size())
                .toList();
    }

    /**
     * Starts BSPSource
     */
    public void run(Consumer<Signal> signalConsumer) throws InterruptedException {
        // some benchmarking
        long startTime = System.currentTimeMillis();

        // log all config fields in debug mode
        if (config.debug) {
            config.dumpToLog();
        }

        if (entries.isEmpty())
            return;

        L.info("Starting...");

        var outputQueue = new LinkedBlockingQueue<Signal>();
        int processedEntries = 0;
        try (var executorService = Executors.newWorkStealingPool()) {
            // publish the executor, so cancel() can shut it down from another thread
            this.executor = executorService;
            if (cancelled)
                executorService.shutdownNow();

            int submittedTasks = 0;
            try {
                for (int i = 0; i < entries.size(); i++) {
                    int finalI = i; // java....
                    executorService.submit(() -> decompile(finalI, outputQueue));
                    submittedTasks++;
                }
            } catch (RejectedExecutionException e) {
                // cancel() shut the executor down while we were still submitting, the
                // remaining entries simply never run
                L.debug("Stopped submitting entries because of cancellation", e);
            }

            try {
                int remainingTasks = submittedTasks;
                while (remainingTasks > 0) {
                    var signal = outputQueue.poll(SIGNAL_POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                    if (signal == null) {
                        // Nothing was reported for a while. Entries that cancel() dropped from
                        // the executor queue never report anything at all, so once every task
                        // stopped there is nothing left to wait for.
                        if (cancelled && executorService.isTerminated() && outputQueue.isEmpty())
                            break;

                        continue;
                    }

                    signalConsumer.accept(signal);

                    if (isTerminal(signal)) {
                        remainingTasks--;

                        if (!(signal instanceof Signal.TaskCancelled))
                            processedEntries++;
                    }
                }
            } catch (InterruptedException e) {
                L.info("Stopping because of interrupt");

                // stop the entries that are still running. They check for interruption at
                // their task boundaries and abandon their work there
                executorService.shutdownNow();

                // interuppted. Reset interrupt flag which causes subsequent
                // Executor.close to not wait for tasks to finish
                Thread.currentThread().interrupt();
                throw e;
            }
        } finally {
            this.executor = null;
        }

        // get total execution time. Cancelled entries were never processed, so don't count them
        double duration = (System.currentTimeMillis() - startTime) / 1000.0;
        L.info("Processed %d file(s) in %.4f seconds".formatted(processedEntries, duration));
    }

    /**
     * Cancels a {@link #run} that is currently in progress.
     * <p>
     * Entries that haven't started yet are dropped and entries that are being decompiled right
     * now are interrupted, which makes them abandon their work at the next task boundary.
     * Entries that already finished are unaffected and keep their vmf files. Every abandoned
     * entry is reported as {@link Signal.TaskCancelled}, never as a failure.
     * <p>
     * Can be called from any thread, before, during or after {@link #run}. Cancellation is
     * permanent: a cancelled {@code BspSource} can't be run again.
     */
    public void cancel() {
        cancelled = true;

        // drops all entries that haven't started yet and interrupts the running ones
        ExecutorService executor = this.executor;
        if (executor != null)
            executor.shutdownNow();
    }

    /**
     * @return whether this entry should stop, either because {@link #cancel()} was called or
     *         because the executor interrupted us
     */
    private boolean isCancelled() {
        return cancelled || Thread.currentThread().isInterrupted();
    }

    private void decompile(int index, BlockingQueue<Signal> outputQueue) {
        var entry = entries.get(index);
        var uuid = entryUuids.get(index);

        try (var closeable = CloseableThreadContext.put(DECOMPILE_TASK_ID_IDENTIFIER, uuid.toString())) {
            if (isCancelled()) {
                // cancelled before this entry got the chance to start
                outputQueue.add(new Signal.TaskCancelled(index));
                return;
            }

            outputQueue.add(new Signal.TaskStarted(index));
            try {
                decompile(entry, config);
                outputQueue.add(new Signal.TaskFinished(index));
            } catch (InterruptedException e) {
                // don't swallow the interrupt, run() and the executor rely on it
                Thread.currentThread().interrupt();
                L.info("Cancelled decompiling '{}'", entry.getBspFile());
                outputQueue.add(new Signal.TaskCancelled(index));
            } catch (Throwable e) {
                if (isCancelled()) {
                    // Interrupting a thread closes the nio channels it reads from, so an error
                    // that surfaces while we are being cancelled is a symptom of the
                    // cancellation and not worth reporting as a failure.
                    L.info("Cancelled decompiling '{}'", entry.getBspFile());
                    outputQueue.add(new Signal.TaskCancelled(index));
                } else {
                    L.error("Error occurred decompiling '%s'".formatted(entry.getBspFile()),  e);
                    outputQueue.add(new Signal.TaskFailed(index, e));
                }
            }
        }
    }

    private static boolean isTerminal(Signal signal) {
        // exhaustive on purpose: a new signal has to decide if it ends an entry or not
        return switch (signal) {
            case Signal.TaskStarted ignored -> false;
            case Signal.TaskFinished ignored -> true;
            case Signal.TaskFailed ignored -> true;
            case Signal.TaskCancelled ignored -> true;
        };
    }

    /**
     * Starts the decompiling process
     * <p>
     * Cancellation is cooperative and only happens at the boundaries between the major phases:
     * the calling thread's interrupt flag is checked there, but not inside them. Decompiling a
     * single, already loaded bsp therefore always runs to completion.
     *
     * @throws InterruptedException if the calling thread was interrupted, in which case the
     *                              entry is abandoned and a half written vmf file is deleted.
     *                              The interrupt flag is left set on purpose, so that the
     *                              executor and everything up the call stack still sees it.
     */
    public static void decompile(BspFileEntry entry, BspSourceConfig config)
            throws BspSourceException, BspException, InterruptedException {
        Path bspFile = entry.getBspFile();
        Path vmfFile = entry.getVmfFile();

        // Only used for 'No More Room in Hell'
        Path nmoFile = entry.getNmoFile();
        Path nmosFile = entry.getNmosFile();

        checkCancelled(bspFile);

        // load BSP
        L.info("Loading {}", bspFile);

        var bsp = new BspFile();
        bsp.setAppId(config.defaultAppId);

        try {
            bsp.load(bspFile);
        } catch (NoSuchFileException e) {
            throw new BspSourceException("Could not find bsp file.", e);
        } catch (IOException e) {
            // an interrupt closes the nio channels we read the bsp through, so an i/o error
            // while being cancelled is a symptom of the cancellation
            checkCancelled(bspFile);
            throw new BspSourceException("Error loading bsp file.", e);
        }

        if (config.loadLumpFiles) {
            bsp.loadLumpFiles();
        }

        checkCancelled(bspFile);

        Predicate<String> fileFilter = filename -> !config.smartUnpack ||
                (!PakFile.isVBSPGeneratedFile(filename) && !TextureSource.isPatchedMaterial(filename));

        // extract embedded files
        if (config.unpackEmbedded) {
            try {
                bsp.getPakFile().unpack(entry.getPakDir(), fileFilter);
            } catch (IOException e) {
                // an interrupt closes the nio channels we unpack through, so an i/o error
                // while being cancelled is a symptom of the cancellation
                checkCancelled(bspFile);
                throw new BspSourceException("Can't extract embedded files.", e);
            }

            checkCancelled(bspFile);
        }

        var reader = new BspFileReader(bsp);
        reader.loadAll();

        checkCancelled(bspFile);

        // load NMO if game is 'No More Room in Hell'
        NmoFile nmo = null;
        if (reader.getBspFile().getAppId() == SourceAppId.NO_MORE_ROOM_IN_HELL) {
            if (Files.exists(nmoFile)) {
                try {
                    nmo = new NmoFile();
                    nmo.load(nmoFile, true);

	                // write nmos
	                try {
		                nmo.writeAsNmos(nmosFile);
	                } catch (IOException e) {
                        throw new BspSourceException("Error writing nmos file.", e);
	                }
                } catch (NmoException | IOException e) {
                    throw new BspSourceException("Error loading nmo file.", e);
                }
            } else {
                L.warn("Missing .nmo file! If the bsp is for the objective game mode, its objectives will be missing.");
            }
        }

        if (!config.debug) {
            int appId = reader.getBspFile().getAppId();
            // an unidentified map is common now that detection requires actual evidence, so say
            // so in words rather than printing the raw id
            String gameName = appId == SourceAppId.UNKNOWN
                    ? "Unknown, no game could be identified from this map"
                    : SourceAppDB.getInstance().getName(appId).orElse(String.valueOf(appId));

            L.info("BSP version: {}", reader.getBspFile().getVersion());
            L.info("Game: {}", gameName);
        }

        // last chance to bail out before we create the vmf file
        checkCancelled(bspFile);

        // create and configure decompiler and start decompiling
        boolean decompiled = false;
        try (VmfWriter writer = getVmfWriter(vmfFile.toFile(), config)) {
            BspDecompiler decompiler = new BspDecompiler(reader, writer, config);

            if (nmo != null)
                decompiler.setNmoData(nmo);

            decompiler.start();
            decompiled = true;
            L.info("Finished decompiling {}.", bspFile);
        } catch (IOException e) {
            // an interrupt closes the nio channels underneath us, so an i/o error while being
            // cancelled is a symptom of the cancellation and not a decompiling problem
            checkCancelled(bspFile);
            throw new BspSourceException("Error decompiling bsp.", e);
        } finally {
            // a cancelled entry leaves a truncated vmf behind, which is worse than no vmf at
            // all, because nothing marks it as incomplete
            if (!decompiled && !config.nullOutput && Thread.currentThread().isInterrupted())
                deleteIncompleteVmfFile(vmfFile);
        }
    }

    /**
     * Abandons the current entry if the thread was interrupted, e.g. by {@link #cancel()}.
     * <p>
     * Doesn't clear the interrupt flag: the executor and the caller of {@link #run} need to see
     * the cancellation too.
     */
    private static void checkCancelled(Path bspFile) throws InterruptedException {
        if (Thread.currentThread().isInterrupted())
            throw new InterruptedException("Decompiling '%s' was cancelled.".formatted(bspFile));
    }

    private static void deleteIncompleteVmfFile(Path vmfFile) {
        try {
            if (Files.deleteIfExists(vmfFile))
                L.info("Deleted incomplete vmf file {}", vmfFile);
        } catch (IOException e) {
            L.warn("Couldn't delete incomplete vmf file {}", vmfFile, e);
        }
    }

    private static VmfWriter getVmfWriter(File vmfFile, BspSourceConfig config) throws IOException {
        // write to file or omit output?
        return new VmfWriter(
                config.nullOutput 
                        ? new PrintWriter(OutputStream.nullOutputStream()) 
                        // Latin-1 matches the charset the entity lump is decoded with, so
                        // non-ASCII targetnames round-trip instead of being replaced by '?'.
                        : new PrintWriter(vmfFile, StandardCharsets.ISO_8859_1),
                config.vmfDoubleScale,
                config.vmfDoubleScaleTextureAxes,
                config.vmfDoubleScaleTextureScale
        );
    }

    public List<UUID> getEntryUuids() {
        return entryUuids;
    }

    public sealed interface Signal {
        record TaskStarted(int index) implements Signal {}
        record TaskFinished(int index) implements Signal {}
        record TaskFailed(int index, Throwable exception) implements Signal {}

        /**
         * The entry was abandoned because {@link BspSource#cancel()} was called. This is not a
         * failure: nothing is wrong with the entry, it just never got the chance to finish, so
         * there is no exception and nothing to report to the user beyond the cancellation.
         */
        record TaskCancelled(int index) implements Signal {}
    }
}
