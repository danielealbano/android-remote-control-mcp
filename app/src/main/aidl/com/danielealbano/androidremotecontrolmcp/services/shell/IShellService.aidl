package com.danielealbano.androidremotecontrolmcp.services.shell;

import android.os.ParcelFileDescriptor;
import com.danielealbano.androidremotecontrolmcp.services.shell.IShellProcessCallback;

interface IShellService {
    ParcelFileDescriptor start(int processId, String script, IShellProcessCallback callback) = 1;
    void kill(int processId) = 2;
    void destroy() = 16777114;
}
