// AI生成
package com.quietbox.app.anchor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Assume;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 宿主版本门禁契约（纯 JVM）：tools/check-mounts.sh 读到宿主 versionName 后必须
 * 与目标版本 9.53.0 比对，不符默认硬失败（退出码 2），只有显式 --allow-version /
 * CHECK_MOUNTS_ALLOW_VERSION=1 才放行。
 *
 * <p>历史行为是只 echo WARN 不退出：宿主 APK 版本漂移后 check-mounts 仍 exit 0，
 * release.sh 的 mount_gate 只拦 dexsql 失败，拦不住「锚点表与实际宿主不一致」，
 * 模块装到真机才静默失效（对齐 CloudMusicTool 的 --allow-version 硬门禁）。
 * 本类把新约束固化成单测：
 * 1) 版本比对块确实存在、且不符时 exit 2（不是只 WARN）；
 * 2) 放行开关在 usage 与参数解析里都注册了（--allow-version + 环境变量）；
 * 3) 门禁比对的目标版本与 anchors.json 的 appVersion 一致；
 * 4) 行为验证：个别名 9.54.0 的假 APK 默认 exit 2，--allow-version 后才放行。
 */
public class CheckMountsVersionGateTest {

    private static final String SCRIPT_RELATIVE = "tools/check-mounts.sh";
    private static final String ANCHORS_RELATIVE = "app/src/main/resources/anchors.json";
    private static final String EXPECTED_VERSION = "9.54.0";
    private static final String DRIFTED_VERSION = "9.55.0";

    private static final Pattern APP_VERSION =
            Pattern.compile("\"appVersion\"\\s*:\\s*\"([^\"]+)\"");

    /** 从测试工作目录向上找仓库文件（Gradle 单测 cwd 多为 app/ 模块目录）。 */
    private static File findRepoFile(String relative) {
        File dir = new File(System.getProperty("user.dir", ".")).getAbsoluteFile();
        while (dir != null) {
            File candidate = new File(dir, relative);
            if (candidate.isFile()) {
                return candidate;
            }
            dir = dir.getParentFile();
        }
        fail("找不到 " + relative + "（cwd=" + System.getProperty("user.dir") + "）");
        return null;
    }

    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    /** 版本门禁块：versionName 回显之后、读不到 versionName 的降级分支之前。 */
    private static String versionGateBlock(String script) {
        int start = script.indexOf("echo \"versionName: $VERSION_NAME\"");
        assertTrue("check-mounts.sh 缺少 versionName 回显（门禁位置已漂移？）", start >= 0);
        int end = script.indexOf("cannot read versionName", start);
        assertTrue("check-mounts.sh 缺少读不到 versionName 的分支", end > start);
        return script.substring(start, end);
    }

    private static String firstGroup(String text, Pattern pattern) {
        Matcher matcher = pattern.matcher(text);
        assertTrue("无法从文本中提取 " + pattern.pattern(), matcher.find());
        return matcher.group(1);
    }

    @Test
    public void versionMismatchIsHardFailureNotWarn() throws Exception {
        String gate = versionGateBlock(read(findRepoFile(SCRIPT_RELATIVE)));
        assertTrue("门禁必须比对 versionName 与 " + EXPECTED_VERSION,
                gate.contains("\"$VERSION_NAME\" != \"" + EXPECTED_VERSION + "\""));
        assertTrue("版本不符必须硬失败（exit 2），不能再只 WARN 放行",
                gate.contains("exit 2"));
        assertTrue("版本不符必须打印 ERROR 行供 release.sh/CI 抓取",
                gate.contains("ERROR: expected " + EXPECTED_VERSION));
        assertTrue("放行时必须显式点名 --allow-version（放行原因可追溯）",
                gate.contains("--allow-version"));
    }

    @Test
    public void allowVersionSwitchIsRegistered() throws Exception {
        String script = read(findRepoFile(SCRIPT_RELATIVE));
        assertTrue("必须支持 --allow-version 参数",
                script.contains("--allow-version)"));
        assertTrue("必须支持 CHECK_MOUNTS_ALLOW_VERSION 环境变量",
                script.contains("ALLOW_VERSION=\"${CHECK_MOUNTS_ALLOW_VERSION:-}\""));
        assertTrue("usage 必须文档化 --allow-version",
                script.contains("--allow-version    宿主 versionName"));
    }

    @Test
    public void gateTargetsAnchorsTableVersion() throws Exception {
        String script = read(findRepoFile(SCRIPT_RELATIVE));
        assertEquals("check-mounts.sh 硬编码的目标版本必须与 anchors.json appVersion 一致"
                        + "（表升级后忘了改脚本时，门禁拦的正是这个漂移）",
                firstGroup(read(findRepoFile(ANCHORS_RELATIVE)), APP_VERSION), EXPECTED_VERSION);
        assertTrue("脚本里必须出现目标版本 " + EXPECTED_VERSION,
                script.contains(EXPECTED_VERSION));
    }

