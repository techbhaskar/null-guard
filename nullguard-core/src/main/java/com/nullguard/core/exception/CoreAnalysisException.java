package com.nullguard.core.exception;

public class CoreAnalysisException extends RuntimeException {
    public CoreAnalysisException(String message) { super(message); }
    public CoreAnalysisException(String message, Throwable cause) { super(message, cause); }
}
