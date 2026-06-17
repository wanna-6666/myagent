package com.myagent.rag;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/**
 * 三级代码切块器 - 文件 → 类 → 方法（JavaParser AST）
 */
public class CodeChunker {

    private static final Set<String> EXCLUDED_DIRS = Set.of(
            ".git", "target", "node_modules", "dist", "build", ".idea", ".gradle", ".mvn"
    );

    public List<CodeChunk> chunkProject(String projectPath) throws IOException {
        Path root = Path.of(projectPath).toAbsolutePath().normalize();
        List<CodeChunk> chunks = new ArrayList<>();
        int[] idCounter = {0};

        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (EXCLUDED_DIRS.contains(dir.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String name = file.getFileName().toString();
                if (name.endsWith(".java") && attrs.size() < 500_000) {
                    try {
                        chunks.addAll(chunkFile(file, root, idCounter));
                    } catch (Exception ignored) {}
                }
                return FileVisitResult.CONTINUE;
            }
        });

        return chunks;
    }

    public List<CodeChunk> chunkFile(Path file, Path projectRoot, int[] idCounter) throws IOException {
        String source = Files.readString(file, StandardCharsets.UTF_8);
        String relPath = projectRoot.relativize(file).toString();
        List<CodeChunk> chunks = new ArrayList<>();
        int totalLines = source.split("\n", -1).length;

        // Level 1: 文件级
        chunks.add(new CodeChunk(
                "chunk-" + (idCounter[0]++), file, "file", source, 1, totalLines));

        // Level 2 & 3: AST 解析
        try {
            CompilationUnit cu = StaticJavaParser.parse(source);

            for (ClassOrInterfaceDeclaration clazz : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                int classStart = clazz.getBegin().map(p -> p.line).orElse(1);
                int classEnd = clazz.getEnd().map(p -> p.line).orElse(totalLines);

                CodeChunk classChunk = new CodeChunk(
                        "chunk-" + (idCounter[0]++), file, "class",
                        clazz.toString(), classStart, classEnd);
                classChunk.setName(clazz.getNameAsString());
                chunks.add(classChunk);

                for (MethodDeclaration method : clazz.getMethods()) {
                    int mStart = method.getBegin().map(p -> p.line).orElse(classStart);
                    int mEnd = method.getEnd().map(p -> p.line).orElse(classEnd);

                    CodeChunk methodChunk = new CodeChunk(
                            "chunk-" + (idCounter[0]++), file, "method",
                            method.toString(), mStart, mEnd);
                    methodChunk.setName(clazz.getNameAsString() + "." + method.getNameAsString());
                    chunks.add(methodChunk);
                }
            }
        } catch (Exception e) {
            // AST 解析失败，只保留文件级 chunk
        }

        return chunks;
    }
}
