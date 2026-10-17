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
