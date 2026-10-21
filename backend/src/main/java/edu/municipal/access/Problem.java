// Typed process failure translated by ApiErrors into stable HTTP status and safe JSON messages.

package edu.municipal.access;
public class Problem extends RuntimeException {
    public final int status;
    public final String code;
    public Problem(int status, String code, String message) {
        super(message); this.status = status; this.code = code;
    }
    public static Problem bad(String message) { return new Problem(400, "INVALID_INPUT", message); }
    public static Problem missing() { return new Problem(404, "NOT_FOUND", "对象不存在"); }
    public static Problem conflict() { return new Problem(409, "VERSION_CONFLICT", "配置已经变化，请重新加载后再保存"); }
}
