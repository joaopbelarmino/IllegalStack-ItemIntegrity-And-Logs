package main.java.me.dniym.audit;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuditModuleTest {
    @Test void capacitySimulationConsumesPartialSlotOnlyOnce() {
        ItemStack existing=stack(63),first=stack(1),second=stack(1);
        when(existing.isSimilar(any())).thenReturn(true);
        assertFalse(AuditModule.canFit(new ItemStack[]{existing},List.of(first,second)));
    }

    @Test void capacitySimulationAcceptsExactRemainingSpace() {
        ItemStack existing=stack(63),moving=stack(1);
        when(existing.isSimilar(any())).thenReturn(true);
        assertTrue(AuditModule.canFit(new ItemStack[]{existing},List.of(moving)));
    }

    private ItemStack stack(int initialAmount) {
        ItemStack original=mock(ItemStack.class),copy=mock(ItemStack.class);
        Material type=mock(Material.class);when(type.isAir()).thenReturn(false);
        AtomicInteger amount=new AtomicInteger(initialAmount);
        when(original.clone()).thenReturn(copy);when(original.getAmount()).thenReturn(initialAmount);
        when(original.getMaxStackSize()).thenReturn(64);when(copy.getMaxStackSize()).thenReturn(64);
        when(original.getType()).thenReturn(type);when(copy.getType()).thenReturn(type);
        when(copy.getAmount()).thenAnswer(call->amount.get());
        org.mockito.Mockito.doAnswer(call->{amount.set(call.getArgument(0));return null;}).when(copy).setAmount(org.mockito.ArgumentMatchers.anyInt());
        when(copy.isSimilar(any())).thenAnswer(call->original.isSimilar(call.getArgument(0)));
        return original;
    }
}
