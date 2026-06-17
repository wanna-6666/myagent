package com.myagent.tool;

/**
 * 安全策略异常
 */
public class PolicyException extends RuntimeException {
    public PolicyException(String message) {
        super(message);
    }
}
