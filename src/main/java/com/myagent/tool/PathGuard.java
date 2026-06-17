package com.myagent.tool;

import java.nio.file.Path;

/**
 * 路径安全守卫 - 限定所有文件操作必须在项目目录内
 */
public class PathGuard {

    private final Path projectRoot;

    public PathGuard(String projectPath) {
        this.projectRoot = Path.of(projectPath).toAbsolutePath().normalize();
    }

    /**
     * 解析并验证路径安全性，防止路径穿越攻击
     *
     * @throws PolicyException 路径越界时抛出
     */
    public Path resolveSafe(String path) {
        if (path == null || path.isBlank()) {
            throw new PolicyException("路径不能为空");
        }
        Path resolved = projectRoot.resolve(path).normalize();
        if (!resolved.startsWith(projectRoot)) {
            throw new PolicyException("路径越界: " + path + " 不在项目目录 " + projectRoot + " 内");
        }
        return resolved;
    }

    public Path getRootPath() {
        return projectRoot;
    }
}
