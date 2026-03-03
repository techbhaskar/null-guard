package com.nullguard.callgraph.exception;
public class CallGraphException extends RuntimeException {
    public CallGraphException(String message) { super(message); }
    public CallGraphException(String message, Throwable cause) { super(message, cause); }
}
