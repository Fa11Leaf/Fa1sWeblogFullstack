package com.fa11leaf.blog.auth;

import java.security.SecureRandom;
import java.util.Base64;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fa11leaf.blog.config.AdminProperties;

/**
 * 首次启动时创建后台账号。
 *
 * <p>为什么不把账号写进 Flyway 迁移脚本：迁移里只能放<b>固定的</b> BCrypt 摘要，
 * 那意味着仓库里躺着一个所有人都能看到的密码哈希。反过来，把口令放进环境变量、
 * 由应用启动时创建，口令就只存在于你自己的 .env 里。
 *
 * <p>口令缺失时的处理是个取舍：直接启动失败最"安全"，但那会让每一个刚 clone 下来的人
 * 卡在第一步。所以这里生成一个一次性随机口令、并且用醒目的方框打进日志。
 * 它只在本机、只在首次启动时出现一次；下一次启动因为没有配置口令也不会重新生成，
 * 因为 users 表已经有行了。
 */
@Component
public class AdminSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminSeeder.class);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AdminProperties properties;

    public AdminSeeder(UserRepository users, PasswordEncoder passwordEncoder, AdminProperties properties) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (users.count() > 0) {
            log.debug("users 表已有 {} 行，跳过账号初始化", users.count());
            return;
        }

        boolean generated = !properties.hasConfiguredPassword();
        String password = generated ? randomPassword() : properties.password();

        users.save(new User(
                properties.username(),
                passwordEncoder.encode(password),
                properties.displayName(),
                "ADMIN"));

        log.info("已创建后台账号：{}", properties.username());
        if (generated) {
            log.warn("""

                    ╔════════════════════════════════════════════════════════════╗
                    ║  没有配置 ADMIN_PASSWORD，已生成一次性初始口令：           ║
                    ║      {}
                    ║  登录后请立刻在「设置」里改掉它。                          ║
                    ║  想固定口令：在项目根目录 .env 里写 ADMIN_PASSWORD=xxx，   ║
                    ║  删掉 users 表里那一行后重启即可重新生成。                 ║
                    ╚════════════════════════════════════════════════════════════╝
                    """, password);
        } else {
            log.info("口令取自 ADMIN_PASSWORD 环境变量");
        }
    }

    /** 12 个字符的 URL 安全 Base64，约 72 位熵，一次性口令足够。 */
    private static String randomPassword() {
        byte[] buf = new byte[9];
        RANDOM.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }
}
