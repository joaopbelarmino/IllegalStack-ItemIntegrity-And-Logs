package main.java.me.dniym.checks;

import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionData;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionType;
import org.jetbrains.annotations.NotNull;

import main.java.me.dniym.IllegalStack;
import main.java.me.dniym.enums.Msg;
import main.java.me.dniym.enums.Protections;
import main.java.me.dniym.listeners.fListener;
import main.java.me.dniym.utils.NBTStuff;

public class BadPotionCheck {

	// Histórico da API de "potion sem tipo base" (o antigo conceito de
	// UNCRAFTABLE):
	//  - Até 1.20.5: PotionType.UNCRAFTABLE existia como valor de enum.
	//  - A partir de 1.20.6: UNCRAFTABLE foi removido -> NoSuchFieldError
	//    (issue #199 no repositório oficial, ainda aberto e sem fix).
	//  - A partir da 1.21.x (API 1.21.1+): PotionMeta.getBasePotionData()
	//    foi marcado @Deprecated(forRemoval) e passou a poder retornar
	//    null (não mais um PotionData "vazio"/UNCRAFTABLE), substituído
	//    por PotionMeta.hasBasePotionType()/getBasePotionType(), que
	//    retorna null quando a poção não tem tipo base -> NullPointerException
	//    se algo ainda chamar getBasePotionData().getType() cegamente.
	//
	// Por isso a checagem abaixo tenta a API nova primeiro (não-depreciada,
	// sem sentinel value, só null-check) e só cai pro caminho antigo se o
	// server for velho o suficiente para não ter hasBasePotionType().
	private static final boolean HAS_MODERN_BASE_TYPE_API;
	static {
		boolean has;
		try {
			PotionMeta.class.getMethod("hasBasePotionType");
			has = true;
		} catch (NoSuchMethodException e) {
			has = false;
		}
		HAS_MODERN_BASE_TYPE_API = has;
	}

	// Mantido só como fallback para servidores antigos (pre-1.21) onde
	// hasBasePotionType() ainda não existe. Nesses servidores o velho
	// PotionType.UNCRAFTABLE ainda existe de verdade, então é seguro.
	private static final boolean HAS_UNCRAFTABLE_TYPE;
	static {
		boolean has;
		try {
			PotionType.valueOf("UNCRAFTABLE");
			has = true;
		} catch (IllegalArgumentException e) {
			has = false;
		}
		HAS_UNCRAFTABLE_TYPE = has;
	}

	/**
	 * true quando a poção não tem um "tipo base" utilizável — o mesmo
	 * conceito que antes era representado por PotionType.UNCRAFTABLE.
	 * Usa a API moderna (getBasePotionType() == null) quando disponível;
	 * cai para a checagem antiga (PotionData/PotionType.UNCRAFTABLE) só em
	 * servidores velhos o bastante para não ter a API nova.
	 */
	private static boolean isUncraftable(PotionMeta potion) {
		if (HAS_MODERN_BASE_TYPE_API) {
			return !potion.hasBasePotionType();
		}
		if (!HAS_UNCRAFTABLE_TYPE) {
			// nem API nova nem o velho UNCRAFTABLE existem -> não tem como
			// esse conceito se aplicar nesta versão do jogo, trata como "não é".
			return false;
		}
		PotionData pd = potion.getBasePotionData();
		if (pd == null) {
			return true;
		}
		return pd.getType() != null && "UNCRAFTABLE".equals(pd.getType().name());
	}

	public static void checkPotion(ItemStack is, Player p) {
		if(!is.hasItemMeta())
			return;
		ItemMeta im = is.getItemMeta();
        if (Protections.PreventInvalidPotions.isEnabled() && im instanceof PotionMeta) {
            if (Protections.AllowBypass.isEnabled() && p.hasPermission("illegalstack.enchantbypass")) 
                return;
            

            if(IllegalStack.isHasMCMMO() && NBTStuff.hasNbtTag("IllegalStack", is, "mcmmoitem", Protections.PreventInvalidPotions)) 
                 return;
              
            
            PotionMeta potion = (PotionMeta) is.getItemMeta();
            if (isUncraftable(potion) || (potion.hasCustomEffects() && !potion
                    .getCustomEffects()
                    .isEmpty())) {

                if (isUncraftable(potion) && potion.getCustomEffects().isEmpty()) 
                    return;
                

                p.getInventory().remove(is);
                StringBuilder efx = new StringBuilder();
                for (PotionEffect ce : potion.getCustomEffects()) {
                    efx
                            .append(ce.getType().getName())
                            .append(" amplifier: ")
                            .append(ce.getAmplifier())
                            .append(
                                    " duration: ")
                            .append(ce.getDuration())
                            .append(",");
                }
                fListener.getLog().append(Msg.InvalidPotionRemoved.getValue(p, efx.toString()), Protections.PreventInvalidPotions);
                
            }

        }
    

		
	}

	public static boolean isInvalidPotion(@NotNull Projectile proj) {
		
		if(proj instanceof ThrownPotion) {
			ThrownPotion tp = (ThrownPotion)proj;
			Player p = null;
			if(proj.getShooter() instanceof Player)
				p = ((Player)proj.getShooter());
			
			 if (p != null && Protections.AllowBypass.isEnabled() && p.hasPermission("illegalstack.enchantbypass")) 
                return false;
            
			 PotionMeta potion = null;
			 
			 if(IllegalStack.isPaperServer()) 
				 potion = (PotionMeta) tp.getPotionMeta();
			  else 
				  potion = (PotionMeta) tp.getItem().getItemMeta();
			 
	         if (isUncraftable(potion) || (potion.hasCustomEffects() && !potion
	                    .getCustomEffects()
	                    .isEmpty())) {

	                if (isUncraftable(potion) && potion.getCustomEffects().isEmpty()) 
	                    return false;
	                
	                
	                //p.getInventory().remove(is);
	                StringBuilder efx = new StringBuilder();
	                for (PotionEffect ce : potion.getCustomEffects()) {
	                    efx
	                            .append(ce.getType().getName())
	                            .append(" amplifier: ")
	                            .append(ce.getAmplifier())
	                            .append(
	                                    " duration: ")
	                            .append(ce.getDuration())
	                            .append(",");
	                }
	                
	                fListener.getLog().append(Msg.InvalidThrownPotionRemoved.getValue(p, efx.toString()), Protections.PreventInvalidPotions);
	                return true;
	            }
	         
		}
		return false;
		
	}

	
}
