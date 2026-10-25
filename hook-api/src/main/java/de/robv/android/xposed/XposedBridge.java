package de.robv.android.xposed;
import java.util.Set;
public final class XposedBridge {
    public static Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> clazz,String method,XC_MethodHook callback){throw new UnsupportedOperationException("Compile-only API");}
    public static void log(String text){throw new UnsupportedOperationException("Compile-only API");}
}
