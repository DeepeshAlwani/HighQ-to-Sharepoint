package com.hq2sp.sharepoint;

public class GraphException extends RuntimeException {
    private final int status;

    public GraphException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
