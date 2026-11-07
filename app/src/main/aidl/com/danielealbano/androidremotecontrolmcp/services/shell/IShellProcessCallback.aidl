package com.danielealbano.androidremotecontrolmcp.services.shell;

oneway interface IShellProcessCallback {
    void onExit(int processId, int exitCode) = 1;
}
