package io.github.truscdo.mixauth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 统一的日志工具类，自动适配运行环境。
 *
 * <p>
 * 在 Minecraft 运行环境中效果等同于 {@code com.mojang.logging.LogUtils.getLogger()}，
 * 在纯 JUnit 测试环境（无 Minecraft 类）中同样可用。
 * 内部通过解析调用栈自动确定调用类名，无需显式传入 {@code Class<?>}。
 * </p>
 *
 * <p>
 * 用法：
 * </p>
 * 
 * <pre>{@code
 * private static final Logger LOGGER = LogUtil.getLogger();
 * }</pre>
 */
public final class LogUtil {
    private LogUtil() {
    }

    /**
     * 为调用类创建一个 SLF4J Logger 实例。
     *
     * @return 以调用类全名命名的 Logger
     */
    public static Logger getLogger() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        // stack[0] = getStackTrace(), stack[1] = getLogger(), stack[2] = caller
        return LoggerFactory.getLogger(stack[2].getClassName());
    }
}
