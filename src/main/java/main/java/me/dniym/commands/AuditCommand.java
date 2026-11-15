package main.java.me.dniym.commands;

import main.java.me.dniym.IllegalStack;
import main.java.me.dniym.audit.AuditModule;
import main.java.me.dniym.audit.model.SearchResult;
import main.java.me.dniym.utils.Scheduler;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

final class AuditCommand {
    private AuditCommand(){}
    static boolean handle(CommandSender sender,String[] args){
        if(args.length==0)return false;String root=args[0].toLowerCase(Locale.ROOT);
        if(!List.of("search","view","reindex","audit","backup").contains(root))return false;
        AuditModule module=IllegalStack.getPlugin().getAuditModule();
        if(module==null||!module.enabled()){AuditModule.message(sender,ChatColor.RED+"Modulo de auditoria indisponivel.");return true;}
        try{return switch(root){case "search"->search(module,sender,args);case "view"->view(module,sender,args);case "reindex"->reindex(module,sender,args);case "audit"->audit(module,sender,args);case "backup"->backup(module,sender,args);default->false;};}
        catch(IllegalArgumentException e){AuditModule.message(sender,ChatColor.RED+e.getMessage());return true;}
    }

    private static boolean search(AuditModule module,CommandSender sender,String[] args){
        require(sender,"illegalstack.audit.search");if(args.length<2)throw new IllegalArgumentException("Uso: /stack search <playerdata|inv|end|bau|all|suspeito> [item]");
        String scope=alias(args[1]);
        if(!List.of("playerdata","playerdata_inv","playerdata_end","bau","all","suspeito").contains(scope))throw new IllegalArgumentException("Escopo de busca invalido.");
        if(scope.equals("suspeito")){module.suspicious(args.length>=3?alias(args[2]):"all").whenComplete((rows,error)->reply(sender,rows,error,"Itens suspeitos"));return true;}
        if(args.length<3)throw new IllegalArgumentException("Informe o material, serial:<id> ou custom:<id>.");
        int page=args.length>3?Math.max(1,Integer.parseInt(args[3])):1;
        module.search(scope,args[2],page).whenComplete((rows,error)->reply(sender,rows,error,"Busca "+args[2]));return true;
    }
    private static void reply(CommandSender sender,List<SearchResult> rows,Throwable error,String title){Scheduler.runTask(IllegalStack.getPlugin(),()->{
        if(error!=null){AuditModule.message(sender,ChatColor.RED+"Falha na consulta: "+rootMessage(error));return;}
        AuditModule.message(sender,ChatColor.GOLD+title+ChatColor.GRAY+" - "+rows.size()+" resultado(s)");int rank=1;
        for(SearchResult r:rows){sender.sendMessage(ChatColor.AQUA+"#"+(rank++)+" "+r.targetType()+" "+r.displayName()+ChatColor.GRAY+" ["+r.source()+"] direct="+r.direct()+" nested="+r.nested()+" total="+r.total()+(r.score()>0?" score="+r.score():"")+" "+r.detail());
            String command=r.targetType().equals("CONTAINER")?"/stack view bau "+r.targetId():"/stack view "+(r.source().equals("ENDER")?"end":"inv")+" "+r.targetId();
            sender.sendMessage(net.kyori.adventure.text.Component.text("[ABRIR]").clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand(command)));
        }
    });}

    private static boolean view(AuditModule module,CommandSender sender,String[] args){
        require(sender,"illegalstack.audit.view");if(!(sender instanceof Player player))throw new IllegalArgumentException("Este comando precisa ser usado em jogo.");if(args.length<3)throw new IllegalArgumentException("Uso: /stack view <inv|end|bau> <alvo>");
        String kind=alias(args[1]);if(!List.of("bau","playerdata_inv","playerdata_end").contains(kind))throw new IllegalArgumentException("Tipo de view invalido.");if(kind.equals("bau")){viewContainer(module,player,args);return true;}
        UUID target=playerId(args[2]);module.openPlayer(player,target,kind.equals("playerdata_end"));return true;
    }
    private static void viewContainer(AuditModule module,Player player,String[] args){
        if(args[2].equalsIgnoreCase("here")){Block block=player.getTargetBlockExact(6);if(block==null)throw new IllegalArgumentException("Olhe para um container proximo.");var resolved=module.resolver().resolve(block).orElseThrow(()->new IllegalArgumentException("O bloco nao e um container persistente."));module.openLiveContainer(player,resolved);return;}
        if(args.length>=6){World world=Bukkit.getWorld(args[2]);if(world==null)throw new IllegalArgumentException("Mundo desconhecido.");int x=Integer.parseInt(args[3]),y=Integer.parseInt(args[4]),z=Integer.parseInt(args[5]);if(!world.isChunkLoaded(x>>4,z>>4))throw new IllegalArgumentException("Container nao carregado; nenhum chunk foi forcado.");var resolved=module.resolver().resolve(world.getBlockAt(x,y,z)).orElseThrow(()->new IllegalArgumentException("Nao ha container nessa posicao."));module.openLiveContainer(player,resolved);return;}
        module.openContainer(player,UUID.fromString(args[2]));
    }
    private static boolean reindex(AuditModule module,CommandSender sender,String[] args){require(sender,"illegalstack.audit.reindex");if(args.length<2||!args[1].equalsIgnoreCase("playerdata"))throw new IllegalArgumentException("Uso: /stack reindex playerdata [nick|uuid]");if(args.length>=3)module.playerdata().request(playerId(args[2]));else module.playerdata().requestScan(true);AuditModule.message(sender,ChatColor.GREEN+"Reindexacao agendada em segundo plano.");return true;}
    private static boolean backup(AuditModule module,CommandSender sender,String[] args){require(sender,"illegalstack.audit.admin");if(args.length<3||!args[1].equalsIgnoreCase("playerdata"))throw new IllegalArgumentException("Uso: /stack backup playerdata <nick|uuid>");module.playerdata().backup(playerId(args[2]),true).whenComplete((file,error)->Scheduler.runTask(IllegalStack.getPlugin(),()->AuditModule.message(sender,error==null?ChatColor.GREEN+"Backup criado: "+file.getName():ChatColor.RED+"Backup falhou: "+rootMessage(error))));return true;}
    private static boolean audit(AuditModule module,CommandSender sender,String[] args){require(sender,"illegalstack.audit.admin");if(args.length<2)throw new IllegalArgumentException("Uso: /stack audit <status|reload>");if(args[1].equalsIgnoreCase("reload")){try{module.reload();AuditModule.message(sender,ChatColor.GREEN+"Configuracao de auditoria recarregada.");}catch(Exception e){AuditModule.message(sender,ChatColor.RED+"Falha no reload: "+e.getMessage());}return true;}if(args[1].equalsIgnoreCase("status")){module.database().status().whenComplete((s,e)->Scheduler.runTask(IllegalStack.getPlugin(),()->{if(e!=null){AuditModule.message(sender,ChatColor.RED+"Falha no status.");return;}var p=module.playerdata().progress();AuditModule.message(sender,ChatColor.GOLD+"Audit="+(s.healthy()?"OK":"ERRO")+" players="+s.players()+" containers="+s.activeContainers()+" destroyed="+s.destroyedContainers()+" indices="+s.indexedItems()+" dirty="+module.dirty().size()+" dbQueue="+s.queue()+" dropped="+(s.dropped()+module.dirty().dropped())+" playerdata="+p.scanned()+"/"+p.total()+" running="+p.running());}));return true;}throw new IllegalArgumentException("Uso: /stack audit <status|reload>");}
    private static UUID playerId(String raw){
        try{return UUID.fromString(raw);}catch(IllegalArgumentException ignored){
            Player online=Bukkit.getPlayerExact(raw);if(online!=null)return online.getUniqueId();
            var cached=Bukkit.getOfflinePlayerIfCached(raw);if(cached!=null)return cached.getUniqueId();
            UUID indexed=IllegalStack.getPlugin().getAuditModule().playerdata().cachedId(raw);
            if(indexed!=null)return indexed;
            throw new IllegalArgumentException("Nick nao encontrado no cache local; informe o UUID ou reindexe playerdata.");
        }
    }
    private static String alias(String raw){return switch(raw.toLowerCase(Locale.ROOT)){case "container","bau"->"bau";case "inv","playerdata_inv"->"playerdata_inv";case "end","playerdata_end"->"playerdata_end";case "suspicious","suspeito"->"suspeito";default->raw.toLowerCase(Locale.ROOT);};}
    private static void require(CommandSender sender,String permission){if(!sender.hasPermission(permission))throw new IllegalArgumentException("Sem permissao: "+permission);}
    private static String rootMessage(Throwable t){while(t.getCause()!=null)t=t.getCause();return t.getMessage()==null?t.getClass().getSimpleName():t.getMessage();}
    static List<String> complete(CommandSender sender,String[] args){
        List<String> options=new ArrayList<>();
        if(args.length==2){
            switch(args[0].toLowerCase(Locale.ROOT)){
                case "search"->options.addAll(List.of("playerdata","playerdata_inv","playerdata_end","bau","all","suspeito"));
                case "view"->options.addAll(List.of("inv","end","bau"));
                case "reindex","backup"->options.add("playerdata");
                case "audit"->options.addAll(List.of("status","reload"));
                default->{return null;}
            }
        }else if(args.length==3&&args[0].equalsIgnoreCase("view")&&args[1].equalsIgnoreCase("bau"))options.add("here");
        else return null;
        String prefix=args[args.length-1].toLowerCase(Locale.ROOT);
        return options.stream().filter(v->v.startsWith(prefix)).toList();
    }
}
