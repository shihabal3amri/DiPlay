// Ported from the user's carlink FX11 integration (c839f925).
package com.shilapi.xcertplay.vehicle;

import android.os.Bundle;
import java.lang.reflect.*;

/** Adapts data to the actual interface types exposed by the vehicle's installed SDK. */
final class NavigationPayloads {
    static Object create(Class<?> type, String kind, NavigationDetails details) {
        if (kind.equals("lanes")) {
            if (!type.isArray() || !type.getComponentType().isInterface()) return null;
            Bundle[] lanes=details.lanes();Object result=Array.newInstance(type.getComponentType(),lanes.length);
            for(int i=0;i<lanes.length;i++) Array.set(result,i,proxy(type.getComponentType(),"lane",lanes[i]));
            return result;
        }
        Bundle data=details.object(kind);
        if (data==null || !type.isInterface()) return null;
        return proxy(type,kind,data);
    }
    private static Object proxy(Class<?> type,String kind,Bundle data) {
        return Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(object,method,args)-> {
            String name=method.getName();
            if(name.equals("toString")) return "Demo navigation "+kind;
            if(name.equals("hashCode")) return System.identityHashCode(object);
            if(name.equals("equals")) return object==args[0];
            switch(name) {
                case "getLaneNumber": return number(method,data.getInt("number",1));
                case "getLaneBackIcon": return data.getParcelable("bitmap");
                case "getLaneBackIconId": return number(method,data.getInt("iconId",-1));
                case "getEtaDistance": case "getAreaDistance": return number(method,Math.max(0,data.getInt("distance",0)));
                case "getEtaTime": return number(method,Math.max(0,data.getInt("seconds",0)));
                case "getExitDirection": return data.getString("direction","");
                case "getExitNumber": return data.getString("number","");
                case "getCameraType": case "getAreaType": return number(method,data.getInt("type",0));
                case "getLimitSpeed": return number(method,Math.max(0,data.getInt("limitSpeed",0)));
                case "getAreaName": return data.getString("name","");
                default: return method.getReturnType()==boolean.class ? false : method.getReturnType()==int.class ? 0 : null;
            }
        });
    }
    private static Object number(Method method,int value) {
        if(method.getReturnType()==long.class) return (long)value;
        return value;
    }
}
