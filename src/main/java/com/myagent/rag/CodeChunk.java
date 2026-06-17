package com.myagent.rag;

import java.nio.file.Path;

/**
 * 代码块 - RAG 检索的基本单元
 */
public class CodeChunk {
    private final String id;
    private final Path file;
    private final String level;  // "file" / "class" / "method"
    private final String content;
    private final int startLine;
    private final int endLine;
    private String name;

    public CodeChunk(String id, Path file, String level, String content, int startLine, int endLine) {
        this.id = id;
        this.file = file;
        this.level = level;
        this.content = content;
        this.startLine = startLine;
        this.endLine = endLine;
    }

    public String getId() { return id; }
    public Path getFile() { return file; }
    public String getLevel() { return level; }
    public String getContent() { return content; }
    public int getStartLine() { return startLine; }
    public int getEndLine() { return endLine; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}
