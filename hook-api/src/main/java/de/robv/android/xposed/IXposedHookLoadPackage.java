package de.robv.android.xposed;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
/** Compile-only binary API declaration. Runtime supplied by LSPosed. */
public interface IXposedHookLoadPackage {void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) throws Throwable;}
