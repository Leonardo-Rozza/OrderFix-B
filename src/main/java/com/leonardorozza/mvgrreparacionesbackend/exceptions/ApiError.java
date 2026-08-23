package com.leonardorozza.mvgrreparacionesbackend.exceptions;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiError {
    private LocalDateTime timestamp;
    private int status;
    private String error;
    private String message;
    private String path;
    private String code;
    private Map<String, Object> details;

    public ApiError(LocalDateTime timestamp, int status, String error, String message, String path) {
        this(timestamp, status, error, message, path, null, null);
    }

    public ApiError(LocalDateTime timestamp,
                    int status,
                    String error,
                    String message,
                    String path,
                    String code,
                    Map<String, Object> details) {
        this.timestamp = timestamp;
        this.status = status;
        this.error = error;
        this.message = message;
        this.path = path;
        this.code = code;
        this.details = details;
    }
}
