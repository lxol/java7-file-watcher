package org.ensime.filewatcher;

import java.io.File;
/**
 * Interface for clients that wish to be notified of changes by a FileMonitor.
 */
public interface FileListener {
    void fileAdded(File f);
    void fileRemoved(File f);
    void fileChanged(File f);
    void onOverflow(File f);
}
