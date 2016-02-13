package org.ensime.filewatcher;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent.Kind;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static java.nio.file.StandardWatchEventKinds.*;

public class FileMonitor implements Runnable {

    private final Logger logger = LoggerFactory.getLogger(FileMonitor.class);

    /**
     * The low priority thread used for checking the files being monitored.
     */
    private Thread monitorThread;

    /**
     * A flag used to determine if adding files to be monitored should be recursive.
     */
    private boolean recursive = true;

    /**
     * A flag used to determine if the monitor thread should be running.
     */
    private volatile boolean shouldRun = true;

    /**
     * Access method to get the recursive setting when adding directory for monitoring.
     * @return true if monitoring is enabled for children.
     */
    public boolean isRecursive()
    {
        return this.recursive;
    }

    /**
     * Access method to set the recursive setting when adding files for monitoring.
     * @param newRecursive true if monitoringsho uld be enabled for children.
     */
    public void setRecursive(final boolean newRecursive)
    {
        this.recursive = newRecursive;
    }

    private final Map<WatchKey, Path> directories = new HashMap<>();

    private final CopyOnWriteArrayList<FileListener> listeners = new CopyOnWriteArrayList<>();

    private final CopyOnWriteArraySet<String> selectors = new CopyOnWriteArraySet<>();

    private final CopyOnWriteArrayList<File> watchedDirs = new CopyOnWriteArrayList<>();

    private PathMatcher pathMatcher;

    private WatchService watchService;

    /**
     * Construct a new watcher with no listeners
     */
    public FileMonitor() {
        try {
            watchService = FileSystems.getDefault().newWatchService();
        } catch (final IOException e) {
            throw new RuntimeException("unable to create a WatchService: ", e);
        }
    }

    /**
     * Add a listener to be notified of changes.
     *
     * @param listener The listener to notify.
     */
    public void addListener(final FileListener listener) {
        logger.debug("listener added");
        listeners.add(listener);
    }

    /**
     * Remove a {@link FileListener} from notifications.
     *
     * @param listener The listener to stop notifying.
     */
    public void removeListener(final FileListener listener) {
        listeners.remove(listener);
    }

    /**
     * Add a selector.
     *
     * @param selector. Notify listeners about changes in a file with the
     *     selector extension.
     */
    public void addSelector(final String selector) {

        selectors.add(selector);
        String pattern = "glob:**.{";
        String delim = "";
        for (String s : selectors) {
            pattern += delim + s;
            delim = ",";
        }
        pattern += "}";
        logger.debug("selector added: {}", selector);
        logger.debug("new search pattern: {}", pattern);

        pathMatcher = FileSystems.getDefault().getPathMatcher(pattern);
    }

    /**
     * Add a {@link FileListener} to be notified of changes.
     *
     * @param watchedDir The watchedDir to watch.
     */
    public void addWatchedDir(final File watchedDir) {
        watchedDirs.add(watchedDir);
        if (recursive) {
            registerTree(watchedDir.toPath(), false);
        }
        else {
            registerPath(watchedDir.toPath());
        }
    }

    private void registerPath(Path path)  {
        logger.info("register {}", path);
        final WatchKey key;
        try {
            key = path.register(watchService,
                                ENTRY_CREATE,
                                ENTRY_MODIFY,
                                ENTRY_DELETE);
        }  catch (final IOException e) {
            logger.error("WatchService can not register {}, {}", path, e);
            throw new RuntimeException(e);
        }
        directories.put(key, path);
    }

    /**
     * Register the directory tree.
     *
     * @param start  The directory to register.
     * @param treatExistingAsNew Report existing files as new.
     */

    private void registerTree(Path start, final Boolean treatExistingAsNew)  {
        try {
            Files.walkFileTree(start, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        logger.debug("register {}", dir);
                        registerPath(dir);
                        return FileVisitResult.CONTINUE;
                    }
                    @Override
                    public FileVisitResult visitFile(Path f, BasicFileAttributes attrs) {
                        logger.debug("visited file: {}", f);
                        if (treatExistingAsNew) {
                            logger.debug("treat existing file as new {}", f);
                            if (isWatched(f)) {
                                notifyListeners(f, ENTRY_CREATE);
                            }
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
        } catch (final IOException e) {
            logger.error("unexpected registerTree error. {}", e);
            throw new RuntimeException(e);
        }
    }

    private Boolean isWatched(Path path) {
        return !Files.isDirectory(path) && pathMatcher.matches(path);
    }

    public void shutdown() {
        logger.info("shutdown the file monitor");
        if (watchService != null) {
            try {
                shouldRun = false;
                watchService.close();
            } catch(IOException e) {
                logger.error("unable to close watchService {}", e);
            }
        }
    }

   /**
     * Starts monitoring the files
     */
    public void start()
    {
        logger.info("start monitoring");
        if (this.monitorThread == null)
        {
            this.monitorThread = new Thread(this);
            this.monitorThread.setDaemon(true);
            this.monitorThread.setPriority(Thread.MIN_PRIORITY);
        }
        this.monitorThread.start();
    }

   /**
     * Stops monitoring the files that have been added.
     */
    public void stop()
    {
        logger.info("stop monitoring");
        this.shouldRun = false;
    }

    /**
     *  Wait for Java7 WatchService event and notify the listeners.
     */
    @Override
    public void run() {
        mainloop:
        while (!monitorThread.isInterrupted() && this.shouldRun) {
            final WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException x) {
                logger.error("unexpected WatchService take error. {}", x);
                throw new RuntimeException(x);
            }

            for (WatchEvent<?> watchEvent : key.pollEvents()) {
                final Kind<?> kind = watchEvent.kind();
                Path file = (Path)watchEvent.context();
                if (kind == OVERFLOW) {
                    for (FileListener listener : listeners) {
                        listener.onOverflow(file.toFile());
                    }
                    logger.warn("Receive WatchService OVERFLOW event");
                    continue;
                }

                if (kind == ENTRY_CREATE) {
                    final Path directory_path = directories.get(key);
                    final Path child = directory_path.resolve(file);

                    if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                        registerTree(child, true);
                    }
                }
                if (isWatched(file)) {
                    notifyListeners(file, kind);
                }
            }

            boolean valid = key.reset();
            if (!valid) {
                Path dir = directories.get(key);
                logger.debug("unregister dir: {}", dir);
                directories.remove(key);
                if (directories.isEmpty()) {
                    logger.info("base is removed");
                    for (FileListener listener : listeners) {
                        notifyListeners(dir, ENTRY_DELETE);
                    }
                }
            }
        }
        try {
            watchService.close();
        }
        catch(IOException e) {
            logger.error("unexpected WatchService close error. {}", e);
            throw new RuntimeException(e);
        }
    }

    /**
     * Notify listeners of the event.
     *
     * @param path  The path the event occurred on.
     * @param event The event.
     */
    private void notifyListeners(final Path path, final Kind<?> event) {
        logger.debug("{} event received for {}", event, path);
        if (StandardWatchEventKinds.ENTRY_CREATE.equals(event)) {
            for (FileListener listener : listeners) {
                listener.fileAdded(path.toFile());
            }
        } else if (StandardWatchEventKinds.ENTRY_MODIFY.equals(event)) {
            for (FileListener listener : listeners) {
                listener.fileChanged(path.toFile());
            }
        } else if (StandardWatchEventKinds.ENTRY_DELETE.equals(event)) {
            for (FileListener listener : listeners) {
                listener.fileRemoved(path.toFile());
            }
        } else {
            logger.warn("Unhandled event: {}", event);
        }
    }
}
