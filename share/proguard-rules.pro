# :share 模块混淆规则
#
# 本模块是"契约 + 实现同模块"的业务模块,对外只暴露 com.github.tvbox.osc.share 下的
# 公开契约(ShareFacade / ShareTransport / 模型),内部实现放在 .internal / .online.dto。
# 目前 app 侧 minifyEnabled 未开启,这里保持最小集:只保住契约与 Gson 反射用的 DTO。
#
# 契约与模型(经反射/Gson 的不多,但接口/枚举被 :app 显式引用,统一保留)
-keep public interface com.github.tvbox.osc.share.** { *; }
-keep public enum com.github.tvbox.osc.share.** { *; }

# 在线平台 DTO:Gson 依字段名反射赋值,字段名不可被混淆
-keep class com.github.tvbox.osc.share.online.dto.** { <fields>; }
# Gson 泛型签名(保留 Signature,否则 TypeToken 取不到真实类型)
-keepattributes Signature
-keepattributes *Annotation*
