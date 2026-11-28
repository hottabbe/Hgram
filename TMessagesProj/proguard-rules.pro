-keep public class com.google.android.gms.* { public *; }
-keepnames @com.google.android.gms.common.annotation.KeepName class *
-keepclassmembernames class * {
    @com.google.android.gms.common.annotation.KeepName *;
}

# Firebase Components discovers registrars through reflection. R8 must keep the
# ComponentRegistrar type, every implementation and its public no-arg constructor.
-keep interface com.google.firebase.components.ComponentRegistrar { *; }
-keep class * implements com.google.firebase.components.ComponentRegistrar { *; }
-keepclassmembers class * implements com.google.firebase.components.ComponentRegistrar {
    public <init>();
}

# Firebase/ML Kit/GMS AARs ship their own consumer rules. Only the reflective
# ComponentRegistrar contract needs an explicit local rule.

# AndroidX Startup instantiates initializers from manifest metadata by class name.
-keep class androidx.startup.** { *; }
-keep class * implements androidx.startup.Initializer { *; }
-keepclassmembers class * implements androidx.startup.Initializer {
    public <init>();
}

-keep @interface androidx.annotation.Keep
-keep @androidx.annotation.Keep class * { *; }
-keepclasseswithmembers class * { @androidx.annotation.Keep *; }

-keep class org.webrtc.* { *; }
-keep class org.webrtc.audio.* { *; }
-keep class org.webrtc.voiceengine.* { *; }
# org.telegram.messenger: only the names that native code and reflection resolve at runtime.
# Before changing these rules see TMessagesProj/proguard-checklist.md and run Tools/r8_keep_check.py.
# Static JNI exports Java_org_telegram_messenger_<Class>_<method> (Utilities, Intro, MediaController, MrzRecognizer).
-keepclasseswithmembers,includedescriptorclasses class org.telegram.messenger.* {
    native <methods>;
}
# gifvideo.cpp: FindClass + GetMethodID, called from the native reader.
-keep class org.telegram.messenger.AnimatedFileDrawableStream {
    int read(int, int);
    void cancel();
    boolean isFinishedLoadingFile();
    boolean isCanceled();
    java.lang.String getFinishedFilePath();
}
# NotificationBadge.BADGERS holds class literals, instances are made with Class.newInstance().
-keep class org.telegram.messenger.NotificationBadge$* implements org.telegram.messenger.NotificationBadge$Badger {
    <init>();
}
-keep class org.telegram.messenger.camera.* { *; }
-keep class org.telegram.messenger.secretmedia.* { *; }
-keep class org.telegram.messenger.support.* { *; }
-keep class org.telegram.messenger.support.* { *; }
-keep class org.telegram.messenger.time.* { *; }
-keep class org.telegram.messenger.video.* { *; }
-keep class org.telegram.messenger.voip.* { *; }
-keep class org.telegram.SQLite.** { *; }
-keep class org.telegram.tgnet.ConnectionsManager { *; }
-keep class org.telegram.tgnet.NativeByteBuffer { *; }
-keep class org.telegram.tgnet.RequestTimeDelegate { *; }
-keep class org.telegram.tgnet.RequestDelegate { *; }
-keep class org.telegram.ui.Stories.recorder.FfmpegAudioWaveformLoader { *; }
-keep class androidx.mediarouter.app.MediaRouteButton { *; }
-keepclassmembers class ** {
    @android.webkit.JavascriptInterface <methods>;
}

-keep class org.telegram.utils.code.highlight.PrismaHighlighter {
    private static native <methods>;
}

# https://developers.google.com/ml-kit/known-issues#android_issues
-keep class com.google.mlkit.nl.languageid.internal.LanguageIdentificationJni { *; }

# WidgetSandbox: JNI_OnLoad uses RegisterNatives; backend methods are called by native workers.
-keep class org.telegram.utils.twidget.WidgetSandboxNative { *; }
# The client's share (filters, gesture arbiter) in the client library: static Java_* exports, resolved by name.
-keepclasseswithmembers,includedescriptorclasses class org.telegram.utils.twidget.WidgetClientNative {
    native <methods>;
}
-keep class org.telegram.utils.twidget.WidgetSandboxBackend { *; }
# The isolated engine process's backend (WidgetEngineService): the same JNI callbacks.
-keep class org.telegram.utils.twidget.WidgetEngineBackend { *; }

# Huawei Services
-keep class com.huawei.hianalytics.**{ *; }
-keep class com.huawei.updatesdk.**{ *; }
-keep class com.huawei.hms.**{ *; }

# Don't warn about checkerframework and Kotlin annotations
-dontwarn org.checkerframework.**
-dontwarn javax.annotation.**

-keep class io.nano.tex.** {*;}

-keep class org.telegram.tgnet.** { *; }

# JLatexMath: macro/atom classes are loaded reflectively by Class.forName
-keep class org.scilab.forge.jlatexmath.** { *; }
-keep class ru.noties.jlatexmath.** { *; }
-dontwarn org.scilab.forge.jlatexmath.**

# Use -keep to explicitly keep any other classes shrinking would remove
# R8 keep rules cannot match <clinit>; without this, pure static field stores are
# removed even though the class is kept. Optimization stays off, shrinking remains on.
-dontoptimize
-dontobfuscate

# R8-GUARD-1: keep reflection/JNI/DI-sensitive application surface unchanged.
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keep class xyz.nextalone.** { *; }
-keep class tw.nekomimi.** { *; }

# R8 drops pure static-field stores from <clinit>; keep rules cannot match <clinit>
# (the rule is reported as matching nothing). HgramConfig therefore uses explicit
# loaded flags instead of relying on -1 static defaults.

# Gson models and ObjectBox entities live under tw/org.telegram and are kept
# above; their library internals are covered by the AAR consumer rules.

# Native methods may be resolved by name or registered from JNI.
-keepclasseswithmembers class * {
    native <methods>;
}

# values()/valueOf() are used by config serialization and runtime lookups.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

-keep class org.telegram.** { *; }

-keep class org.openintents.openpgp.** { *; }

-keep class org.dizitart.no2.**  { *; }
-keep class org.slf4j.** { *; }
-keep class org.h2.** { *; }
-keep class org.objenesis.** { *; }
-keep class com.fasterxml.jackson.** { *; }

-keepnames class * implements java.io.Serializable

-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

-keepattributes SourceFile,LineNumberTable

-dontwarn java.beans.**
-dontwarn java.lang.management.**
-dontwarn javax.swing.**
-dontwarn lombok.**
-dontwarn org.slf4j.**
-dontwarn org.w3c.dom.bootstrap.**

-dontwarn sun.misc.Unsafe
-dontwarn org.bouncycastle.crypto.**
-dontwarn org.bouncycastle.asn1.**
-dontwarn sun.net.spi.nameservice.**
-dontwarn com.sun.jna.**
-dontwarn javax.naming.NamingException
-dontwarn javax.naming.directory.DirContext
-dontwarn javax.naming.directory.InitialDirContext
-dontwarn org.xbill.DNS.spi.DnsjavaInetAddressResolverProvider

-keepclassmembers class tw.nekomimi.nekogram.helpers.AyuFilter$FilterModel {
 !transient <fields>;
}
-keepattributes Signature
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keep class * extends com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
