package lab.jvm.support;

/**
 * 模拟「被放进缓存却永不淘汰」的业务对象。
 *
 * 为什么单独定义成一个类，而不是直接存 byte[]？
 * 因为这样 jmap -histo 里会直接出现 lab.jvm.support.LeakEntry 这一行，
 * 实例数一路往上涨 —— 两次 histo 一对比就能定位到泄漏点。
 * 如果只存 byte[]，你看到的是一堆 [B，很难判断是谁在持有。
 */
public class LeakEntry {

    private final String key;
    private final byte[] payload;
    private final long createdAt;

    public LeakEntry(String key, byte[] payload) {
        this.key = key;
        this.payload = payload;
        this.createdAt = System.currentTimeMillis();
    }

    public String getKey() {
        return key;
    }

    public byte[] getPayload() {
        return payload;
    }

    public long getCreatedAt() {
        return createdAt;
    }
}
