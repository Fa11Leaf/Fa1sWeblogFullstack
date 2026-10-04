package com.fa11leaf.blog.common;

import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 拼装。
 *
 * <p><b>为什么不用 Jackson：</b>本项目自己 {@code RestClient.builder()} 出来的客户端
 * 只带了 Spring 的默认消息转换器，没有 JSON 写入器（读取是好的，所以健康检查一直正常）。
 * 表现是请求确实发出去了、但 body 是空的，FastAPI 报
 * {@code 422 {"loc":["body"],"msg":"Field required"}}。
 * 与其去猜该往 Builder 里注册哪个转换器类（Spring Framework 7 换成了 Jackson 3，
 * 类名与注册方式都变过），不如把需要发的这几个结构直接拼出来：
 * String 有内置转换器，行为完全确定，也不依赖任何序列化器的自动探测。
 *
 * <p>用法上刻意"不智能"：所有值都要先经过 {@link #string}、{@link #bool}、
 * {@link #raw} 之一转成已编码的片段，再由 {@link #obj} 拼起来。
 * 这样不会出现"传了个对象但没人知道该怎么序列化"的情况。
 */
public final class Json {

    private Json() {
    }

    /** 字符串字面量（含首尾双引号）。 */
    public static String string(String raw) {
        if (raw == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(raw.length() + 16).append('"');
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    // 其余控制字符必须写成 \\uXXXX，直接输出会让整个 JSON 非法。
                    // 注意 < > & 不需要转义 —— 那是 HTML 里的讲究，
                    // 误加进去只会让正文里冒出一堆看不懂的 \u003c。
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    public static String bool(boolean value) {
        return value ? "true" : "false";
    }

    public static String number(long value) {
        return Long.toString(value);
    }

    /** 已经拼好的片段，原样嵌入。用于嵌套对象或数组。 */
    public static String raw(String alreadyEncoded) {
        return alreadyEncoded;
    }

    /** 对象：参数是「键, 已编码的值」交替出现。 */
    public static String obj(String... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("obj() 需要成对的键和值");
        }
        StringBuilder sb = new StringBuilder(64).append('{');
        for (int i = 0; i < keyValues.length; i += 2) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(string(keyValues[i])).append(':').append(keyValues[i + 1]);
        }
        return sb.append('}').toString();
    }

    /** 数组：元素必须已经是编码好的片段。 */
    public static String arr(List<String> encodedItems) {
        return "[" + String.join(",", encodedItems) + "]";
    }

    /** map 风格的对象，输入顺序即输出顺序（用 LinkedHashMap 时）。 */
    public static String obj(Map<String, String> encodedEntries) {
        StringBuilder sb = new StringBuilder(64).append('{');
        boolean first = true;
        for (Map.Entry<String, String> e : encodedEntries.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(string(e.getKey())).append(':').append(e.getValue());
        }
        return sb.append('}').toString();
    }
}
