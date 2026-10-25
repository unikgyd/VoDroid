package de.robv.android.xposed;
public abstract class XC_MethodHook {
    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}
    public static class MethodHookParam {public Object thisObject;public Object[] args;public void setResult(Object result){throw new UnsupportedOperationException("Compile-only API");}public Object getResult(){throw new UnsupportedOperationException("Compile-only API");}}
    public class Unhook {}
}
