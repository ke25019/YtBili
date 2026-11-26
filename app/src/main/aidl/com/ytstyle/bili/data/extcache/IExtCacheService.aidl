// Runs in the Shizuku UserService process (shell or root uid), not in the app
// process. Every method is synchronous and called from a background thread.
package com.ytstyle.bili.data.extcache;

import android.os.ParcelFileDescriptor;

interface IExtCacheService {
    /** `entry.json` paths under [root], depth-limited. */
    String[] findEntries(String root, int maxDepth);

    /** File paths named [name] under [root], depth-limited. */
    String[] findFiles(String root, String name, int maxDepth);

    String readText(String path);

    long length(String path);

    boolean exists(String path);

    /**
     * Opens [path] for reading and positions it at [offset].
     *
     * The returned descriptor is passed over Binder, so the app can read a file
     * it has no filesystem permission for — the open happened as shell/root.
     */
    ParcelFileDescriptor openAt(String path, long offset);

    /** "uid=…, pid=…" for the diagnostics panel. */
    String identity();

    /** Shizuku asks the user service to exit; the process must die here. */
    void destroy();
}
