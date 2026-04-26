package com.distributed.cache.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.io.Serializable;
import java.time.LocalDateTime;

public class UserDto {

    // ─── Request DTO ──────────────────────────────────────────
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Request implements Serializable {

        @NotBlank(message = "Name is required")
        @Size(min = 2, max = 100, message = "Name must be 2–100 characters")
        private String name;

        @NotBlank(message = "Email is required")
        @Email(message = "Invalid email format")
        private String email;

        @Size(max = 50)
        private String department;

        @Size(max = 100)
        private String jobTitle;

        private boolean active = true;
    }

    // ─── Response DTO ─────────────────────────────────────────
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @ToString
    public static class Response implements Serializable {
        private static final long serialVersionUID = 1L;

        private Long id;
        private String name;
        private String email;
        private String department;
        private String jobTitle;
        private boolean active;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;

        // Cache metadata (for observability demo)
        private transient String cacheStatus; // HIT / MISS / FALLBACK
    }

    // ─── API Envelope ─────────────────────────────────────────
    @Getter
    @Setter
    @AllArgsConstructor
    @Builder
    public static class ApiResponse<T> {
        private boolean success;
        private String message;
        private T data;
        private String instanceId; // Shows which app instance served the request

        public static <T> ApiResponse<T> success(T data, String message, String instanceId) {
            return ApiResponse.<T>builder()
                    .success(true).message(message).data(data).instanceId(instanceId).build();
        }

        public static <T> ApiResponse<T> error(String message) {
            return ApiResponse.<T>builder()
                    .success(false).message(message).build();
        }
    }
}
