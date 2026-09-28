package lab.jvm.support;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 一个「用完即弃」的类加载器。
 *
 * 为什么必须有它？—— 这是整个 Metaspace 演练的关键。
 *
 * 如果直接用 MethodHandles.Lookup#defineClass，类会被定义进应用主 ClassLoader。
 * 主 ClassLoader 在进程生命周期内永远可达，于是它加载过的类**永远无法卸载**，
 * Metaspace 只增不减 —— 演练变成一次性的，复位也没用。
 *
 * 换成这个独立的 ClassLoader 之后：
 *   引用一断 → ClassLoader 不可达 → 整代类和它的元数据一起被卸载。
 * 这正是真实世界里 Metaspace 泄漏的形态：热部署、插件系统、脚本引擎
 * 每加载一次就新建一个 ClassLoader，却因为某个静态引用忘了释放，
 * 旧 ClassLoader 一直活着，元空间就一路涨到 OOM。
 */
public class LabClassLoader extends ClassLoader {

    /** 保存自己定义过的字节码，findClass 时需要 */
    private final Map<String, byte[]> definitions = new ConcurrentHashMap<>();

    public LabClassLoader(String name) {
        // 父加载器用系统加载器，保证 java.lang.Object 等核心类可见
        super(name, ClassLoader.getSystemClassLoader());
    }

    /**
     * 定义一个新类。
     *
     * @param binaryName 二进制名（点号分隔），必须与字节码里的类名一致
     * @param bytecode   ASM 生成的字节码
     */
    public Class<?> define(String binaryName, byte[] bytecode) {
        definitions.put(binaryName, bytecode);
        return defineClass(binaryName, bytecode, 0, bytecode.length);
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        byte[] bytecode = definitions.get(name);
        if (bytecode == null) {
            throw new ClassNotFoundException(name);
        }
        return defineClass(name, bytecode, 0, bytecode.length);
    }
}
