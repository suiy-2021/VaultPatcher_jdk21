package me.fengming.vaultpatcher_asm.core.utils;

import me.fengming.vaultpatcher_asm.config.TargetClassInfo;
import me.fengming.vaultpatcher_asm.config.TranslationInfo;

import java.util.List;

@SuppressWarnings("unused")
public class DynamicReplaceUtils {

    // Java 21 中使用 StackWalker，启用 RETAIN_CLASS_REFERENCE 以获得最佳性能
    private static final StackWalker STACK_WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    /**
     * 执行字符串动态替换。
     * <p>
     * 遍历配置的替换规则，按照规则顺序返回第一个匹配的结果。
     * 若无规则匹配或输入为空，则返回原字符串。
     * </p>
     *
     * @param original 原始字符串
     * @param method   当前方法名（如编译时常量）
     * @return 替换后的字符串，可能为 {@code original} 自身
     */
    public static String __mappingString(String original, String method) {
        if (original == null) return null;
        if (StringUtils.isBlank(original)) return original;

        // 获取规则快照，避免遍历期间集合被修改（并发安全）
        List<TranslationInfo> infos = Utils.dynTranslationInfos;
        if (infos == null) {
            return original;
        }
        // 使用不可变快照，保证本次调用中规则集稳定
        List<TranslationInfo> snapshot = List.copyOf(infos);

        // 惰性抓取堆栈：只在首次遇到需要类匹配的规则时才获取
        List<StackWalker.StackFrame> frames = null;
        boolean framesFetched = false;

        for (TranslationInfo info : snapshot) {
            if (info == null) {
                continue; // 防御性跳过 null 条目
            }

            String replaced = MatchUtils.matchPairs(info.getPairs(), original, true);
            if (StringUtils.isBlank(replaced) || replaced.equals(original)) {
                continue;
            }

            TargetClassInfo tci = info.getTargetClassInfo();
            // 如果目标类信息缺失，视为全局替换规则（原逻辑：configClass 为空时直接返回）
            if (tci == null) {
                Utils.printDebugInfo(-1, original, method, replaced, "[Global]", info, null);
                return replaced;
            }

            String configClass = tci.getDynamicName();
            String configMethod = tci.getMethod();
            TargetClassInfo.MatchMode mode = tci.getMatchMode();

            // 全局替换：未指定类名
            if (StringUtils.isBlank(configClass)) {
                Utils.printDebugInfo(-1, original, method, replaced, "[Global]", info, null);
                return replaced;
            }

            // 类匹配模式：需要遍历堆栈
            // 惰性抓取堆栈帧（首次需要时执行，且仅执行一次）
            if (!framesFetched) {
                frames = STACK_WALKER.walk(s -> s.limit(20).toList());
                framesFetched = true;
            }

            if (frames != null) {
                // 如果匹配模式未指定，该规则无法用于类匹配，跳过
                if (mode == null) {
                    continue;
                }

                for (StackWalker.StackFrame ste : frames) {
                    // 方法名过滤（如果配置了 method）
                    if (!StringUtils.isBlank(configMethod) && !configMethod.equals(ste.getMethodName())) {
                        continue;
                    }

                    String cn = ste.getClassName();
                    boolean ok = false;
                    switch (mode) {
                        case FULL:
                            ok = cn.equals(configClass);
                            break;
                        case STARTS:
                            ok = cn.startsWith(configClass);
                            break;
                        case ENDS:
                            ok = cn.endsWith(configClass);
                            break;
                        // 如有新增模式，可在此扩展
                        default:
                            // 未知匹配模式，视为不匹配
                            break;
                    }

                    if (ok) {
                        Utils.printDebugInfo(-1, original, method, replaced,
                                "[" + ste.getClassName() + "#" + ste.getMethodName() + "]",
                                info, null);
                        return replaced;
                    }
                }
            }
            // 若堆栈帧获取失败（极端情况 frames 为 null），或未匹配到任何帧，继续下一条规则
        }

        return original;
    }
}