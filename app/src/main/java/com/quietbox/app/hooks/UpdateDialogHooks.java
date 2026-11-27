package com.quietbox.app.hooks;

import com.quietbox.app.Feature;
import com.quietbox.app.HookState;
import com.quietbox.app.anchor.AnchorKeys;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * UI-04 更新弹窗屏蔽。
 * onCreate 不能跳过：super.onCreate 只在静态 de() 内被调用一次，
 * 跳过会让 Activity.mCalled 校验抛 SuperNotCalledException。
 * 因此 onCreate 只 finish() 后照常 proceed；ke() 是普通 void（9.54.0 名 oe、9.56.0 名 ke），可安全跳过。
 */
final class UpdateDialogHooks {

    private UpdateDialogHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.UPDATE_DIALOG_BLOCK;
        try {
            Method onCreate = HookKit.findAnchorMethod(module, f, cl,
                    AnchorKeys.UPDATE_DIALOG_ON_CREATE, true);
            // onCreate 是 Activity 生命周期唯一入口，缺失即整个弹窗屏蔽失效。
            // findAnchorMethod(..., true) 已记 critical；install 收到 null 时还会再记一条
            // 普通 skip，这里显式补 critical，保证失败横幅一定亮（即便 UI-04 会话里
            // 还有别的挂载点注册成功）。
            if (onCreate == null) {
                HookState.markNotFoundCritical(f, "锚点未找到 " + AnchorKeys.UPDATE_DIALOG_ON_CREATE);
            }
            HookKit.install(module, f, "onCreate", onCreate,
                    HookKit.finishThenProceed(module, f, "onCreate(finish)", null));
            Method he = HookKit.findAnchorMethod(module, f, cl,
                    AnchorKeys.UPDATE_DIALOG_HE, true);
            if (he == null) {
                HookState.markNotFoundCritical(f, "锚点未找到 " + AnchorKeys.UPDATE_DIALOG_HE);
            }
            HookKit.install(module, f, "ke", he, HookKit.skipVoid(module, f, "ke"));
        } catch (Throwable t) {
            HookKit.error(module, f.id + " install aborted", t);
            // install 整段抛错时 UI-04 也必须进失败横幅，不能静默。
            HookState.markNotFoundCritical(f, "安装异常 " + t);
        }
    }
}
