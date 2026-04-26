package com.distributed.cache.controller;

import com.distributed.cache.dto.UserDto;
import com.distributed.cache.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    // Injected at runtime to identify which instance served the request
    @Value("${app.instance-id:default}")
    private String instanceId;

    // ─── GET /api/users/{id} ───────────────────────────────────
    // Cache-first: Redis → DB fallback
    @GetMapping("/{id}")
    public ResponseEntity<UserDto.ApiResponse<UserDto.Response>> getUserById(@PathVariable Long id) {
        log.info("[REQUEST] GET /api/users/{} | Instance: {}", id, instanceId);

        UserDto.Response user = userService.getUserById(id);
        return ResponseEntity.ok(
                UserDto.ApiResponse.success(user, "User retrieved", instanceId));
    }

    // ─── GET /api/users ───────────────────────────────────────
    @GetMapping
    public ResponseEntity<UserDto.ApiResponse<List<UserDto.Response>>> getAllUsers() {
        log.info("[REQUEST] GET /api/users | Instance: {}", instanceId);

        List<UserDto.Response> users = userService.getAllUsers();
        return ResponseEntity.ok(
                UserDto.ApiResponse.success(users, "Users retrieved: " + users.size(), instanceId));
    }

    // ─── GET /api/users/search?keyword=alice ──────────────────
    @GetMapping("/search")
    public ResponseEntity<UserDto.ApiResponse<List<UserDto.Response>>> searchUsers(
            @RequestParam String keyword) {
        log.info("[REQUEST] GET /api/users/search?keyword={} | Instance: {}", keyword, instanceId);

        List<UserDto.Response> users = userService.searchUsers(keyword);
        return ResponseEntity.ok(
                UserDto.ApiResponse.success(users, "Search results: " + users.size(), instanceId));
    }

    // ─── POST /api/users ──────────────────────────────────────
    // Writes to DB + warms Redis cache immediately
    @PostMapping
    public ResponseEntity<UserDto.ApiResponse<UserDto.Response>> createUser(
            @Valid @RequestBody UserDto.Request request) {
        log.info("[REQUEST] POST /api/users | name={} | Instance: {}", request.getName(), instanceId);

        UserDto.Response created = userService.createUser(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(UserDto.ApiResponse.success(created, "User created", instanceId));
    }

    // ─── PUT /api/users/{id} ──────────────────────────────────
    // Updates DB + refreshes Redis cache
    @PutMapping("/{id}")
    public ResponseEntity<UserDto.ApiResponse<UserDto.Response>> updateUser(
            @PathVariable Long id,
            @Valid @RequestBody UserDto.Request request) {
        log.info("[REQUEST] PUT /api/users/{} | Instance: {}", id, instanceId);

        UserDto.Response updated = userService.updateUser(id, request);
        return ResponseEntity.ok(
                UserDto.ApiResponse.success(updated, "User updated", instanceId));
    }

    // ─── DELETE /api/users/{id} ───────────────────────────────
    // Deletes from DB + evicts from Redis
    @DeleteMapping("/{id}")
    public ResponseEntity<UserDto.ApiResponse<Void>> deleteUser(@PathVariable Long id) {
        log.info("[REQUEST] DELETE /api/users/{} | Instance: {}", id, instanceId);

        userService.deleteUser(id);
        return ResponseEntity.ok(
                UserDto.ApiResponse.success(null, "User deleted", instanceId));
    }

    // ─── GET /api/users/{id}/cache-bypass ─────────────────────
    // Always goes to DB (demonstrates bypassing cache)
    @GetMapping("/{id}/cache-bypass")
    public ResponseEntity<UserDto.ApiResponse<UserDto.Response>> getUserByIdBypassCache(
            @PathVariable Long id) {
        log.info("[REQUEST] GET /api/users/{}/cache-bypass | Bypassing Redis | Instance: {}", id, instanceId);

        // Uses @Retryable variant with fallback
        UserDto.Response user = userService.getUserWithRetry(id);
        return ResponseEntity.ok(
                UserDto.ApiResponse.success(user, "User retrieved (with retry fallback)", instanceId));
    }
}
