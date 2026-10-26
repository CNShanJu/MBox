#############################################
#
# 【注意】本文件已停用(2026-10-26)
#
# 背景:release 曾开启 R8 混淆(minifyEnabled true),但导致安装包启动即闪退。
#   排查结论:R8 混淆 + -repackageclasses 会剥离 ViewBinding/TypeToken 的泛型
#   Signature,BaseVbActivity 等通过反射读取泛型参数时崩溃;且 keep 规则越补越多、
#   排查成本远超体积收益,故关闭混淆(minifyEnabled false / shrinkResources false)。
#
# 现状:app/build.gradle 的 release 已不再引用本文件。本文件保留作历史参考,
#   若未来重新评估混淆,需重新验证 ViewBinding 反射 / Gson TypeToken / XStream
#   / QuickJS 桥接 / P2P JNI 等 keep 规则是否完整,并回归测试启动链路。
#
#############################################
#
# 对于一些基本指令的添加
#
#############################################
-optimizationpasses 5
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-dontskipnonpubliclibraryclassmembers
-dontpreverify
-verbose
-printmapping proguardMapping.txt
-optimizations !code/simplification/cast,!field/*,!class/merging/*
-keepattributes *Annotation*,InnerClasses
-keepattributes EnclosingMethod, InnerClasses
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes LineNumberTable
-renamesourcefileattribute SourceFile

# @Keep 注解:显式保留被标注的类/成员(不依赖 androidx 库的 consumer rules 合并行为)
-keep @androidx.annotation.Keep class * { *; }
-keepclassmembers class * {
    @androidx.annotation.Keep <methods>;
    @androidx.annotation.Keep <fields>;
}

# 混淆后的类统一打包到单一包(-repackageclasses 已含 -flattenpackagehierarchy 效果,后者冗余;
# -useuniqueclassmembernames 为 R8 不支持项,已移除)
-repackageclasses androidx.base
#############################################
#
# Android开发中一些需要保留的公共部分
#
#############################################

# 保留我们使用的四大组件，自定义的Application等等这些类不被混淆
# 因为这些子类都有可能被外部调用
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.app.backup.BackupAgentHelper
-keep public class * extends android.preference.Preference
-keep public class * extends android.view.View
-keep public class com.android.vending.licensing.ILicensingService

# 保留support下的所有类及其内部类
-keep class android.support.** {*;}
# 保留继承的
-keep public class * extends android.support.v4.**
-keep public class * extends android.support.v7.**
-keep public class * extends android.support.annotation.**

# AppSlider's Material 1.9 tooltip color bridge also relies on BaseSlider.labels.
-keep class com.google.android.material.** { *; }
-dontwarn com.google.android.material.**
-dontnote com.google.android.material.**
-dontwarn androidx.**
-keep class androidx.** { *; }
-keep interface androidx.** { *; }
#-keep public class * extends androidx.**

-keep class org.xmlpull.v1.** {*;}

# 保留R下面的资源
-keep class **.R$* {*;}

# 保留本地native方法不被混淆
-keepclasseswithmembernames class * {
    native <methods>;
}

# 保留在Activity中的方法参数是view的方法，
# 这样以来我们在layout中写的onClick就不会被影响
-keepclassmembers class * extends android.app.Activity{
    public void *(android.view.View);
}

# 保留枚举类不被混淆
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# 保留我们自定义控件（继承自View）不被混淆
-keep public class * extends android.view.View{
    *** get*();
    void set*(***);
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

-keep public class * extends androidx.recyclerview.widget.RecyclerView$LayoutManager{
    *** get*();
    void set*(***);
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}


# 保留Parcelable序列化类不被混淆
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# 保留Serializable序列化的类不被混淆
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    !private <fields>;
    !private <methods>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# 对于带有回调函数的onXXEvent、**On*Listener的，不能被混淆
-keepclassmembers class * {
    void *(**On*Event);
    void *(**On*Listener);
}
#xwalk(已不使用,规则随之删除)
-dontwarn android.view.**
-dontwarn android.media.**
#okhttp
-dontwarn okhttp3.**
-keep class okhttp3.**{*;}
#okio
-dontwarn okio.**
-keep class okio.**{*;}
#loadsir
-dontwarn com.kingja.loadsir.**
-keep class com.kingja.loadsir.** {*;}
#gson
# Gson specific classes
-dontwarn sun.misc.**
#-keep class com.google.gson.stream.** { *; }
# Application classes that will be serialized/deserialized over Gson
-keep class com.google.gson.examples.android.model.** { <fields>; }
# Gson 泛型签名保留(R8 兼容模式关键修复):
# TypeToken 靠反射读取匿名子类继承链上的泛型 Signature 来还原 List<T>/Map<K,V> 这类真实类型。
# 本工程开了 -repackageclasses(全部类合并进单一包),R8 的 class merging / argument removal
# 会把 TypeToken 匿名子类的泛型签名剥掉,启动时 new TypeToken<...>(){} 的 <clinit> 抛
# "TypeToken must be created with a type argument" 的 ExceptionInInitializerError,直接闪退。
# 单独一条 -keepattributes Signature 不够(见 R8 compatibility-faq「GSON」小节),必须连类一起 keep。
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
# Prevent proguard from stripping interface information from TypeAdapter, TypeAdapterFactory,
# JsonSerializer, JsonDeserializer instances (so they can be used in @JsonAdapter)
-keep class * extends com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
# Prevent R8 from leaving Data object members always null
-keepclassmembers,allowobfuscation class * {
  @com.google.gson.annotations.SerializedName <fields>;
}
#xstream
-keep class com.thoughtworks.xstream.converters.extended.SubjectConverter { *; }
-keep class com.thoughtworks.xstream.converters.extended.ThrowableConverter { *; }
-keep class com.thoughtworks.xstream.converters.extended.StackTraceElementConverter { *; }
-keep class com.thoughtworks.xstream.converters.extended.CurrencyConverter { *; }
-keep class com.thoughtworks.xstream.converters.extended.RegexPatternConverter { *; }
-keep class com.thoughtworks.xstream.converters.extended.CharsetConverter { *; }
-keep class com.thoughtworks.xstream.** { *; }
# XStream 引用的可选依赖(cglib/dom4j/jdom/joda/java.awt/javax.xml.stream 等)在 Android 上不存在,
# 只被"未实际使用的驱动/转换器"引用,运行时不会触发;不忽略会被 R8 升级成致命 missing class 错误。
-dontwarn com.thoughtworks.xstream.**
#eventbus 已全仓移除(AGENTS §五,禁止再引入):相关 keep 规则随之删除

#dkplayer(现用坐标 xyz.doikki.android.dkplayer,旧包名 com.dueeeke 已废弃)
-keep class xyz.doikki.videoplayer.** { *; }
-dontwarn xyz.doikki.videoplayer.**

# IjkPlayer 已移除(2026-10-17),相关 keep 规则随之删除

# Media3 ExoPlayer（渲染器/协议扩展另带 consumer rules）
-keep class androidx.media3.** { *; }

# 实体类
-keep class com.github.tvbox.osc.bean.** { *; }

-keep class com.github.catvod.crawler.*{*;}
# 迅雷下载模块
-keep class com.xunlei.downloadlib.** {*;}
# 迅雷 P2P 原生库 JNI 绑定(app 层 com.p2p.P2PClass,libp2p.so)
-keep class com.p2p.** { *; }
# quickjs引擎(:thirdparty)
-keep class com.whl.quickjs.** {*;}
# QuickJS 桥接:JS 侧按方法名调用,必须保留 @Function/@ContextSetter 标注的方法名
-keepclassmembers class * {
    @com.whl.quickjs.wrapper.Function <methods>;
}
-keepclassmembers class * {
    @com.whl.quickjs.wrapper.ContextSetter <methods>;
}

# 支持影视的ali相关的jar
-keep class com.google.gson.**{*;}


# 沉浸状态栏
-keep class com.gyf.immersionbar.* {*;}
-dontwarn com.gyf.immersionbar.**


# glide 已不使用(图片加载统一 Picasso,AGENTS §七):规则随之删除

# ViewBinding & DataBinding
-keepclassmembers class * implements androidx.viewbinding.ViewBinding {
  public static * inflate(android.view.LayoutInflater);
  public static * inflate(android.view.LayoutInflater, android.view.ViewGroup, boolean);
  public static * bind(android.view.View);
}
