package com.cmh.psycare.ai.rag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Milvus 的启动探活。
 *
 * <h3>为什么失败只 WARN，而且不影响启动</h3>
 * <p>这是「RAG 是增强不是依赖」这条决策在启动路径上的具体体现：
 * 索引层连不上，表现是「AI 答不出知识库内容」，而不是「服务起不来」。
 * 本地开发时更是常态 —— 很多人只想起个后端调对话，不该被迫先开 Docker。
 *
 * <h3>为什么这里只做「连接 + describe」</h3>
 * <p>探活<b>不建集合、不写数据</b>。写探针在启动路径上会带来两个问题：
 * 多等一次网络往返；以及某天探针逻辑写错时，会在启动时污染真实集合。
 * 建集合留给第一次真实索引去做（那本来就需要）。
 *
 * <h3>与向量化探活的分工</h3>
 * <p>两者都不会让启动失败，但提示的<b>处理动作</b>不同：
 * embedding 的失败通常要去改 Key / 模型名；Milvus 的失败通常要去起容器。
 * 所以提示文案必须分别写到「该去动哪一样」，否则用户看到「索引不可用」不知道该查哪。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MilvusStartupProbe implements ApplicationRunner {

    private final MilvusProperties props;
    private final MilvusVectorStore vectorStore;

    @Override
    public void run(ApplicationArguments args) {
        if (!props.isEnabled()) {
            log.info("[Milvus 启动探活] 已禁用（psycare.milvus.enabled=false）→ 知识库索引与检索不可用，AI 对话不受影响");
            return;
        }
        if (!props.isStartupProbe()) {
            log.info("[Milvus 启动探活] 已关闭（psycare.milvus.startup-probe=false）");
            return;
        }

        String state = vectorStore.describeState();
        if (vectorStore.ping()) {
            log.info("[Milvus 启动探活] ✅ 通过：uri={} state={} token={}",
                    props.resolvedUri(), state, props.maskedToken());
            return;
        }

        log.warn("[Milvus 启动探活] ⚠️ 未通过：uri={} → {}",
                props.resolvedUri(), state);
        log.warn("[Milvus 启动探活] 常见原因与处理：\n"
                + "  1) **容器刚启动、内部还没就绪 —— 实践中最常见**。单容器内嵌 etcd 冷启动"
                + "实测约需 3~4 分钟。这期间端口已被 Docker 的端口转发**瞬间**映射出去"
                + "（TCP 能握手成功），但 Milvus 进程尚未开始服务 → 表现为 DEADLINE_EXCEEDED"
                + "且 `waiting_for_connection`，**看起来像『连不上』，其实只是还没起来**。"
                + "等 `docker ps` 的 STATUS 出现 (healthy) 再启动后端最省事；\n"
                + "     ✅ 补救**不需要重启后端**：建连是惰性的（失败不置 prepared），"
                + "Milvus 就绪后第一次真实索引/检索调用会自动重连成功；\n"
                + "  2) 容器没起 —— 本机启动脚本 E:\\Milvus\\standalone.bat，镜像 milvusdb/milvus:v3.0.0"
                + "（**单容器内嵌 etcd、无 MinIO**，9000 不监听是正常的，别去找它）；\n"
                + "  3) 19530 被别的实例占用（本机这台 Milvus **不是本项目独占**，上面有别人的集合）；\n"
                + "  4) 配了鉴权但 token 为空（或反之）—— 本机未开鉴权时应留空 token。\n"
                + "  排查顺序：先 `docker ps` 看容器在不在、healthy 没 → 再 `netstat -ano | findstr 19530`"
                + "看监听 → 最后才怀疑 token/库名。\n"
                + "影响范围：知识库索引与检索不可用；AI 对话、测评、医患聊天均不受影响。");
    }
}
