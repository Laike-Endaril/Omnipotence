package com.fantasticsource.omniscience.hack;

import com.fantasticsource.tools.ReflectionTool;
import com.google.common.collect.Sets;
import com.google.common.reflect.TypeToken;
import net.minecraftforge.fml.common.FMLLog;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModContainer;
import net.minecraftforge.fml.common.eventhandler.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class OmniEventBus extends EventBus
{
    protected static final Field
            EVENT_BUS_LISTENERS_FIELD = ReflectionTool.getField(EventBus.class, "listeners"),
            EVENT_BUS_LISTENER_OWNERS_FIELD = ReflectionTool.getField(EventBus.class, "listenerOwners"),
            EVENT_BUS_BUS_ID_FIELD = ReflectionTool.getField(EventBus.class, "busID");

    protected ConcurrentHashMap<Object, ArrayList<IEventListener>> listeners;
    protected Map<Object, ModContainer> listenerOwners;
    protected int busID;

    public OmniEventBus(EventBus originalBus)
    {
        listeners = (ConcurrentHashMap<Object, ArrayList<IEventListener>>) ReflectionTool.get(EVENT_BUS_LISTENERS_FIELD, originalBus);
        listenerOwners = (Map<Object, ModContainer>) ReflectionTool.get(EVENT_BUS_LISTENER_OWNERS_FIELD, originalBus);
        busID = (int) ReflectionTool.get(EVENT_BUS_BUS_ID_FIELD, originalBus);
    }

    @Override
    public void register(Object target)
    {
        if (listeners.containsKey(target)) return;

        ModContainer activeModContainer = Loader.instance().activeModContainer();
        if (activeModContainer == null)
        {
            FMLLog.log.error("Unable to determine registrant mod for {}. This is a critical error and should be impossible", target, new Throwable());
            activeModContainer = Loader.instance().getMinecraftModContainer();
        }
        listenerOwners.put(target, activeModContainer);
        boolean isStatic = target.getClass() == Class.class;
        @SuppressWarnings("unchecked")
        Set<? extends Class<?>> supers = isStatic ? Sets.newHashSet((Class<?>) target) : TypeToken.of(target.getClass()).getTypes().rawTypes();
        for (Method method : (isStatic ? (Class<?>) target : target.getClass()).getMethods())
        {
            if (isStatic && !Modifier.isStatic(method.getModifiers())) continue;
            else if (!isStatic && Modifier.isStatic(method.getModifiers())) continue;

            for (Class<?> cls : supers)
            {
                try
                {
                    Method real = cls.getDeclaredMethod(method.getName(), method.getParameterTypes());
                    if (real.isAnnotationPresent(SubscribeEvent.class))
                    {
                        Class<?>[] parameterTypes = method.getParameterTypes();
                        if (parameterTypes.length != 1)
                        {
                            throw new IllegalArgumentException(
                                    "Method " + method + " has @SubscribeEvent annotation, but requires " + parameterTypes.length + " arguments.  Event handler methods must require a single argument."
                            );
                        }

                        Class<?> eventType = parameterTypes[0];

                        if (!Event.class.isAssignableFrom(eventType))
                        {
                            throw new IllegalArgumentException("Method " + method + " has @SubscribeEvent annotation, but takes a argument that is not an Event " + eventType);
                        }

                        register(eventType, target, real, activeModContainer, listeners);
                        break;
                    }
                }
                catch (NoSuchMethodException e)
                {
                    // Eat the error, this is not unexpected
                }
            }
        }
    }

    private void register(Class<?> eventType, Object target, Method method, final ModContainer owner, ConcurrentHashMap<Object, ArrayList<IEventListener>> listeners)
    {
        try
        {
            Constructor<?> ctr = eventType.getConstructor();
            ctr.setAccessible(true);
            Event event = (Event) ctr.newInstance();
            final OmniASMEventHandler asm = new OmniASMEventHandler(target, method, owner, IGenericEvent.class.isAssignableFrom(eventType));

            IEventListener listener = asm;
            if (IContextSetter.class.isAssignableFrom(eventType))
            {
                listener = event1 ->
                {
                    ModContainer old = Loader.instance().activeModContainer();
                    Loader.instance().setActiveModContainer(owner);
                    ((IContextSetter) event1).setModContainer(owner);
                    asm.invoke(event1);
                    Loader.instance().setActiveModContainer(old);
                };
            }

            event.getListenerList().register(busID, asm.getPriority(), listener);

            ArrayList<IEventListener> others = listeners.computeIfAbsent(target, k -> new ArrayList<>());
            others.add(listener);
        }
        catch (Exception e)
        {
            FMLLog.log.error("Error registering event handler: {} {} {}", owner, eventType, method, e);
        }
    }

}
