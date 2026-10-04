package com.fa11leaf.blog.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fa11leaf.blog.common.BusinessException;
import com.fa11leaf.blog.common.ErrorCode;
import com.fa11leaf.blog.config.AdminProperties;

/**
 * 登录、校验令牌、退出。
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** 令牌原文是 32 字节随机数，用 URL 安全的 Base64 表示（43 个字符），信息量远超暴力破解可能。 */
    private static final int TOKEN_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository users;
    private final UserTokenRepository tokens;
    private final PasswordEncoder passwordEncoder;
    private final AdminProperties properties;

    public AuthService(UserRepository users,
                       UserTokenRepository tokens,
                       PasswordEncoder passwordEncoder,
                       AdminProperties properties) {
        this.users = users;
        this.tokens = tokens;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    /** 一次成功的登录：令牌原文只在这一次返回给浏览器，服务端只留摘要。 */
    public record Session(String token, LocalDateTime expiresAt, User user) {
    }

    @Transactional
    public Session login(String username, String rawPassword) {
        User user = users.findByUsername(username)
                // 用户名不存在与口令错误返回同一个提示：不给出"这个用户名存在"的额外信息
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "用户名或口令不正确"));

        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            log.warn("登录失败：口令不匹配，username={}", username);
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "用户名或口令不正确");
        }

        String raw = newRawToken();
        LocalDateTime expiresAt = LocalDateTime.now().plusHours(properties.tokenTtlHours());
        tokens.save(new UserToken(user.getId(), sha256Hex(raw), expiresAt));

        // 顺手清理过期记录。单用户场景下这个表最多几十行，
        // 不值得为它单独上定时任务。
        int purged = tokens.deleteExpired(LocalDateTime.now());
        if (purged > 0) {
            log.debug("清理过期令牌 {} 条", purged);
        }

        log.info("登录成功：{}", user.getUsername());
        return new Session(raw, expiresAt, user);
    }

    /** 用令牌原文换回用户；令牌无效或已过期时返回空。 */
    @Transactional(readOnly = true)
    public Optional<User> resolve(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }

        return tokens.findByTokenHash(sha256Hex(rawToken))
                .filter(t -> !t.expired(LocalDateTime.now()))
                .flatMap(t -> users.findById(t.getUserId()));
    }

    @Transactional
    public void logout(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        tokens.findByTokenHash(sha256Hex(rawToken)).ifPresent(tokens::delete);
    }

    /**
     * 改口令。
     *
     * <p>要求提供当前口令，是为了让"令牌泄漏"不至于直接变成"账号被永久接管"。
     * 改完顺手清掉该用户已签发的全部令牌：既让本人重新登录一次确认新口令可用，
     * 也让任何可能已经泄漏的旧令牌立刻作废。
     */
    @Transactional
    public void changePassword(String username, String currentPassword, String newPassword) {
        User user = users.findByUsername(username)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED, "登录状态已失效"));

        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "当前口令不正确");
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        int removed = tokens.deleteByUserId(user.getId());
        log.info("口令已修改，同时失效 {} 个已签发的令牌", removed);
    }

    private static String newRawToken() {
        byte[] buf = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    /**
     * 令牌摘要。
     *
     * <p>这里用不加盐的 SHA-256，和口令必须用 BCrypt 是两回事：口令熵低、需要加盐抵抗彩虹表；
     * 而令牌本身是 256 位随机数，根本不存在"被猜出来"的问题，加盐只会让查询多一次全表扫描。
     */
    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 是 JDK 必须实现的算法，走到这里说明运行环境坏了
            throw new IllegalStateException("运行环境缺少 SHA-256 实现", ex);
        }
    }
}
