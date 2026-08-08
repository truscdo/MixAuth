package io.github.truscdo.mixauth.gametest;

import net.minecraft.gametest.framework.GameTest;
import net.neoforged.testframework.DynamicTest;
import net.neoforged.testframework.annotation.TestHolder;
import net.neoforged.testframework.gametest.EmptyTemplate;

/**
 * M1 冒烟测试：验证 testframework 在 gameTestServer 中正常加载、测试被发现并执行。
 * <p>
 * 方法式测试的组成：{@code @TestHolder}（注册测试）+ {@code @GameTest}（接入 vanilla
 * GameTest 框架）+ {@code @EmptyTemplate}（自动注册空结构模板，生成
 * {@code mixauth:empty_3x3x3}）。
 */
public final class SmokeGameTest {
    @TestHolder(description = { "冒烟：验证 testframework 加载、测试发现与执行" })
    @GameTest(template = "empty_3x3x3")
    @EmptyTemplate
    static void smoke(final DynamicTest test) {
        test.onGameTest(helper -> {
            helper.succeed();
        });
    }
}
