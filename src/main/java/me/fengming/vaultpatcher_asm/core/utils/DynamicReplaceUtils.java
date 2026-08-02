package me.fengming.vaultpatcher_asm.core.utils;

import me.fengming.vaultpatcher_asm.config.TargetClassInfo;
import me.fengming.vaultpatcher_asm.config.TranslationInfo;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@SuppressWarnings("unused")
public class DynamicReplaceUtils {

    private static final StackWalker STACK_WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    private static final int MAX_CACHE_SIZE = 10_000;
    private static final ConcurrentHashMap<String, String> CACHE = new ConcurrentHashMap<>();

    public static void clearCache() {
        CACHE.clear();
    }

    public static String __mappingString(String original, String method) {
        if (original == null) return null;
        if (StringUtils.isBlank(original)) return original;

        List<TranslationInfo> infos = Utils.dynTranslationInfos;
        if (infos == null || infos.isEmpty()) {
            return original;
        }

        // 不可变快照，保证遍历期间规则集稳定（并发安全）
        List<TranslationInfo> snapshot = List.copyOf(infos);

        // 惰性抓取堆栈：只在首次需要时才抓取
        List<StackWalker.StackFrame> frames = null;
        boolean framesFetched = false;

        for (TranslationInfo info : snapshot) {
            if (info == null) continue;

            String cacheKey = original + '\0' + System.identityHashCode(info);
            String cached = CACHE.get(cacheKey);

            if (cached != null) {
                if (!cached.equals(original)) {
                    Utils.printDebugInfo(-1, original, method, cached, "[CACHE]", info, null);
                    return cached;
                }
                continue; // 缓存值为原串，表示该规则上次未匹配，跳过
            }

            // 执行匹配
            String replaced = MatchUtils.matchPairs(info.getPairs(), original, true);
            if (StringUtils.isBlank(replaced) || replaced.equals(original)) {
                safeCache(cacheKey, original);
                continue;
            }

            TargetClassInfo tci = info.getTargetClassInfo();
            if (tci == null) {
                safeCache(cacheKey, replaced);
                Utils.printDebugInfo(-1, original, method, replaced, "[Global]", info, null);
                return replaced;
            }

            String configClass = tci.getDynamicName();
            String configMethod = tci.getMethod();
            TargetClassInfo.MatchMode mode = tci.getMatchMode();

            if (StringUtils.isBlank(configClass)) {
                safeCache(cacheKey, replaced);
                Utils.printDebugInfo(-1, original, method, replaced, "[Global]", info, null);
                return replaced;
            }

            // 惰性抓取栈帧（仅当真正需要类匹配时）
            if (!framesFetched) {
                frames = STACK_WALKER.walk(s -> s.limit(20).toList());
                framesFetched = true;
            }

            if (frames != null && mode != null) {
                for (StackWalker.StackFrame ste : frames) {
                    if (!StringUtils.isBlank(configMethod) && !configMethod.equals(ste.getMethodName())) {
                        continue;
                    }

                    String cn = ste.getClassName();
                    boolean ok = false;
                    switch (mode) {
                        case FULL:    ok = cn.equals(configClass); break;
                        case STARTS:  ok = cn.startsWith(configClass); break;
                        case ENDS:    ok = cn.endsWith(configClass); break;
                        default:      break;
                    }

                    if (ok) {
                        safeCache(cacheKey, replaced);
                        Utils.printDebugInfo(-1, original, method, replaced,
                                "[" + ste.getClassName() + "#" + ste.getMethodName() + "]",
                                info, null);
                        return replaced;
                    }
                }
            }

            // 堆栈匹配失败，缓存原串表示该规则不适用
            safeCache(cacheKey, original);
        }

        return original;
    }

    /**
     * 缓存插入，满时整体清空（简单可靠）。
     */
    private static void safeCache(String key, String value) {
        if (CACHE.size() >= MAX_CACHE_SIZE) {
            CACHE.clear();
        }
        CACHE.putIfAbsent(key, value);
    }
}