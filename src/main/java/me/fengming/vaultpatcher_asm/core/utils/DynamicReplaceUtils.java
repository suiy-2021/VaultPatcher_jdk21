package me.fengming.vaultpatcher_asm.core.utils;

import me.fengming.vaultpatcher_asm.config.TargetClassInfo;
import me.fengming.vaultpatcher_asm.config.TranslationInfo;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@SuppressWarnings("unused")
public class DynamicReplaceUtils {

    // Java 21 中使用 StackWalker，启用 RETAIN_CLASS_REFERENCE 以获得最佳性能
    private static final StackWalker STACK_WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    // 缓存大小上限（防止无限制膨胀导致 OOM）
    private static final int MAX_CACHE_SIZE = 10_000;

    // 缓存：键 = original + "\0" + System.identityHashCode(info)，值 = 替换后的字符串（或 original 自身表示未匹配）
    private static final ConcurrentHashMap<String, String> CACHE = new ConcurrentHashMap<>();

    /**
     * 清空缓存，建议在动态翻译规则刷新后调用。
     */
    public static void clearCache() {
        CACHE.clear();
    }

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

            // 构造缓存键
            String cacheKey = original + '\0' + System.identityHashCode(info);
            String cached = CACHE.get(cacheKey);

            // 缓存命中逻辑
            if (cached != null) {
                // 若缓存的是替换结果（非 original），说明之前该规则已成功替换，直接返回
                if (!cached.equals(original)) {
                    Utils.printDebugInfo(-1, original, method, cached, "[CACHE]", info, null);
                    return cached;
                }
                // 若缓存的就是 original，说明之前该规则未匹配，跳过此规则继续检查下一条
                continue;
            }

            // ----- 未命中缓存，执行原始匹配逻辑 -----
            String replaced = MatchUtils.matchPairs(info.getPairs(), original, true);

            // 快速路径：无实际替换 -> 缓存 original 作为“未匹配”标记，并跳过该规则
            if (StringUtils.isBlank(replaced) || replaced.equals(original)) {
                cacheIfNotFull(cacheKey, original);
                continue;
            }

            TargetClassInfo tci = info.getTargetClassInfo();
            // 目标类信息缺失，视为全局替换规则
            if (tci == null) {
                cacheIfNotFull(cacheKey, replaced);
                Utils.printDebugInfo(-1, original, method, replaced, "[Global]", info, null);
                return replaced;
            }

            String configClass = tci.getDynamicName();
            String configMethod = tci.getMethod();
            TargetClassInfo.MatchMode mode = tci.getMatchMode();

            // 全局替换：未指定类名
            if (StringUtils.isBlank(configClass)) {
                cacheIfNotFull(cacheKey, replaced);
                Utils.printDebugInfo(-1, original, method, replaced, "[Global]", info, null);
                return replaced;
            }

            // 类匹配模式：需要遍历堆栈
            if (!framesFetched) {
                frames = STACK_WALKER.walk(s -> s.limit(20).toList());
                framesFetched = true;
            }

            if (frames != null) {
                if (mode == null) {
                    // 匹配模式未指定，无法进行类匹配，缓存为未匹配并跳过
                    cacheIfNotFull(cacheKey, original);
                    continue;
                }

                for (StackWalker.StackFrame ste : frames) {
                    // 方法名过滤
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
                        default:
                            break;
                    }

                    if (ok) {
                        cacheIfNotFull(cacheKey, replaced);
                        Utils.printDebugInfo(-1, original, method, replaced,
                                "[" + ste.getClassName() + "#" + ste.getMethodName() + "]",
                                info, null);
                        return replaced;
                    }
                }
            }

            // 堆栈遍历完毕仍未匹配：此规则不适用，缓存 original 并继续下一条规则
            cacheIfNotFull(cacheKey, original);
        }

        // 所有规则均未命中，返回原字符串
        return original;
    }

    /**
     * 向缓存中插入键值对，并确保缓存容量不超过上限。
     * 若已满，则清空整个缓存（简单粗暴但可靠）。
     */
    private static void cacheIfNotFull(String key, String value) {
        if (CACHE.size() >= MAX_CACHE_SIZE) {
            CACHE.clear();
        }
        CACHE.putIfAbsent(key, value);
    }
}