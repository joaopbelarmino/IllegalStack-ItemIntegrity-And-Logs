package main.java.me.dniym.identity;

import io.papermc.paper.registry.RegistryAccess;
import org.bukkit.Registry;
import org.bukkit.inventory.MenuType;
import org.mockito.MockedStatic;

import java.lang.reflect.Proxy;

import static org.mockito.Mockito.*;

/** Minimal menu registry for API-only inventory tests; no server registries are started. */
public final class TestMenuRegistry {
    private TestMenuRegistry() {}

    public static MockedStatic<RegistryAccess> install() {
        MockedStatic<RegistryAccess> scope = mockStatic(RegistryAccess.class);
        RegistryAccess access = mock(RegistryAccess.class, call -> {
            if (!call.getMethod().getName().equals("getRegistry")) return RETURNS_DEFAULTS.answer(call);
            return Proxy.newProxyInstance(Registry.class.getClassLoader(), new Class<?>[]{Registry.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("get") || method.getName().equals("getOrThrow")) {
                            return mock(MenuType.Typed.class, RETURNS_SELF);
                        }
                        if (method.getName().equals("toString")) return "TestMenuRegistry";
                        throw new UnsupportedOperationException(method.getName());
                    });
        });
        scope.when(RegistryAccess::registryAccess).thenReturn(access);
        return scope;
    }
}
