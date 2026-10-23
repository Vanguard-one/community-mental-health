package com.cmh.psycare.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * 启动时确保 {@code scale.is_deleted} 列存在（量表软删除所需）。
 *
 * <h3>为什么需要这个类</h3>
 * <p>量表删除改成了逻辑删除，{@code Scale} 实体因此新增 {@code isDeleted} 属性 ——
 * 而全局配置 {@code logic-delete-field: isDeleted} 是**按属性名生效**的：
 * 只要实体里有这个属性，MyBatis-Plus 就会给**所有**
 * {@code selectById / selectList / deleteById} 生成的 SQL 追加
 * {@code is_deleted = 0}。
 *
 * <p>也就是说，如果数据库里没有这一列，不是"删除功能不可用"这么局部，
 * 而是<b>整个居民端量表列表、问卷详情、提交测评全部报
 * {@code Unknown column 'is_deleted'}</b>。这类问题在本地开发里极难第一时间
 * 归因到"少执行了一个 ALTER"。
 *
 * <p>所以在应用启动时补一次列：有则什么都不做，无则 ALTER。
 * 这样「拉代码 → 启动」这条路不会因为漏执行脚本而崩，
 * 同时仍然保留 {@code sql/v12_scale_manage_ddl.sql} 供建库脚本与线上变更使用
 * （正式环境应当由迁移工具执行 DDL，而不是靠应用自己改表 —— 这里的自动补列
 * 是为单体毕设项目的本地/演示环境做的取舍，注释写清，不藏着）。
 *
 * <h3>为什么用 DataSource 而不是 JdbcTemplate</h3>
 * <p>本项目没有显式引入 {@code spring-boot-starter-jdbc}（依赖是
 * mybatis-plus-spring-boot3-starter 带进来的），无法确定
 * {@code JdbcTemplate} 的自动配置一定生效。{@code DataSource} 则是 MyBatis 的
 * 硬依赖，必然存在 —— 用它不引入任何新的装配风险。
 */
@Slf4j
@Component
@Order(-100)   // 必须早于任何会碰 scale 表的 Runner（如 DataInitializer）
@RequiredArgsConstructor
public class ScaleSchemaInitializer implements ApplicationRunner {

    private static final String COLUMN = "is_deleted";
    private static final String TABLE = "scale";

    private final DataSource dataSource;

    @Override
    public void run(ApplicationArguments args) {
        try (Connection conn = dataSource.getConnection()) {
            if (columnExists(conn)) {
                return;
            }
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("ALTER TABLE `" + TABLE + "` ADD COLUMN `" + COLUMN + "` "
                        + "TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 未删 1 已删'");
            }
            log.info("[Scale] 检测到缺少 {}.{} 列，已自动补齐（量表软删除）", TABLE, COLUMN);
        } catch (Exception e) {
            // 不让启动失败：列缺失只影响量表相关接口，其他功能还能用；
            // 但必须把话说清楚，否则用户只会看到一串 Unknown column。
            log.error("[Scale] 自动补齐 {}.{} 列失败：{}。"
                            + "请手工执行 psycare-server/sql/v12_scale_manage_ddl.sql，"
                            + "否则筛查表列表 / 启停 / 删除会报 Unknown column",
                    TABLE, COLUMN, e.getMessage());
        }
    }

    private boolean columnExists(Connection conn) throws Exception {
        String sql = "SELECT 1 FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ? LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, TABLE);
            ps.setString(2, COLUMN);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}
