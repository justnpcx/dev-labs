package lab.jvm.support;

import org.springframework.asm.ClassWriter;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;

import java.util.ArrayList;
import java.util.List;

/**
 * 运行时动态生成类，用来撑爆 Metaspace。
 *
 * 为什么用 ASM 生成字节码，而不是 CGLIB / JDK Proxy？
 *  - JDK Proxy 内部有缓存，同一组接口只生成一个类，撑不爆 Metaspace
 *  - CGLIB 默认也走缓存，要 setUseCache(false)，且在 JDK 17 上定义类时
 *    可能撞上模块系统限制，需要额外 --add-opens
 *  - ASM 完全可控，行为确定，还能顺便看清"一个类到底占多少元数据"
 *
 * 生成的类会按批次放进独立的 LabClassLoader（每代 1000 个类）。
 * 调用 reset() 丢掉所有代之后，只要发生一次 Full GC，这些类和它们的元数据
 * 就会被整体卸载 —— 于是 Metaspace 演练可以反复做，不用重启进程。
 */
public final class ClassFactory {

    /**
     * 每个 ClassLoader 承载多少个类。
     * 太少 → ClassLoader 数量爆炸，本身开销就很大；
     * 太多 → 卸载粒度太粗，复位后释放不干净。
     */
    private static final int CLASSES_PER_LOADER = 1000;

    private static final List<LabClassLoader> GENERATIONS = new ArrayList<>();
    private static LabClassLoader currentLoader;
    private static int currentLoaderClassCount;

    private ClassFactory() {
    }

    /**
     * 生成一个独一无二的类。
     *
     * @param seq 序号，用于生成唯一类名
     * @return 新生成的 Class；调用方必须持有引用，否则它会被正常卸载，看不出泄漏
     */
    public static synchronized Class<?> generate(long seq) {
        if (currentLoader == null || currentLoaderClassCount >= CLASSES_PER_LOADER) {
            currentLoader = new LabClassLoader("lab-gen-" + GENERATIONS.size());
            GENERATIONS.add(currentLoader);
            currentLoaderClassCount = 0;
        }

        String binaryName = "lab.jvm.generated.LabGen$" + seq;
        Class<?> clazz = currentLoader.define(binaryName, bytecodeFor(seq, binaryName));
        currentLoaderClassCount++;
        return clazz;
    }

    private static byte[] bytecodeFor(long seq, String binaryName) {
        // 字节码里用的是斜杠分隔的内部名，和 defineClass 要的点号名不同，别搞混
        String internalName = binaryName.replace('.', '/');

        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V17,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                internalName,
                null,
                "java/lang/Object",
                null);

        // 默认构造器
        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(1, 1);
        ctor.visitEnd();

        // 一个静态方法，把 seq 塞进常量池，让每个类的元数据体积接近真实业务类
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "id", "()J", null, null);
        mv.visitCode();
        mv.visitLdcInsn(seq);
        mv.visitInsn(Opcodes.LRETURN);
        mv.visitMaxs(2, 0);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /** 当前一共开了几代 ClassLoader —— 这个数字直接对应"多少个加载器还活着"。 */
    public static synchronized int generationCount() {
        return GENERATIONS.size();
    }

    /**
     * 丢掉所有代。
     * 调用后这些 ClassLoader 变为不可达，下一次 Full GC 会把它们连同加载过的类一起卸载。
     *
     * @return 被丢弃的 ClassLoader 数量
     */
    public static synchronized int reset() {
        int generations = GENERATIONS.size();
        GENERATIONS.clear();
        currentLoader = null;
        currentLoaderClassCount = 0;
        return generations;
    }
}
