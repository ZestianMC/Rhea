package de.erethon.rhea;

import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import java.net.URL;
import java.net.URLClassLoader;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Direct Rhea remains usable when the host does not include the optional integrations. */
class CoreWithoutZlibTest {
    @Test void originalRendererLoadsWithoutZlibOrPacketEvents() throws Exception {
        URL classes=Rhea.class.getProtectionDomain().getCodeSource().getLocation();
        try(var isolated=new URLClassLoader(new URL[]{classes},getClass().getClassLoader()) {
            @Override protected Class<?> loadClass(String name,boolean resolve) throws ClassNotFoundException {
                if(name.startsWith("com.zestian.zlib.")||name.startsWith("com.github.retrooper.packetevents."))throw new ClassNotFoundException(name);
                if(!name.startsWith("de.erethon.rhea."))return super.loadClass(name,resolve);
                synchronized(getClassLoadingLock(name)) {
                    Class<?> type=findLoadedClass(name);if(type==null)type=findClass(name);if(resolve)resolveClass(type);return type;
                }
            }
        }) {
            var renderer=Class.forName("de.erethon.rhea.Rhea",true,isolated);
            assertNotNull(renderer.getConstructor(JavaPlugin.class).newInstance(mock(JavaPlugin.class)));
            for(String type:new String[]{"RheaTemplate","RheaText","RheaProvider"})Class.forName("de.erethon.rhea."+type,true,isolated).getDeclaredMethods();
            assertThrows(ClassNotFoundException.class,()->isolated.loadClass("com.zestian.zlib.menu.MenuManager"));
        }
    }
}
