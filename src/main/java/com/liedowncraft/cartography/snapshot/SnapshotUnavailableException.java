package com.liedowncraft.cartography.snapshot;

import java.io.IOException;

public final class SnapshotUnavailableException extends IOException {
    public SnapshotUnavailableException(String message) {
        super(message);
    }
}
