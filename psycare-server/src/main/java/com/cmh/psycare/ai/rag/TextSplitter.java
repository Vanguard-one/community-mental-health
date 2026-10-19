package com.cmh.psycare.ai.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 父子分块器（纯函数：不读库、不调模型、不看时钟）。
 *
 * <h3>为什么要父子两层</h3>
 * <p>检索单元要<b>小</b>（300–500 字）：块太大，向量被多个主题平均掉，语义变得模糊，
 * 相近问题的区分度下降；但生成上下文要<b>完整</b>：把 400 字的碎片直接喂给模型，
 * 它拿到的往往是半句话或没有前提的结论。所以检索用子块、生成用父块 ——
 * 命中子块后用 {@code parentId} 回表取父块正文，一次 {@code IN} 查询取 3–5 个，成本可忽略。
 *
 * <h3>为什么是纯函数</h3>
 * <p>切分参数（块大小 / overlap）在 Phase 4 要按 Recall@5 做网格搜索。纯函数意味着
 * 「换一组参数重跑」不需要清库、不需要起服务、也不依赖任何外部状态 ——
 * 这是让参数调优真正可做的前提。类里因此<b>没有任何</b> {@code @Component} 或依赖注入。
 *
 * <h3>切分优先级</h3>
 * <ol>
 *   <li>章节标题（Markdown {@code #}、{@code 第X节}、{@code 1.2 }）→ 决定 {@code section} 字段；</li>
 *   <li>空行分段 → 决定父块边界（不跨段落合并：段落边界是作者给出的语义边界）；</li>
 *   <li>中文句末标点 → 决定子块边界；</li>
 *   <li>硬切 → 兜底（单个句子本来就超长时，只能按长度切，但会记在日志里）。</li>
 * </ol>
 */
public final class TextSplitter {

    /**
     * 切分参数。
     *
     * @param childSize    子块目标字数（检索单元）
     * @param childOverlap 相邻子块的重叠字数（10–15%，避免一个答案被切在边界上）
     * @param parentSize   父块目标字数（生成上下文）
     * @param minChildSize 子块最小字数；低于它的尾块会并入上一块，不单独成块
     */
    public record Options(int childSize, int childOverlap, int parentSize, int minChildSize) {

        /** 计划 5.4 推荐的默认档（子 400 / 父 1400 / overlap 50） */
        public static Options defaults() {
            return new Options(400, 50, 1400, 120);
        }

        public Options {
            if (childSize <= 0 || parentSize < childSize) {
                throw new IllegalArgumentException("切分参数非法：childSize=" + childSize + " parentSize=" + parentSize);
            }
            if (childOverlap < 0 || childOverlap >= childSize) {
                throw new IllegalArgumentException("overlap 必须落在 [0, childSize) 内：" + childOverlap);
            }
        }
    }

    /**
     * 一个待落库的分块草稿。
     *
     * @param type           PARENT / CHILD
     * @param ordinal        文档内顺序（含父块与子块，从 0 连续递增）
     * @param parentOrdinal  所属父块的 ordinal；父块自身为 -1
     * @param section        章节路径
     * @param content        正文
     * @param charCount      字数
     * @param tokenCount     估算 token（与 {@code ContextAssembler} 同口径）
     */
    public record Draft(String type, int ordinal, int parentOrdinal, String section,
                        String content, int charCount, int tokenCount) {
    }

    public static final String TYPE_PARENT = "PARENT";
    public static final String TYPE_CHILD = "CHILD";

    /** 章节标题：Markdown 标题 / 「第X节|章|部分」/ 数字编号（1.2 / 3.） */
    private static final Pattern SECTION_PAT = Pattern.compile(
            "^(?:#{1,6}\\s*\\S|第[一二三四五六七八九十百零〇\\d]{1,4}\\s*[章节节篇部分]|\\d{1,2}(?:\\.\\d{1,2}){1,3}[\\s、.．]?\\S).{0,80}$");

    private TextSplitter() {
    }

    /* ==================== 主入口 ==================== */

    /**
     * 把一段纯文本切成父子块。
     *
     * @return 顺序列表：每个 PARENT 后紧跟它自己的 CHILD（调用方因此可以用
     *         「记住最近的父块 id」的方式回填 {@code parentId}，无需二次查找）
     */
    public static List<Draft> split(String rawText, Options options) {
        String text = normalize(rawText);
        if (text.isEmpty()) {
            return List.of();
        }

        List<Draft> out = new ArrayList<>();
        int ordinal = 0;

        for (Section section : groupSections(text)) {
            for (String parentText : packParents(section.body(), options.parentSize())) {
                String parentTrimmed = parentText.trim();
                if (parentTrimmed.isEmpty()) {
                    continue;
                }
                int parentOrdinal = ordinal;
                out.add(newDraft(TYPE_PARENT, ordinal++, -1, section.title(), parentTrimmed));

                for (String childText : splitChildren(parentTrimmed, options)) {
                    out.add(newDraft(TYPE_CHILD, ordinal++, parentOrdinal, section.title(), childText));
                }
            }
        }
        return out;
    }

    /* ==================== 步骤 1：清洗 ==================== */

    /**
     * 归一化文本。
     *
     * <p>三个都是有理由的：{@code CRLF} 不统一会让「按行处理」在 Windows 上出现空行错位；
     * BOM 与零宽字符是 PDF / 微信复制最常见的隐形污染，它们<b>不占可见宽度</b>，
     * 混进正文后 BM25 分词会多出莫名其妙的 token，而肉眼完全看不出来。
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.replace("\r\n", "\n").replace('\r', '\n');
        s = s.replace("\uFEFF", "").replace("\u200B", "").replace("\u200C", "");
        StringBuilder sb = new StringBuilder(s.length());
        int blank = 0;
        for (String line : s.split("\n", -1)) {
            String t = line.stripTrailing();
            if (t.isBlank()) {
                blank++;
                // 最多保留一个空行：连续空行在 PDF 抽取结果里可能成片出现，
                // 保留原样只会让「按空行分段」切出一堆空段。
                if (blank > 1) {
                    continue;
                }
                sb.append('\n');
            } else {
                blank = 0;
                sb.append(t).append('\n');
            }
        }
        return sb.toString().strip();
    }

    /* ==================== 步骤 2：章节 ==================== */

    private record Section(String title, String body) {
    }

    private static List<Section> groupSections(String text) {
        List<Section> sections = new ArrayList<>();
        String currentTitle = "";
        StringBuilder buf = new StringBuilder();

        for (String line : text.split("\n", -1)) {
            String t = line.strip();
            if (!t.isEmpty() && t.length() <= 100 && SECTION_PAT.matcher(t).matches()) {
                if (!buf.isEmpty()) {
                    sections.add(new Section(currentTitle, buf.toString()));
                    buf.setLength(0);
                }
                currentTitle = t;
            } else {
                buf.append(line).append('\n');
            }
        }
        if (!buf.isEmpty()) {
            sections.add(new Section(currentTitle, buf.toString()));
        }
        return sections;
    }

    /* ==================== 步骤 3：父块（按段落聚合） ==================== */

    private static List<String> packParents(String body, int parentSize) {
        List<String> parents = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String para : body.split("\n\\s*\n")) {
            String p = para.strip();
            if (p.isEmpty()) {
                continue;
            }
            if (cur.length() > 0 && cur.length() + p.length() > parentSize) {
                parents.add(cur.toString());
                cur.setLength(0);
            }
            if (cur.length() > 0) {
                cur.append("\n\n");
            }
            cur.append(p);
            // 单段本身就超长：先落一整段，避免与下一段无关内容挤在一起
            if (cur.length() >= parentSize) {
                parents.add(cur.toString());
                cur.setLength(0);
            }
        }
        if (cur.length() > 0) {
            // 尾块太短就并入上一块（父块只影响喂给模型的上下文量，不必强行独立）
            if (!parents.isEmpty() && cur.length() < parentSize / 3) {
                parents.set(parents.size() - 1, parents.get(parents.size() - 1) + "\n\n" + cur);
            } else {
                parents.add(cur.toString());
            }
        }
        return parents;
    }

    /* ==================== 步骤 4：子块（按句子） ==================== */

    private static List<String> splitChildren(String parentText, Options o) {
        List<String> sentences = splitSentences(parentText, o.childSize());
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();

        for (String s : sentences) {
            if (cur.length() > 0 && cur.length() + s.length() > o.childSize()) {
                String block = cur.toString().strip();
                if (!block.isEmpty()) {
                    out.add(block);
                }
                // overlap：把上一块末尾若干字作为下一块的前缀，避免答案正好落在切缝上
                String tail = tail(block, o.childOverlap());
                cur.setLength(0);
                cur.append(tail);
            }
            cur.append(s);
        }

        String rest = cur.toString().strip();
        if (!rest.isEmpty()) {
            if (!out.isEmpty() && rest.length() < o.minChildSize()) {
                // 尾块过短：并入上一块。不合并的话，库里会出现一堆「好的。」这种
                // 无检索价值的碎块，还会拉低向量平均质量。
                out.set(out.size() - 1, out.get(out.size() - 1) + rest);
            } else {
                out.add(rest);
            }
        }
        return out;
    }

    /**
     * 句子切分（保留标点）。
     *
     * <p>切分符包含换行：段落内的换行往往就是列表项的分界，比句号更能表达结构。
     * 单句超过 {@code maxLen} 时硬切兜底 —— 中文语料里长句（尤其是没有标点的
     * 表格行拼接）并不罕见，不兜底就会出现一个 2000 字的「句子」，
     * 之后按 childSize 判断时它直接被当成一整块，子块大小完全失控。
     */
    private static List<String> splitSentences(String text, int maxLen) {
        List<String> raw = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            cur.append(c);
            boolean end = c == '。' || c == '！' || c == '？' || c == '；'
                    || c == '!' || c == '?' || c == ';' || c == '\n';
            if (end) {
                raw.add(cur.toString());
                cur.setLength(0);
            }
        }
        if (!cur.isEmpty()) {
            raw.add(cur.toString());
        }

        List<String> out = new ArrayList<>(raw.size());
        for (String s : raw) {
            if (s.length() <= maxLen) {
                out.add(s);
                continue;
            }
            for (int i = 0; i < s.length(); i += maxLen) {
                out.add(s.substring(i, Math.min(s.length(), i + maxLen)));
            }
        }
        return out;
    }

    private static String tail(String s, int n) {
        if (n <= 0 || s.isEmpty()) {
            return "";
        }
        return s.length() <= n ? s : s.substring(s.length() - n);
    }

    /* ==================== 工具 ==================== */

    private static Draft newDraft(String type, int ordinal, int parentOrdinal, String section, String content) {
        int chars = content.length();
        return new Draft(type, ordinal, parentOrdinal, section, content, chars, estimateTokens(content));
    }

    /**
     * token 估算：中文按 1 字 ≈ 1 token（保守高估），其余按 4 字符 ≈ 1 token。
     *
     * <p>⚠️ 这个口径必须与 {@code ContextAssembler#estimate} 保持一致 ——
     * 两处估算的是同一件事（这份文本会占多少上下文预算），口径不同会让
     * 「分块时认为放得下、组装时又被裁掉」。将来若要抽公共工具类，两处一起改。
     */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) {
                cjk++;
            } else {
                other++;
            }
        }
        return cjk + (other / 4) + 8;
    }
}
