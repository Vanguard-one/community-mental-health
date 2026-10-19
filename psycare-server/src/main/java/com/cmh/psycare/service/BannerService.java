package com.cmh.psycare.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cmh.psycare.common.exception.BizException;
import com.cmh.psycare.dto.BannerRequest;
import com.cmh.psycare.entity.Banner;
import com.cmh.psycare.mapper.BannerMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 首页轮播 banner 的读写服务。
 *
 * <h3>为什么读（active）与写（管理端 CRUD）放在同一个类</h3>
 * <p>知识库那边把读侧单独拆成 {@code KnowledgeService}，是因为索引侧有状态机、
 * 降级、Milvus 一致性这些中间态，读侧不该去理解它们。banner 没有这层复杂度 ——
 * 它就是一张小配置表的增删改查，拆开只会让「排序」这种同时涉及读与写的操作
 * 横跨两个类。所以保持单类。
 *
 * <h3>关于「上移/下移」为什么不叫 drag / reorder</h3>
 * <p>本项目<b>没有引入拖拽库</b>（前端零新增依赖）。交互是相邻两行交换 sort 值，
 * 所以接口语义就是「与相邻行交换」，而不是「提交一个新顺序数组」。
 * 后者需要前端把整个列表顺序发上来，一旦并发编辑就会互相覆盖。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BannerService {

    private static final int STATUS_ENABLED = 1;
    private static final int STATUS_DISABLED = 0;

    private final BannerMapper bannerMapper;

    /* ==================== 读侧 ==================== */

    /**
     * 首页要展示的 banner：启用中 + 在时间窗口内，按 sort 升序。
     *
     * <h3>时间窗口的谓词为什么写成这样</h3>
     * <p>{@code startAt} 与 {@code endAt} 都可能是 NULL，而 SQL 里
     * {@code NULL <= now()} 的结果是 NULL（不是 true），直接写
     * {@code .le(Banner::getStartAt, now)} 会把「未设开始时间」的行全部过滤掉 ——
     * 这正是最常被漏掉的一类 bug。所以两段条件都必须显式带上 {@code IS NULL} 分支：
     * <pre>
     *   (start_at IS NULL OR start_at &lt;= now)
     *   AND (end_at IS NULL OR end_at &gt;= now)
     * </pre>
     * <p>边界用闭区间（{@code <=} / {@code >=}）：运营填了「10:00 开始」，
     * 期望的是 10:00 那一刻就能看到，而不是 10:00:01。
     */
    public List<Banner> active() {
        LocalDateTime now = LocalDateTime.now();
        return bannerMapper.selectList(new LambdaQueryWrapper<Banner>()
                .eq(Banner::getStatus, STATUS_ENABLED)
                .and(w -> w.isNull(Banner::getStartAt).or().le(Banner::getStartAt, now))
                .and(w -> w.isNull(Banner::getEndAt).or().ge(Banner::getEndAt, now))
                .orderByAsc(Banner::getSort)
                .orderByAsc(Banner::getId));
    }

    /**
     * 管理端列表：<b>不</b>过滤启停与时间窗口。
     *
     * <p>管理端必须能看到「已停用的」和「已过期的」，否则运营点停用之后
     * 那条记录就消失了，既没法再启用，也看不出自己停过什么。
     * 是否在窗口内由前端根据 {@code startAt/endAt} 自行标注（列表里打标签），
     * 而不是靠后端过滤掉。
     */
    public List<Banner> listAll() {
        return bannerMapper.selectList(new LambdaQueryWrapper<Banner>()
                .orderByAsc(Banner::getSort)
                .orderByAsc(Banner::getId));
    }

    /* ==================== 写侧 ==================== */

    public Banner create(BannerRequest req) {
        Banner b = new Banner();
        applyRequest(b, req);
        if (req.getSort() == null) {
            // 未指定排序时排到末尾：取当前最大 sort + 1。
            // 不用 selectCount() 当序号 —— 删掉中间一条后会和已有的 sort 撞车。
            b.setSort(nextSort());
        }
        if (req.getStatus() == null) {
            b.setStatus(STATUS_ENABLED);
        }
        b.setIsDeleted(0);
        b.setCreatedAt(LocalDateTime.now());
        b.setUpdatedAt(LocalDateTime.now());
        bannerMapper.insert(b);
        log.info("[Banner] 新增 id={} title={} sort={} status={}",
                b.getId(), b.getTitle(), b.getSort(), b.getStatus());
        return b;
    }

    public Banner update(Long id, BannerRequest req) {
        Banner b = require(id);
        applyRequest(b, req);
        if (req.getSort() != null) {
            b.setSort(req.getSort());
        }
        if (req.getStatus() != null) {
            b.setStatus(req.getStatus());
        }
        b.setUpdatedAt(LocalDateTime.now());
        // 逻辑删除的实体不会被 updateById 更新到吗？—— 会。
        // updateById 只按主键匹配，不带 is_deleted 条件。
        // 但 require(id) 已经用 selectById 查过，而 selectById 受逻辑删除插件影响
        // （MyBatis-Plus 会给它加上 is_deleted=0），所以能走到这里的一定是活行。
        bannerMapper.updateById(b);
        log.info("[Banner] 编辑 id={} title={}", id, b.getTitle());
        return b;
    }

    /** 逻辑删除（isDeleted=1）。删除后首页立刻不再展示 */
    public void delete(Long id) {
        require(id);
        bannerMapper.deleteById(id);
        log.info("[Banner] 删除 id={}", id);
    }

    public Banner toggleStatus(Long id) {
        Banner b = require(id);
        int next = b.getStatus() != null && b.getStatus() == STATUS_ENABLED
                ? STATUS_DISABLED : STATUS_ENABLED;
        b.setStatus(next);
        b.setUpdatedAt(LocalDateTime.now());
        bannerMapper.updateById(b);
        log.info("[Banner] 启停切换 id={} status={}", id, next);
        return b;
    }

    /**
     * 与相邻行交换 sort（{@code direction = "up" | "down"}）。
     *
     * <h3>为什么要处理「相邻两行 sort 相同」</h3>
     * <p>历史数据或人工改库都可能出现两行 sort 相等（表里没有唯一约束，
     * 因为排序值本来就允许重复）。此时单纯交换数值会<b>什么都不变</b>
     * （a=b，swap 后还是 a=b）—— 用户点「上移」看到列表纹丝不动。
     * 所以这里在交换失败时做一次「重排」：把当前顺序整体重新编号 0,1,2…，
     * 用确定的稠密序号抹掉重复值。这一步是<b>幂等且无损</b>的。
     *
     * <h3>为什么不用「改成上一个 sort - 1」</h3>
     * <p>那样会让 sort 一路负增长，且多次「上移同一条」会不断产生更小的负值，
     * 序号失去直观意义。交换相邻值能保证 sort 始终是 0..n-1 的一个排列（前提是初值如此）。
     */
    public List<Banner> move(Long id, String direction) {
        Banner cur = require(id);
        boolean up = "up".equalsIgnoreCase(direction);
        if (!up && !"down".equalsIgnoreCase(direction)) {
            throw new BizException(400, "direction 只能是 up 或 down");
        }

        List<Banner> all = listAll();
        int idx = -1;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).getId().equals(id)) {
                idx = i;
                break;
            }
        }
        if (idx < 0) {
            throw new BizException(404, "banner 不存在");
        }
        int target = up ? idx - 1 : idx + 1;
        if (target < 0 || target >= all.size()) {
            // 已在首/末位：不是错误，直接返回当前顺序，前端按钮自己做禁用态
            return all;
        }

        Banner other = all.get(target);
        Integer curSort = cur.getSort() == null ? idx : cur.getSort();
        Integer otherSort = other.getSort() == null ? target : other.getSort();

        if (curSort.equals(otherSort)) {
            // 排序值重复 → 交换无意义，改用整体重排
            log.info("[Banner] 检测到重复 sort={}（id={} 与 id={}），改为整体重排",
                    curSort, cur.getId(), other.getId());
            resequence(all);
            return listAll();
        }

        cur.setSort(otherSort);
        other.setSort(curSort);
        LocalDateTime now = LocalDateTime.now();
        cur.setUpdatedAt(now);
        other.setUpdatedAt(now);
        bannerMapper.updateById(cur);
        bannerMapper.updateById(other);
        log.info("[Banner] {} id={}（sort {}↔{}）", up ? "上移" : "下移", id, curSort, otherSort);
        return listAll();
    }

    /* ==================== 工具 ==================== */

    private Banner require(Long id) {
        Banner b = bannerMapper.selectById(id);
        if (b == null) {
            throw new BizException(404, "banner 不存在或已删除");
        }
        return b;
    }

    private void applyRequest(Banner b, BannerRequest req) {
        if (req == null) {
            throw new BizException(400, "请求体不能为空");
        }
        if (req.getTitle() == null || req.getTitle().isBlank()) {
            throw new BizException(400, "请填写标题");
        }
        if (req.getImageUrl() == null || req.getImageUrl().isBlank()) {
            throw new BizException(400, "请上传图片");
        }
        // 时间窗口校验：给了两端就必须有序，否则这条 banner 永远不会出现，
        // 而表现和「上传失败」一模一样。挡在这里比让运营对着空首页猜好。
        if (req.getStartAt() != null && req.getEndAt() != null
                && req.getEndAt().isBefore(req.getStartAt())) {
            throw new BizException(400, "结束时间不能早于开始时间");
        }
        b.setTitle(req.getTitle().trim());
        b.setImageUrl(req.getImageUrl().trim());
        b.setLinkUrl(req.getLinkUrl() == null || req.getLinkUrl().isBlank()
                ? null : req.getLinkUrl().trim());
        b.setStartAt(req.getStartAt());
        b.setEndAt(req.getEndAt());
    }

    /** 下一个可用序号：当前最大 sort + 1（表空时为 0） */
    private int nextSort() {
        List<Banner> all = listAll();
        return all.stream()
                .map(x -> x.getSort() == null ? 0 : x.getSort())
                .max(Integer::compareTo)
                .map(m -> m + 1)
                .orElse(0);
    }

    /** 按当前展示顺序重新编号 0,1,2…（用于消除重复 sort） */
    private void resequence(List<Banner> ordered) {
        LocalDateTime now = LocalDateTime.now();
        int i = 0;
        for (Banner b : ordered) {
            if (b.getSort() == null || b.getSort() != i) {
                b.setSort(i);
                b.setUpdatedAt(now);
                bannerMapper.updateById(b);
            }
            i++;
        }
    }
}
