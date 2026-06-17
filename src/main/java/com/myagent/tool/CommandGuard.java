package com.myagent.tool;

import java.util.List;

/**
 * 命令安全守卫 - 拒绝危险命令
 */
public class CommandGuard {

    private static final List<String> DENIED_PATTERNS = List.of(
            "sudo ", "rm -rf /", "rm -rf /*", "mkfs",
            "dd if=", "dd of=/dev", ":(){ :|:& };:",
            "curl | sh", "curl|sh", "wget | sh", "wget|sh",
            "chmod 777 /", "shutdown", "reboot",
            "> /dev/sda", "format c:"
    );

    /**
     * 检查命令是否安全
     *
     * @return null 表示安全，否则返回拒绝原因
     */
    public static String check(String command) {
        if (command == null || command.isBlank()) {
            return "命令不能为空";
        }
        String lower = command.toLowerCase();
        for (String pattern : DENIED_PATTERNS) {
            if (lower.contains(pattern.toLowerCase())) {
                return "命令被安全策略拒绝: 包含危险模式 '" + pattern + "'";
            }
        }
        return null;
    }
}
