package com.distributed.cache.service;

import com.distributed.cache.dto.UserDto;
import com.distributed.cache.entity.User;
import com.distributed.cache.exception.UserAlreadyExistsException;
import com.distributed.cache.exception.UserNotFoundException;
import com.distributed.cache.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * ============================================================
 * USER SERVICE — DISTRIBUTED CACHE LAYER
 * ============================================================
 *
 * CACHE FLOW:
 *
 * GET /users/{id}:
 *   Request → @Cacheable checks "users::{id}" in Redis
 *     ├── HIT:  Return cached UserDto.Response (no DB query)
 *     └── MISS: Query MySQL → store in Redis → return
 *
 * POST /users:
 *   Request → Save to MySQL → @CachePut writes "users::{id}" to Redis
 *             → @CacheEvict removes stale "users-list" entries
 *
 * PUT /users/{id}:
 *   Request → Update MySQL → @CachePut updates "users::{id}" in Redis
 *             → @CacheEvict removes stale "users-list" entries
 *
 * DELETE /users/{id}:
 *   Request → Delete from MySQL → @CacheEvict removes "users::{id}"
 *             → @CacheEvict removes "users-list" entries
 *
 * FAULT TOLERANCE (handled in RedisConfig.errorHandler()):
 *   Redis down → cache ops fail silently → DB always used → no crash
 * ============================================================
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class UserService {

    private final UserRepository userRepository;

    // ─────────────────────────────────────────────────────────
    // READ: @Cacheable — Cache-aside pattern
    // ─────────────────────────────────────────────────────────

    /**
     * Cache key: "users::1", "users::2", etc.
     * Spring generates key from #id parameter.
     * unless: skips caching if result is null (our config already disables null caching).
     */
    @Cacheable(value = "users", key = "#id")
    @Transactional(readOnly = true)
    public UserDto.Response getUserById(Long id) {
        log.info("[CACHE-MISS] DB query for user id={} | Redis had no entry", id);

        return userRepository.findById(id)
                .map(this::toResponse)
                .orElseThrow(() -> new UserNotFoundException("User not found with id: " + id));
    }

    /**
     * List all users — cached separately with shorter TTL (5 min).
     * Key: "users-list::all"
     * Evicted whenever any user is created/updated/deleted.
     */
    @Cacheable(value = "users-list", key = "'all'")
    @Transactional(readOnly = true)
    public List<UserDto.Response> getAllUsers() {
        log.info("[CACHE-MISS] DB query for ALL users | Redis had no list cache");
        return userRepository.findAll()
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    /**
     * Search users — short TTL (3 min), keyed by keyword.
     * Key: "user-search::alice"
     */
    @Cacheable(value = "user-search", key = "#keyword.toLowerCase()")
    @Transactional(readOnly = true)
    public List<UserDto.Response> searchUsers(String keyword) {
        log.info("[CACHE-MISS] DB search for keyword='{}' | Redis had no search cache", keyword);
        return userRepository.searchByKeyword(keyword)
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    // ─────────────────────────────────────────────────────────
    // CREATE: @CachePut + @CacheEvict
    // ─────────────────────────────────────────────────────────

    /**
     * @CachePut: ALWAYS executes method body (saves to DB), then stores result in cache.
     * This ensures cache is warm immediately after creation.
     *
     * @CacheEvict on users-list: list is now stale, must be removed.
     */
    @Caching(
            put    = { @CachePut(value = "users", key = "#result.id") },
            evict  = { @CacheEvict(value = "users-list", key = "'all'"),
                       @CacheEvict(value = "user-search", allEntries = true) }
    )
    public UserDto.Response createUser(UserDto.Request request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new UserAlreadyExistsException("Email already registered: " + request.getEmail());
        }

        User user = User.builder()
                .name(request.getName())
                .email(request.getEmail())
                .department(request.getDepartment())
                .jobTitle(request.getJobTitle())
                .active(request.isActive())
                .build();

        User saved = userRepository.save(user);
        log.info("[CACHE-PUT] User created id={} | Stored in Redis cache", saved.getId());
        return toResponse(saved);
    }

    // ─────────────────────────────────────────────────────────
    // UPDATE: @CachePut (refresh) + @CacheEvict (lists)
    // ─────────────────────────────────────────────────────────

    @Caching(
            put   = { @CachePut(value = "users", key = "#id") },
            evict = { @CacheEvict(value = "users-list", key = "'all'"),
                      @CacheEvict(value = "user-search", allEntries = true) }
    )
    public UserDto.Response updateUser(Long id, UserDto.Request request) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException("User not found with id: " + id));

        user.setName(request.getName());
        user.setEmail(request.getEmail());
        user.setDepartment(request.getDepartment());
        user.setJobTitle(request.getJobTitle());
        user.setActive(request.isActive());

        User updated = userRepository.save(user);
        log.info("[CACHE-PUT] User updated id={} | Cache refreshed in Redis", updated.getId());
        return toResponse(updated);
    }

    // ─────────────────────────────────────────────────────────
    // DELETE: @CacheEvict (removes specific + list entries)
    // ─────────────────────────────────────────────────────────

    @Caching(evict = {
            @CacheEvict(value = "users",       key = "#id"),
            @CacheEvict(value = "users-list",  key = "'all'"),
            @CacheEvict(value = "user-search", allEntries = true)
    })
    public void deleteUser(Long id) {
        if (!userRepository.existsById(id)) {
            throw new UserNotFoundException("User not found with id: " + id);
        }
        userRepository.deleteById(id);
        log.info("[CACHE-EVICT] User deleted id={} | Entry removed from Redis", id);
    }

    // ─────────────────────────────────────────────────────────
    // RETRY + FALLBACK (Spring Retry)
    // ─────────────────────────────────────────────────────────

    /**
     * @Retryable: On Redis connection errors, retry up to 3 times with exponential backoff.
     * After 3 failures → @Recover method is called → returns DB result directly.
     *
     * This is useful for transient network blips between app and Redis.
     */
    @Retryable(
            retryFor   = { org.springframework.dao.DataAccessResourceFailureException.class },
            maxAttempts = 3,
            backoff     = @Backoff(delay = 500, multiplier = 2)
    )
    @Cacheable(value = "users", key = "#id")
    @Transactional(readOnly = true)
    public UserDto.Response getUserWithRetry(Long id) {
        return getUserById(id);
    }

    @Recover
    public UserDto.Response recoverGetUser(Exception e, Long id) {
        log.warn("[CACHE-RECOVER] Redis retries exhausted for id={} | Falling back to direct DB | error={}",
                id, e.getMessage());
        return userRepository.findById(id)
                .map(u -> {
                    UserDto.Response resp = toResponse(u);
                    resp.setCacheStatus("FALLBACK");
                    return resp;
                })
                .orElseThrow(() -> new UserNotFoundException("User not found: " + id));
    }

    // ─────────────────────────────────────────────────────────
    // MAPPER
    // ─────────────────────────────────────────────────────────

    private UserDto.Response toResponse(User user) {
        return UserDto.Response.builder()
                .id(user.getId())
                .name(user.getName())
                .email(user.getEmail())
                .department(user.getDepartment())
                .jobTitle(user.getJobTitle())
                .active(user.isActive())
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .build();
    }
}
