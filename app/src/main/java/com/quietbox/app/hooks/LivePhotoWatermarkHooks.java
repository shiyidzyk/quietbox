package com.quietbox.app.hooks;

import androidx.annotation.Nullable;

import com.quietbox.app.Feature;
import com.quietbox.app.HookState;
import com.quietbox.app.anchor.AnchorKeys;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * EN-03 实况图去水印（默认关闭：开启后会切换实况保存链路）。
 *
 * <p>9.52.0 真机验证通过（25102RKBEC，当时类名为 zm7.j/dg7.h/p32.h/g42.e）：同一条
 * 实况笔记开/关对比，EN-03 开 → 3/3 段内嵌视频无水印，关 → 3/3 段有「小红书」logo
 * + 小红书号水印；静态帧两组均无水印（EN-01 覆盖）；批量保存 11 张 JPG 全部含
 * MotionPhoto 元数据，无崩溃、无 -4 失败。日志证实 {@code p32.h#a(1)→false} 与
 * {@code dg7.h.e/h set=2} 均命中，{@code zm7.j#c} 在 9.52.0 实况保存路径真实执行。
 *
 * <p>9.53.0 起的旧坐标（bt9.j / kl9.i / zk2.h / rl2.e）在 9.54.0 与 9.56.0 中均已只剩类型引用、
 * 没有类定义，属长期失效锚点；0.9.12 按 9.56.0 重新定位：入口
 * {@code b2a.j#b(Lv7a/m;Lhu9/i;Z)V}（264 条指令，日志 {@code imageSave start}，读 feedback
 * 全部 8 个字段），参数对象 {@code hu9.i}（字段 a~d=String、e=Z、f/g=String、h=Z，
 * 形状与旧 kl9.i 完全一致）；开关类 {@code em2.h#a(I)Z}（79 条，遍历
 * android_feature_close_switch_by_code 列表）；旧下载类 {@code wm2.e#d(String,Z)}。
 * 历史对应关系：zm7.j → ss9.j → bt9.j → b2a.j（入口方法 c → b → b）、
 * dg7.h → cl9.i → kl9.i → hu9.i、p32.h → wk2.h → zk2.h → em2.h、
 * g42.e → ol2.e → rl2.e → wm2.e。
 *
 * <p>9.52.0 实况保存入口反汇编（历史旧名，形状与当前一致；当前类名见上）：
 * <pre>
 *   ss9.j#c(ny9.m listener, cl9.i feedback, boolean saveStaticImage)
 *     // 1) 静态帧：cl9.i.e（disableWaterMark）与 cl9.i.h（disableAiWaterMark）
 *     //    作为布尔位传入 bm8.i.h（12 参默认参桥接，内部委托 11 参 bm8.i.i，
 *     //    对应 EN-01 的 index 5 / index 10），Canvas 合成时跳过水印绘制。
 *     // 2) 实况视频：cl9.i.g（livePhotoVideoPath）非空时：
 *     //      if (wk2.h#a(1))   // ConfigCenter android_feature_close_switch_by_code code=1
 *     //          新链路：ss9.j#f(cl9.i.f livePhotoFileId, ...) → POST live_photo/save，
 *     //                  视频由服务端渲染，可能带烧录水印；
 *     //      else
 *     //          旧链路：ol2.e#d(cl9.i.g, Z) → 直接下载 livePhotoVideoPath，
 *     //                  与笔记详情预览播放同源，是唯一可能无客户端水印的来源。
 * </pre>
 *
 * <p>两个挂载点：
 * <ol>
 *   <li>before {@code b2a.j#b}：把 hu9.i 参数对象的 e/h 反射置 true，静态帧走 EN-01
 *       同一条 Canvas 跳绘路径（e=true 同时绕过 bm8.i 的
 *       {@code if (!m && watermark == null) fail(-4)} fail-open 守卫，保存不会因此失败）。</li>
 *   <li>{@code em2.h#a(I)Z}：仅当参数 == {@link #LIVE_PHOTO_SAVE_SWITCH_CODE}（1
 *       确认 b2a.j#b 内 {@code const 1} 传入）时返回 false，强制走旧链路；其余 code
 *       一律 proceed 原值，不影响其他功能的关闭开关判断。</li>
 * </ol>
 *
 * <p>选择 em2.h#a(1) 而不是 hook 新链路换 URL 的原因：响应类与 hu9.i
 * 无任何引用关系，换 URL 需要跨 hook 传递状态（b2a.j#b 时暂存 g、响应时取用），
 * 时序脆弱；em2.h#a(1) 是链路分叉的唯一判定点，在判定处改写无状态且同时让
 * live_photo/save 请求不再发出。代价是 code=1 的其他读点（如有）也会读到 false——
 * 该 code 在 b2a.j#b 中的语义就是实况保存链路开关，9.52.0 真机验证未观察到其他影响；
 * 宿主后续若改保存链路（如下线 livePhotoVideoPath），本功能可能失效。
 *
 * <p>签名字段语义（9.53.0 dexsql 复核，9.56.0 复核形状未变）：hu9.i 为 8 参构造
 * {@code <init>(String,String,String,String,Z,String,String,Z)}，字段 a~d=String、
 * e=Z（disableWaterMark）、f=String（livePhotoFileId）、g=String（livePhotoVideoPath）、
 * h=Z（disableAiWaterMark），全部为跨类直接 iget/iput 的公开字段，可反射改写。
 */
final class LivePhotoWatermarkHooks {

    /** 日志用短名：入口第 2 参 FeedbackDownloadImage（9.56.0 为 hu9.i，9.53.0 为 kl9.i），字段 e/h 为水印开关。 */
    private static final String ENTRY_FEEDBACK = "hu9.i";

    /**
     * 实况保存链路开关 code：b2a.j#b 内 {@code const v5, 0x1} 传入 {@code em2.h#a(I)Z}。
     * em2.h#a 的语义：android_feature_close_switch_by_code 列表含该 code → false，
     * 否则 true；默认（服务端未下发）为 true 即新链路。
     */
    static final int LIVE_PHOTO_SAVE_SWITCH_CODE = 1;

    private LivePhotoWatermarkHooks() {
    }

    static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.LIVE_PHOTO_WATERMARK;
        int installed = 0;

        // 1) 入口改参：hu9.i.e / hu9.i.h 置 true，静态帧跳过水印绘制。
        installed += hookSaveEntry(module, f, cl);

        // 2) 链路开关：code=1 时返回 false，实况视频走旧链路（预览同源 URL）。
        installed += hookSaveSwitch(module, f, cl);

        if (installed == 0) {
            HookState.markNotFound(f, "实况水印挂载点未找到");
        }
    }

    /**
     * b2a.j#b before：改写 hu9.i 参数的水印开关位后按原参 proceed。
     * 改写发生在原方法执行前（hu9.i 实例随后被传入保存链路与开关判定，before 时改写生效）。
     */
    private static int hookSaveEntry(XposedModule module, Feature f, ClassLoader cl) {
        try {
            Method m = HookKit.findAnchorMethod(module, f, cl,
                    AnchorKeys.LIVE_PHOTO_SAVE_ENTRY, true);
            if (m == null) {
                return 0;
            }
            // install 返回 false（module.hook 抛异常等）时 HookKit 已记 skip，
            // 这里不能再自报挂载成功，否则 installed 计数与兜底日志失真。
            return HookKit.install(module, f, "livePhotoSaveEntry", m,
                    entryHooker(module, f)) ? 1 : 0;
        } catch (Throwable t) {
            HookKit.warn(module, f.id + " skip: live photo save entry anchor " + t);
            return 0;
        }
    }

    /**
     * 入口改参 hooker。只有真正置位了至少一个字段才记 hit；宿主字段改名/缺失时
     * set=0，{@link #applyFeedbackFlags} 已写 skip，不能再把失效报成命中。
     */
    static XposedInterface.Hooker entryHooker(XposedModule module, Feature f) {
        return new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                int set = 0;
                try {
                    List<Object> args = chain.getArgs();
                    if (args.size() >= 2) {
                        set = applyFeedbackFlags(f, args.get(1));
                    }
                    if (set > 0) {
                        HookKit.hit(module, f, ENTRY_FEEDBACK + ".e/h set=" + set);
                    }
                } catch (Throwable t) {
                    HookKit.error(module, f.id + " intercept error b2a.j#b", t);
                }
                // 改写的是参数对象内部字段，原参 proceed 即可。
                return chain.proceed();
            }
        };
    }

    /**
     * em2.h#a(I)Z：仅 code == {@link #LIVE_PHOTO_SAVE_SWITCH_CODE} 时返回 false（旧链路），
     * 其余 code 一律 proceed，避免全局返回 false 影响其他功能。
     */
    private static int hookSaveSwitch(XposedModule module, Feature f, ClassLoader cl) {
        try {
            Method m = HookKit.findAnchorMethod(module, f, cl,
                    AnchorKeys.LIVE_PHOTO_SAVE_SWITCH, true);
            if (m == null) {
                return 0;
            }
            // 同入口：只有 HookKit.install 真正挂上才计 1，失败时让 installed==0 的
            // 兜底 markNotFound 保持可达。label 用稳定字面量（F-120：第 3 参不得是
            // 拼接表达式），code 语义见 LIVE_PHOTO_SAVE_SWITCH_CODE。
            return HookKit.install(module, f, "livePhotoSaveSwitch", m,
                    new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            try {
                                List<Object> args = chain.getArgs();
                                if (args.size() == 1 && args.get(0) instanceof Integer
                                        && isLivePhotoSwitchCode((Integer) args.get(0))) {
                                    HookKit.hit(module, f, "em2.h#a(" + args.get(0) + ") → false");
                                    return Boolean.FALSE;
                                }
                            } catch (Throwable t) {
                                HookKit.error(module, f.id + " intercept error em2.h#a", t);
                            }
                            return chain.proceed();
                        }
                    }) ? 1 : 0;
        } catch (Throwable t) {
            HookKit.warn(module, f.id + " skip: anchor em2.h#a " + t);
            return 0;
        }
    }

    /** 仅实况保存链路开关的 code 命中；其他 code 不干预。 */
    static boolean isLivePhotoSwitchCode(int code) {
        return code == LIVE_PHOTO_SAVE_SWITCH_CODE;
    }

    /**
     * 反射把 feedback 对象（hu9.i）的公开 boolean 字段 e（disableWaterMark）与
     * h（disableAiWaterMark）置 true。抽成纯静态方法以便 JVM 单测
     * （LivePhotoWatermarkHooksTest 用字段形状相同的替身类验证）。
     *
     * @param target hu9.i 实例；null 或字段缺失时不动任何东西
     * @return 实际置位的字段数（0~2）；target 为 null 返回 -1
     */
    static int applyFeedbackFlags(@Nullable Object target) {
        return applyFeedbackFlags(null, target);
    }

    /**
     * 带诊断的重载：字段缺失/类型不符时给 f 记 skip，供诊断页定位宿主字段改名。
     * 传入 null Feature 时与 {@link #applyFeedbackFlags(Object)} 完全一致。
     */
    static int applyFeedbackFlags(@Nullable Feature f, @Nullable Object target) {
        if (target == null) {
            return -1;
        }
        return setBooleanTrue(f, target, "e") + setBooleanTrue(f, target, "h");
    }

    /** 单字段置 true；字段不存在、类型不符或反射失败都返回 0，不抛出。 */
    private static int setBooleanTrue(@Nullable Feature f, Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            if (field.getType() != boolean.class) {
                markFieldMiss(f, target, name, "类型不符");
                return 0;
            }
            field.setAccessible(true);
            field.setBoolean(target, true);
            return 1;
        } catch (Throwable t) {
            // 字段缺失时保存链路仍会继续（只是不去水印），但必须留下诊断原因，
            // 否则宿主改字段名后本功能会永久静默失效。
            markFieldMiss(f, target, name, "未找到");
            return 0;
        }
    }

    private static void markFieldMiss(@Nullable Feature f, Object target, String name, String why) {
        if (f != null) {
            HookState.markNotFound(f,
                    "field:" + name + " " + why + " " + target.getClass().getName());
        }
    }
}
