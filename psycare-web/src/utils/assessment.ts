import { RISK_LEVEL_DESC } from '@/types'
import type { Assessment, Scale } from '@/types'

/**
 * 「匹配依据」那一行文案：`PSQI 睡眠质量量表 · 睡眠 · 风险等级 C 级（高风险）`。
 *
 * <p>为什么值得单独成函数：同一份推荐现在有两个入口 —— 独立结果页 `/ai-recommend`
 * 与问诊页的「医生」视图。这行字是用户判断「AI 凭什么推这几位」的唯一依据，
 * 两处措辞必须一模一样；各写一遍模板字符串，改文案时漏掉一处就会变成
 * 「同一份推荐，两处说法不同」，而这种不一致不会报错、也没人会发现。
 *
 * @param assessment 用于匹配的那次测评（无则返回空串，调用方自行隐藏这一条）
 * @param scale      对应量表；量表被删或还没加载出来时只退化为风险等级
 */
export function formatMatchBasis(
  assessment?: Assessment | null,
  scale?: Scale | null,
): string {
  if (!assessment) return ''
  const level = `${assessment.riskLevel} 级（${RISK_LEVEL_DESC[assessment.riskLevel] ?? assessment.riskLevel}）`
  return scale
    ? `${scale.name} · ${scale.dimension} · 风险等级 ${level}`
    : `风险等级 ${level}`
}