    /**
     * 行为验证：versionName 漂移的假 APK，默认必须 exit 2。
     *
     * <p>脚本拷贝到临时根目录执行：REPO_ROOT 由 BASH_SOURCE 推导，这样即使放行后
     * 继续往下跑，DexSQL 缓存也只落在临时目录里，不碰仓库内 .dexsql-cache。
     */
    @Test
    public void driftedHostVersionFailsByDefault() throws Exception {
        String bash = findBash();
        Assume.assumeTrue("未找到 bash，跳过 check-mounts.sh 门禁行为验证", bash != null);
        File root = newFolder("cm-gate-");
        File script = new File(root, "check-mounts.sh");
        Files.copy(findRepoFile(SCRIPT_RELATIVE).toPath(), script.toPath());
        File apk = writeFile(root, "host.apk", "not-an-apk");
        File aapt2 = fakeAapt2(root, DRIFTED_VERSION);
        File dexsql = fakeDeXsql(root);
        makeExecutable(bash, aapt2);
        makeExecutable(bash, dexsql);

        Result result = run(bash, script, apk, aapt2, dexsql, null);
        assertTrue("宿主版本 " + DRIFTED_VERSION + " 与 " + EXPECTED_VERSION
                        + " 不符时必须 exit 2，实际 exit=" + result.exitCode
                        + "\n" + result.output,
                result.exitCode == 2);
        assertTrue("必须打印 ERROR: expected " + EXPECTED_VERSION,
                result.output.contains("ERROR: expected " + EXPECTED_VERSION
                        + ", got " + DRIFTED_VERSION));
    }

    /** 行为验证：--allow-version 后同一 APK 必须越过版本门禁继续跑。 */
    @Test
    public void allowVersionLetsTheGatePass() throws Exception {
        String bash = findBash();
        Assume.assumeTrue("未找到 bash，跳过 check-mounts.sh 门禁行为验证", bash != null);
        File root = newFolder("cm-gate-");
        File script = new File(root, "check-mounts.sh");
        Files.copy(findRepoFile(SCRIPT_RELATIVE).toPath(), script.toPath());
        File apk = writeFile(root, "host.apk", "not-an-apk");
        File aapt2 = fakeAapt2(root, DRIFTED_VERSION);
        File dexsql = fakeDeXsql(root);
        makeExecutable(bash, aapt2);
        makeExecutable(bash, dexsql);

        Result result = run(bash, script, apk, aapt2, dexsql, "--allow-version");
        assertTrue("--allow-version 后不应再被版本门禁拦下（exit="
                        + result.exitCode + "）\n" + result.output,
                result.exitCode != 2);
        assertFalse("放行后不得再打印版本不符 ERROR",
                result.output.contains("ERROR: expected " + EXPECTED_VERSION));
        assertTrue("放行必须打印带开关名的 WARN",
                result.output.contains("--allow-version 放行"));
    }

    // ------------------------------------------------------------ 辅助

    private static final class Result {
        final int exitCode;
        final String output;

        Result(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }
    }

