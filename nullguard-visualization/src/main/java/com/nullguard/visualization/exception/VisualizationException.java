package com.nullguard.visualization.exception;

public class VisualizationException extends RuntimeException {
    public VisualizationException(String message) {
        super(message);
    }

    public VisualizationException(String message, Throwable cause) {
        super(message, cause);
    }
}
