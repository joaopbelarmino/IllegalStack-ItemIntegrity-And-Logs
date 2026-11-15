package main.java.me.dniym.audit.search;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ItemQueryTest {
    @Test void acceptsMaterialAliasesAndStructuredQueries(){
        assertEquals("minecraft:netherite_block",ItemQuery.parse("netherite_block").value());
        assertEquals("minecraft:netherite_block",ItemQuery.parse("minecraft:netherite_block").value());
        assertEquals(ItemQuery.Type.SERIAL,ItemQuery.parse("serial:abc").type());
        assertEquals("custom:pickaxe",ItemQuery.parse("custom:pickaxe").value());
        assertThrows(IllegalArgumentException.class,()->ItemQuery.parse("not_a_real_material"));
    }
}
