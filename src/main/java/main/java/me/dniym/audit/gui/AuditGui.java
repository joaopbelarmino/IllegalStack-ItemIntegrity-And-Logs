package main.java.me.dniym.audit.gui;

import main.java.me.dniym.audit.AuditModule;
import main.java.me.dniym.audit.container.SnapshotCodec;
import main.java.me.dniym.audit.database.AuditDatabase;
import main.java.me.dniym.audit.playerdata.OfflineItemAdapter;
import main.java.me.dniym.audit.playerdata.PlayerDataReader;
import main.java.me.dniym.utils.Scheduler;
import net.querz.nbt.tag.CompoundTag;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class AuditGui implements Listener {
    private static final int PAGE_SIZE=45;
    private final AuditModule module;private final OfflineItemAdapter offlineAdapter=new OfflineItemAdapter();
    public AuditGui(AuditModule module){this.module=module;}

    public void openContainer(Player viewer,AuditDatabase.StoredContainer stored,ItemStack[] items){
        boolean readOnly=!"ACTIVE".equals(stored.status());
        Session s=new Session(Target.CONTAINER,stored.ref().uuid(),stored.ref().type(),false,stored.hash(),cloneItems(items),stored,null,readOnly);
        s.topology=module.dirty().topologyRevision();open(viewer,s);
    }
    public void openPlayer(Player viewer,UUID owner,String name,boolean ender,ItemStack[] items,String state,long modified){
        String hash=SnapshotCodec.hash(SnapshotCodec.serialize(items));Session s=new Session(ender?Target.PLAYER_ENDER:Target.PLAYER_INV,owner,name,ender,hash,cloneItems(items),null,null,false);open(viewer,s);
    }
    public void openOfflinePlayer(Player viewer,UUID owner,boolean ender,List<PlayerDataReader.RawSlot> raw,int version,String hash,long modified){
        ItemStack[] items=new ItemStack[ender?27:41];
        for(PlayerDataReader.RawSlot entry:raw){int index=offlineIndex(entry.slot(),ender);if(index>=0&&index<items.length)items[index]=decode(entry.item(),version);}
        Session s=new Session(ender?Target.OFFLINE_ENDER:Target.OFFLINE_INV,owner,owner.toString(),ender,hash,items,null,null,true);open(viewer,s);
    }

    static int offlineIndex(int slot,boolean ender){
        if(ender)return slot>=0&&slot<27?slot:-1;
        if(slot>=0&&slot<36)return slot;
        if(slot>=100&&slot<=103)return slot-64;
        return slot==-106||slot==150?40:-1;
    }

    private ItemStack decode(CompoundTag item,int version){
        try{return offlineAdapter.decode(item,version);}
        catch(Exception e){String id=item.getString("id").orElse("minecraft:barrier");Material m=Material.matchMaterial(id);ItemStack fallback=new ItemStack(m==null?Material.BARRIER:m,Math.max(1,item.getInt("count").orElse(1)));return named(fallback,ChatColor.RED+"Visualizacao limitada",List.of(ChatColor.GRAY+"NBT preservado no playerdata"));}
    }
    private void open(Player viewer,Session session){
        Holder holder=new Holder(session,false);Inventory inv=Bukkit.createInventory(holder,54,title(session));holder.inventory=inv;render(holder);viewer.openInventory(inv);
    }
    private String title(Session s){String base=s.target==Target.CONTAINER?"Bau ":s.ender?"Ender ":"Inventario ";String name=s.name.length()>16?s.name.substring(0,16):s.name;return ChatColor.DARK_AQUA+base+name;}
    private void render(Holder holder){
        Inventory inv=holder.inventory;inv.clear();Session s=holder.session;int start=s.page*PAGE_SIZE;
        for(int gui=0;gui<PAGE_SIZE&&start+gui<s.items.length;gui++){ItemStack item=s.items[start+gui];if(item==null||item.getType().isAir())continue;ItemStack display=item.clone();if(s.selected.contains(start+gui))display=named(display,ChatColor.RED+"MARCADO PARA TRANSFERIR",List.of(ChatColor.GRAY+item.getType().name()));inv.setItem(gui,display);}
        inv.setItem(45,icon(Material.ARROW,ChatColor.YELLOW+"Pagina anterior"));inv.setItem(46,icon(Material.ARROW,ChatColor.YELLOW+"Proxima pagina"));
        inv.setItem(48,icon(Material.PAPER,ChatColor.AQUA+"Informacoes",List.of(ChatColor.GRAY+"Alvo: "+s.id,ChatColor.GRAY+"Pagina: "+(s.page+1),ChatColor.GRAY+"Hash: "+shortHash(s.hash),s.readOnly?ChatColor.RED+"Somente leitura":ChatColor.GREEN+"Estado revalidavel",s.stored==null?"Fonte: "+s.target:"Local: "+s.stored.ref().world()+" "+s.stored.ref().x()+","+s.stored.ref().y()+","+s.stored.ref().z(),s.stored==null?"":"Tipo/status: "+s.stored.ref().type()+"/"+s.stored.status(),s.stored==null?"":"Criado: "+date(s.stored.createdAt()),s.stored==null?"":"Atualizado: "+date(s.stored.updatedAt()),s.stored==null?"":"Ultimo jogador: "+s.stored.lastPlayer(),s.stored==null?"":"Causa: "+s.stored.lastCause())));
        inv.setItem(49,icon(s.edit?Material.REDSTONE_TORCH:Material.LEVER,s.edit?ChatColor.RED+"Modo de selecao ativo":ChatColor.GREEN+"Ativar selecao"));
        if(s.target==Target.CONTAINER)inv.setItem(50,icon(Material.PLAYER_HEAD,ChatColor.AQUA+"Jogadores"));
        if(s.target==Target.CONTAINER)inv.setItem(51,icon(Material.ENDER_PEARL,ChatColor.AQUA+"Teleportar"));
        inv.setItem(52,icon(Material.LIME_CONCRETE,ChatColor.GREEN+"Confirmar transferencia",List.of(ChatColor.GRAY+"Selecionados: "+s.selected.size())));
        inv.setItem(53,icon(Material.BARRIER,ChatColor.RED+"Fechar"));
    }

    @EventHandler public void click(InventoryClickEvent event){
        if(!(event.getView().getTopInventory().getHolder(false) instanceof Holder holder))return;event.setCancelled(true);
        if(!(event.getWhoClicked() instanceof Player player)||event.getClickedInventory()!=holder.inventory)return;
        if(holder.interactionsOnly){if(event.getRawSlot()==53)player.closeInventory();return;}
        if(holder.confirmation){if(event.getRawSlot()==4)transfer(player,holder.session);else if(event.getRawSlot()==6)open(player,holder.session);return;}
        Session s=holder.session;int slot=event.getRawSlot();
        if(slot>=0&&slot<PAGE_SIZE){int actual=s.page*PAGE_SIZE+slot;if(s.edit&&!s.readOnly&&actual<s.items.length&&s.items[actual]!=null&&!s.items[actual].getType().isAir()){if(!s.selected.add(actual))s.selected.remove(actual);render(holder);}return;}
        switch(slot){case 45->{if(s.page>0){s.page--;render(holder);}}case 46->{if((s.page+1)*PAGE_SIZE<s.items.length){s.page++;render(holder);}}case 49->{if(!s.readOnly&&player.hasPermission("illegalstack.audit.edit")){s.edit=!s.edit;render(holder);}}case 50->{if(s.target==Target.CONTAINER)players(player,s);}case 51->teleport(player,s);case 52->{if(!s.selected.isEmpty()&&!s.readOnly)if(module.config().confirmationRequired())confirm(player,s);else transfer(player,s);}case 53->player.closeInventory();default->{}}
    }
    @EventHandler public void drag(InventoryDragEvent event){if(event.getView().getTopInventory().getHolder(false) instanceof Holder)event.setCancelled(true);}

    private void confirm(Player player,Session session){
        Holder holder=new Holder(session,true);Inventory inv=Bukkit.createInventory(holder,9,ChatColor.DARK_RED+"Confirmar transferencia");holder.inventory=inv;
        inv.setItem(4,icon(Material.LIME_CONCRETE,ChatColor.GREEN+"CONFIRMAR",session.selected.stream().sorted().map(slot->session.items[slot].getAmount()+"x "+session.items[slot].getType().name()).toList()));inv.setItem(6,icon(Material.RED_CONCRETE,ChatColor.RED+"CANCELAR"));player.openInventory(inv);
    }
    private void transfer(Player player,Session s){
        if(s.submitted)return;s.submitted=true;
        if(s.target==Target.CONTAINER&&s.topology!=module.dirty().topologyRevision()){AuditModule.message(player,"Container mudou; reabra a interface.");player.closeInventory();return;}
        if(s.target==Target.CONTAINER)module.transferContainer(player,s.stored,s.items,Set.copyOf(s.selected));
        else module.transferOnlinePlayer(player,s.id,s.ender,s.hash,s.items,Set.copyOf(s.selected));
        player.closeInventory();
    }
    private void players(Player player,Session s){module.database().interactions(s.id).whenComplete((rows,error)->Scheduler.runTask(modulePlugin(),()->{if(error!=null){AuditModule.message(player,ChatColor.RED+"Falha ao consultar interacoes.");return;}Holder holder=new Holder(s,false);holder.interactionsOnly=true;Inventory inv=Bukkit.createInventory(holder,54,ChatColor.DARK_AQUA+"Jogadores do container");holder.inventory=inv;int i=0;for(var row:rows){String name=row.name()==null?row.uuid().toString():row.name();inv.setItem(i++,icon(Material.PLAYER_HEAD,ChatColor.AQUA+name,List.of(ChatColor.GRAY+row.uuid().toString(),ChatColor.GRAY+"Interacoes: "+row.interactions(),ChatColor.GRAY+"Primeira: "+date(row.firstSeen()),ChatColor.GRAY+"Ultima: "+date(row.lastSeen()))));if(i>=45)break;}inv.setItem(53,icon(Material.BARRIER,ChatColor.RED+"Fechar"));player.openInventory(inv);}));}
    private main.java.me.dniym.IllegalStack modulePlugin(){return main.java.me.dniym.IllegalStack.getPlugin();}
    private void teleport(Player player,Session s){if(s.stored==null)return;World world=Bukkit.getWorld(s.stored.ref().world());if(world==null||!world.isChunkLoaded(s.stored.ref().x()>>4,s.stored.ref().z()>>4)){AuditModule.message(player,ChatColor.RED+"Regiao nao carregada; nenhum chunk foi forcado.");return;}player.teleport(new org.bukkit.Location(world,s.stored.ref().x()+.5,s.stored.ref().y()+1,s.stored.ref().z()+.5));}
    private ItemStack icon(Material type,String name){return icon(type,name,List.of());}
    private ItemStack icon(Material type,String name,List<String> lore){return named(new ItemStack(type),name,lore);}
    private ItemStack named(ItemStack item,String name,List<String> lore){ItemMeta meta=item.getItemMeta();meta.setDisplayName(name);meta.setLore(lore);item.setItemMeta(meta);return item;}
    private static String date(long value){return java.time.Instant.ofEpochMilli(value).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"));}
    private String shortHash(String hash){return hash==null?"-":hash.substring(0,Math.min(12,hash.length()));}
    private ItemStack[] cloneItems(ItemStack[] items){ItemStack[] out=new ItemStack[items.length];for(int i=0;i<items.length;i++)out[i]=items[i]==null?null:items[i].clone();return out;}
    enum Target{CONTAINER,PLAYER_INV,PLAYER_ENDER,OFFLINE_INV,OFFLINE_ENDER}
    static final class Session{final Target target;final UUID id;final String name;final boolean ender;final String hash;final ItemStack[]items;final AuditDatabase.StoredContainer stored;final Set<Integer>selected=new HashSet<>();final boolean readOnly;int page;boolean edit;boolean submitted;long topology;Session(Target target,UUID id,String name,boolean ender,String hash,ItemStack[]items,AuditDatabase.StoredContainer stored,Object ignored,boolean readOnly){this.target=target;this.id=id;this.name=name;this.ender=ender;this.hash=hash;this.items=items;this.stored=stored;this.readOnly=readOnly;}}
    static final class Holder implements InventoryHolder{final Session session;final boolean confirmation;boolean interactionsOnly;Inventory inventory;Holder(Session session,boolean confirmation){this.session=session;this.confirmation=confirmation;}public Inventory getInventory(){return inventory;}}
}
