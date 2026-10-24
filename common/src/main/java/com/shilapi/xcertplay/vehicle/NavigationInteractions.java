// Ported from the user's carlink FX11 integration (c839f925).
package com.shilapi.xcertplay.vehicle;
import java.lang.reflect.*;
import android.util.Log;

/** Optional SDK callback adapter; method names are discovered on the installed car framework. */
final class NavigationInteractions {
    interface Listener { void onAction(int action, String query); }
    private Object manager, callback;
    private volatile int generation;
    private Class<?> api, callbackType;
    boolean register(Object target, Class<?> type, Listener listener) {
        close();
        for(Method method:type.getMethods()) {
            String name=method.getName();
            if (!(name.startsWith("set") || name.startsWith("register")) || !name.contains("InteractionCallback")
                    || method.getParameterTypes().length!=1 || !method.getParameterTypes()[0].isInterface()) continue;
            try {
                Class<?> cb=method.getParameterTypes()[0];
                int registeredGeneration = generation;
                Object proxy=Proxy.newProxyInstance(cb.getClassLoader(),new Class<?>[]{cb},(self,m,args)-> {
                    if(m.getName().equals("toString")) return "Demo navigation callback";
                    if(m.getName().equals("hashCode")) return System.identityHashCode(self);
                    if(m.getName().equals("equals")) return self==args[0];
                    if (registeredGeneration != generation) return m.getReturnType()==boolean.class ? false : null;
                    if(m.getName().equals("onDoInteractionAction") && args!=null && args[0] instanceof Number)
                        listener.onAction(((Number)args[0]).intValue(),null);
                    else if(m.getName().equals("onSearchAddress") && args!=null) listener.onAction(-1,String.valueOf(args[0]));
                    return m.getReturnType()==boolean.class ? false : m.getReturnType()==int.class ? 0 : null;
                });
                method.invoke(target,proxy); manager=target;api=type;callbackType=cb;callback=proxy;return true;
            } catch(Exception e) { Log.w("GeelyEcarxDim","Navigation callback registration failed",e); }
        }
        return false;
    }
    void close() {
        generation++;
        if(manager!=null) {
            for(Method method:api.getMethods()) {
                if((method.getName().startsWith("unset") || method.getName().startsWith("unregister"))
                        && method.getParameterTypes().length==1 && method.getParameterTypes()[0]==callbackType) {
                    try { method.invoke(manager,callback); } catch(Exception e) { Log.w("GeelyEcarxDim","Navigation callback release failed",e); }
                    break;
                }
            }
        }
        manager=null;callback=null;api=null;callbackType=null;
    }
}
