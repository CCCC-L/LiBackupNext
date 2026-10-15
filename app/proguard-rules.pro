# 只做裁剪和优化，不做混淆。
#
# 正式包之前用 AGP 9.3 的新 DSL（optimization { enable = true }）会把
# androidx / kotlin / kotlinx 整个混淆掉，装上一打开就闪退；debug 包不混淆所以正常。
# 这里保留「删除无用代码 + 资源收缩 + 优化」（体积几乎一样，dex 3.19MB），
# 但关掉改名，避免运行期按类名反射的地方被破坏。
-dontobfuscate

# ==================== 反射相关，必须保留 ====================

# libsu：root 服务由系统按清单里的类名实例化，内部还会反射找 Service
-keep class com.topjohnwu.superuser.** { *; }
-dontwarn com.topjohnwu.**

# rikkax.parcelablelist：AIDL 里传的 Parcelable 列表包装
-keep class me.rikkax.parcelablelist.** { *; }

# Refine（隐藏 API 代理）：运行期按类名反射系统类
-keep class com.cc.libackupnext.hidden.** { *; }

# AIDL 生成的 Stub / Proxy（binder 跨进程按名字找）
-keep class **.Stub { *; }
-keep class **.Proxy { *; }

# Parcelable 的 CREATOR 是运行期反射取的
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# 崩溃栈保留行号，方便定位
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