    private static Result run(String bash, File script, File apk, File aapt2, File dexsql,
                              String extra) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(bash);
        command.add(posix(script));
        command.add("--apk");
        command.add(posix(apk));
        command.add("--anchors");
        command.add(posix(findRepoFile(ANCHORS_RELATIVE)));
        command.add("--aapt2");
        command.add(posix(aapt2));
        command.add("--dexsql");
        command.add(posix(dexsql));
        if (extra != null) {
            command.add(extra);
        }
        ProcessBuilder builder = new ProcessBuilder(command);
        // 避免测试机上的 TARGET_APK / APK / DEXSQL / AAPT2 干扰判定。
        builder.environment().put("TARGET_APK", "");
        builder.environment().put("APK", "");
        builder.environment().put("DEXSQL", "");
        builder.environment().put("AAPT2", "");
        builder.environment().put("CHECK_MOUNTS_ALLOW_VERSION", "");
        // 版本门禁之后脚本还要解析 anchors（依赖 python）；Gradle worker 的 PATH
        // 不一定有 python3/python，这里放一个只 exit 0 的假解释器，专测版本门禁语义。
        File fakePython = new File(script.getParentFile(), "python3");
        Files.write(fakePython.toPath(),
                "#!/usr/bin/env bash\nexit 0\n".getBytes(StandardCharsets.UTF_8));
        makeExecutable(bash, fakePython);
        builder.environment().put("PYTHON", toPosix(fakePython.getAbsolutePath()));
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = readAll(process.getInputStream());
        int exitCode = process.waitFor();
        return new Result(exitCode, output);
    }

    /** 假 aapt2：只回显 aapt2 dump badging 样式的 versionName 行。 */
    private static File fakeAapt2(File dir, String versionName) throws Exception {
        File script = new File(dir, "aapt2");
        Files.write(script.toPath(), ("#!/usr/bin/env bash\n"
                + "echo \"package: name='com.xingin.xhs' versionCode='9530807'"
                + " versionName='" + versionName + "'\"\n").getBytes(StandardCharsets.UTF_8));
        return script;
    }

    /** 假 dexsql：exit 0，只为通过 is_executable_file 校验，不真正建缓存。 */
    private static File fakeDeXsql(File dir) throws Exception {
        File script = new File(dir, "dexsql");
        Files.write(script.toPath(),
                "#!/usr/bin/env bash\nexit 0\n".getBytes(StandardCharsets.UTF_8));
        return script;
    }

    /** MSYS/Cygwin 需要的执行位：bash 认为有 shebang 或 exec 位才算可执行。 */
    private static void makeExecutable(String bash, File file) throws Exception {
        if (bash == null) {
            file.setExecutable(true);
            return;
        }
        Process process = new ProcessBuilder(bash, "-c",
                "chmod +x \"" + file.getAbsolutePath().replace('\\', '/') + "\"")
                .redirectErrorStream(true).start();
        process.getInputStream().close();
        process.waitFor();
    }

    /** MSYS/Git bash 不认 Windows 反斜杠路径，统一转成 C:/... 形式。 */
    private static String posix(File file) {
        return file.getAbsolutePath().replace('\\', '/');
    }

    private static String findBash() {
        List<String> candidates = new ArrayList<>();
        // Windows 上 Java 的 CreateProcess 会先命中 C:\Windows\System32\bash.exe（WSL）：
        // 它既打不开 posix() 产出的 C:/... 脚本路径，又缺少 python/cygpath，脚本会以
        // exit 127「No such file or directory」告终。优先挑 PATH 里非 System32 的
        // MSYS Git bash.exe（能识别 C:/... 并带齐工具）；Linux/macOS 没有 bash.exe，
        // 回落到系统自带的 "bash"。
        String path = System.getenv("PATH");
        if (path != null) {
            for (String entry : path.split(File.pathSeparator)) {
                if (entry.toLowerCase().contains("system32")) {
                    continue;
                }
                File dir = new File(entry);
                File direct = new File(dir, "bash.exe");
                if (direct.isFile()) {
                    candidates.add(direct.getAbsolutePath());
                }
                // PATH 里常见的是 <Git>/cmd，真正的 bash 在 <Git>/bin。
                File parent = dir.getParentFile();
                if (parent != null) {
                    File inBin = new File(new File(parent, "bin"), "bash.exe");
                    if (inBin.isFile()) {
                        candidates.add(inBin.getAbsolutePath());
                    }
                }
            }
        }
        String gitBash = System.getenv("ProgramFiles");
        if (gitBash != null) {
            candidates.add(gitBash + "\\Git\\bin\\bash.exe");
        }
        candidates.add("C:\\Program Files\\Git\\bin\\bash.exe");
        candidates.add("bash"); // POSIX 环境：系统 bash 直接可用
        for (String candidate : candidates) {
            try {
                Process process = new ProcessBuilder(candidate, "--version")
                        .redirectErrorStream(true).start();
                boolean msys = isMsys(new String(process.getInputStream().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8));
                if (process.waitFor() == 0 && msys) {
                    return candidate;
                }
            } catch (Exception ignored) {
                // 尝试下一个候选
            }
        }
        return null;
    }

    /** WSL 的 bash 也能 --version 成功，但它访问不了 C:/... 这种 Windows 临时路径。 */
    private static boolean isMsys(String banner) {
        return banner.contains("msys") || banner.contains("MSYS")
                || banner.contains("MINGW") || banner.contains("CYGWIN");
    }

    /** 在 PATH 里找一个真实可用的 python（跳过 WindowsApps 的商店别名）。 */
    private static String findPython() {
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String entry : path.split(File.pathSeparator)) {
            if (entry.toLowerCase().contains("windowsapps")) {
                continue;
            }
            for (String name : new String[]{"python3.exe", "python.exe", "python3", "python"}) {
                File candidate = new File(entry, name);
                if (candidate.isFile()) {
                    return toPosix(candidate.getAbsolutePath());
                }
            }
        }
        return null;
    }

    /** MSYS bash 的 command -v 不认 C:\...，转成 /c/... 形式。 */
    private static String toPosix(String win) {
        String p = win.replace('\\', '/');
        if (p.length() > 1 && p.charAt(1) == ':') {
            p = "/" + Character.toLowerCase(p.charAt(0)) + p.substring(2);
        }
        return p;
    }

    private static File newFolder(String prefix) throws Exception {
        return Files.createTempDirectory(prefix).toFile();
    }

    private static File writeFile(File dir, String name, String content) throws Exception {
        File file = new File(dir, name);
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static String readAll(InputStream stream) throws Exception {
        StringBuilder text = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            char[] buffer = new char[1024];
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                text.append(buffer, 0, read);
            }
        }
        return text.toString();
    }
}
