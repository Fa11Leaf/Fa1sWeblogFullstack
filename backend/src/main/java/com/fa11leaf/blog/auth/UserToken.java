package com.fa11leaf.blog.auth;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 登录令牌。
 *
 * <p><b>存摘要而不是令牌本身。</b>这一列的唯一用途是"拿着浏览器送来的令牌，算个摘要，
 * 查一下有没有匹配的行"。因此数据库里根本不需要保存可用令牌——泄漏了也冒充不了。
 * 这和 users 里存 BCrypt 摘要是同一个道理。
 *
 * <p><b>为什么不用 JWT：</b>JWT 的优势是服务端无状态，代价是签出去就撤不回来——
 * 想"退出登录"就得再维护一张黑名单表，等于把无状态又变回有状态。
 * 而这个后台只有一个用户、只在本机跑，无状态带来的好处≈0，
 * 撤销能力却是实打实的。<b>顺带还省掉一个依赖</b>：jjwt-jackson 需要 Jackson 2 的
 * databind，而 Boot 4 已经换成 Jackson 3，硬凑容易踩坑。
 */
@Entity
@Table(name = "user_tokens")
public class UserToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "token_hash", nullable = false, length = 64, unique = true)
    private String tokenHash;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    protected UserToken() {
    }

    public UserToken(Long userId, String tokenHash, LocalDateTime expiresAt) {
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public boolean expired(LocalDateTime now) {
        return expiresAt.isBefore(now);
    }
}
