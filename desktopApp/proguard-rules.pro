# PikSeek 桌面端 release 的 ProGuard 规则（承自 Piko）。只做裁剪，不混淆：体积主要来自没用到的图标与
# Compose 组件，混淆只再省几个百分点，却让崩溃栈难读、反射查找更易出错。
-dontobfuscate

# JNA 按接口方法名反射绑定 native 函数，结构体按字段名映射内存布局。
# mediamp 用它调 kernel32 的 SetDllDirectoryW，mpv 绑定也走 JNA
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.Library { *; }
-keep class * implements com.sun.jna.Callback { *; }
-keep class * extends com.sun.jna.Structure { *; }
-dontwarn com.sun.jna.**

# mediamp：mpv 的 JNI 回调按类名与方法签名从 native 侧查找，播放器工厂与画面表面经 ServiceLoader 装载
-keep class org.openani.mediamp.** { *; }
-dontwarn org.openani.mediamp.**

# zstd-jni 的原生代码按字段名读写 nativePtr 等字段，这些字段在 Java 侧没有读者
-keep class com.github.luben.zstd.** { *; }
-dontwarn com.github.luben.zstd.**

# 自绘标题栏的窗口过程按名字经 MethodHandles.findVirtual 取出、交给 FFM 做 upcall，
# 代码里没有直接调用，不保留就被当作无用方法裁掉，release 包退回系统标题栏
-keepclassmembers class dev.piko.desktop.winrt.WindowsCaption {
    long frameProc(java.lang.foreign.MemorySegment, int, long, long);
    long childProc(java.lang.foreign.MemorySegment, int, long, long);
}

# 触摸桥（compose-windows-touch）经反射取 Compose Desktop 的内部入口，注入 WM_POINTER 的触摸。
# 取不到时静默退回鼠标路径，release 里看不出来，所以这些成员要显式保留
-keepclassmembers class androidx.compose.ui.awt.** {
    *** composePanel;
    *** _composeContainer;
    *** mediator;
    *** getContentComponent();
}
-keepclassmembers class androidx.compose.ui.scene.ComposeSceneMediator {
    *** scene$delegate;
    *** getSceneBoundsInPx();
    static *** access$getScene(androidx.compose.ui.scene.ComposeSceneMediator);
}

# ServiceLoader 装载的实现类。ProGuard 不像 R8 那样自动保留 META-INF/services 里列出的类
-keep class coil3.network.okhttp.internal.OkHttpNetworkFetcherServiceLoaderTarget { *; }
-keep class io.ktor.client.engine.okhttp.OkHttpEngineContainer { *; }
-keep class io.ktor.serialization.kotlinx.json.KotlinxSerializationJsonExtensionProvider { *; }
-keep class org.slf4j.simple.SimpleServiceProvider { *; }

# kotlinx.serialization：生成的 serializer 经伴生对象的 serializer() 查找
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-keepclassmembers class * {
    *** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class **$$serializer { *; }

# Ktor 与 OkHttp 引用了桌面 JVM 上不存在的可选依赖
-dontwarn io.ktor.**
-dontwarn okhttp3.internal.platform.**
-dontwarn okhttp3.internal.graal.**
-dontwarn org.graalvm.**
-dontwarn com.oracle.svm.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn org.slf4j.**
-dontwarn kotlinx.coroutines.debug.**
-dontwarn javax.annotation.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn org.jetbrains.annotations.**

# isoparser 按类名反射创建 MP4 box 实现
-keep class com.coremedia.iso.** { *; }
-keep class com.googlecode.mp4parser.** { *; }
-keep class org.mp4parser.** { *; }
-dontwarn com.coremedia.iso.**
-dontwarn com.googlecode.mp4parser.**
-dontwarn org.aspectj.**

# PikSeek 的认证模块（DPAPI）与缩略图引擎（libmpv）经 FFM 直调原生函数，用的是 MethodHandle.invoke 与 VarHandle.get。
# 这两个方法是「签名多态」的：字节码里写的是调用处的实际参数类型，而库里只声明了一个 Object... 的版本，
# ProGuard 对不上就当成缺失的引用。调用本身没有问题，JVM 认得
-dontwarn java.lang.invoke.MethodHandle
-dontwarn java.lang.invoke.VarHandle
