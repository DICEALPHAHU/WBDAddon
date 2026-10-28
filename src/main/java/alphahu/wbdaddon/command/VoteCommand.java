package alphahu.wbdaddon.command;

import alphahu.wbdaddon.WBDAddon;
import alphahu.wbdaddon.api.AddonMode;
import alphahu.wbdaddon.mode.BombDefuseMode;
import alphahu.wbdaddon.module.AddonModule;
import alphahu.wbdaddon.module.VoteModule;
import com.warz.bombdefuse.arena.ArenaSession;
import com.warz.bombdefuse.arena.PlayerRecord;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 投票相关命令的统一入口。
 *
 * <p>{@code /vyes}、{@code /vno}、{@code /vkick}、{@code /vmap}、{@code /vsdr}、
 * {@code /unpause} 都是同一份逻辑的不同动作，故共用一个执行器，
 * 由构造参数区分——比写六个几乎一样的小类清爽。
 *
 * <p>所有判定（谁有资格、门槛多少、通过了做什么）都在
 * {@link VoteModule} 里，命令类只负责把参数递过去。
 *
 * @author AlphaHu
 */
public class VoteCommand implements CommandExecutor, TabCompleter {

    /** 动作：表态、踢人、换图、投降、解除暂停。 */
    public enum Action {
        YES, NO, KICK, MAP, SURRENDER, UNPAUSE
    }

    private final WBDAddon plugin;
    private final Action action;

    public VoteCommand(WBDAddon plugin, Action action) {
        this.plugin = plugin;
        this.action = action;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender,
                             @NotNull Command command,
                             @NotNull String label,
                             @NotNull String[] args) {

        if (!(sender instanceof Player player)) {
            sender.sendMessage("§c该命令只能由玩家执行。");
            return true;
        }

        VoteModule votes = module();
        if (votes == null) {
            player.sendMessage("§c比赛投票模块未启用。");
            return true;
        }

        switch (action) {
            case YES -> votes.vote(player, true);
            case NO -> votes.vote(player, false);
            case UNPAUSE -> votes.startUnpause(player);
            case SURRENDER -> votes.startSurrender(player);
            case KICK -> {
                if (args.length < 1) {
                    player.sendMessage("§c用法：/vkick <同阵营玩家>");
                    return true;
                }
                votes.startKick(player, args[0]);
            }
            case MAP -> {
                if (args.length < 1) {
                    player.sendMessage("§c用法：/vmap <地图ID>");
                    return true;
                }
                votes.startMap(player, args[0]);
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender,
                                      @NotNull Command command,
                                      @NotNull String alias,
                                      @NotNull String[] args) {
        if (!(sender instanceof Player player) || args.length != 1) {
            return List.of();
        }
        if (action == Action.MAP) {
            return completeMap(player, args[0]);
        }
        if (action == Action.KICK) {
            return completeTeammate(player, args[0]);
        }
        return List.of();
    }

    /** 补全可换过去的地图：启用的、且不是自己正在打的那张。 */
    private List<String> completeMap(Player player, String prefix) {
        List<String> ids = new ArrayList<>();
        if (plugin.getWbd() == null || plugin.getWbd().getArenaManager() == null) {
            return ids;
        }
        String current = arenaIdOf(player);
        for (ArenaSession session : plugin.getWbd().getArenaManager().getSessions()) {
            String id = session.getConfig().getId();
            if (!session.getConfig().isEnabled() || id.equals(current)) {
                continue;
            }
            if (id.startsWith(prefix.toLowerCase())) {
                ids.add(id);
            }
        }
        return ids;
    }

    /** 补全同阵营玩家。 */
    private List<String> completeTeammate(Player player, String prefix) {
        List<String> names = new ArrayList<>();
        String arenaId = arenaIdOf(player);
        if (arenaId == null || plugin.getWbd() == null
                || plugin.getWbd().getArenaManager() == null) {
            return names;
        }
        ArenaSession session = plugin.getWbd().getArenaManager().getSession(arenaId);
        if (session == null) {
            return names;
        }
        var team = session.getTeam(player);
        for (PlayerRecord record : session.getPlayers()) {
            if (record.getTeam() != team
                    || record.getUuid().equals(player.getUniqueId())) {
                continue;
            }
            Player online = Bukkit.getPlayer(record.getUuid());
            if (online == null) {
                continue;
            }
            if (online.getName().toLowerCase().startsWith(prefix.toLowerCase())) {
                names.add(online.getName());
            }
        }
        return names;
    }

    private String arenaIdOf(Player player) {
        if (plugin.getApi() == null) {
            return null;
        }
        AddonMode mode = plugin.getApi().getModeFor(player);
        if (mode == null || !BombDefuseMode.MODE_ID.equals(mode.getModeId())) {
            return null;
        }
        return mode.getArenaId(player);
    }

    private VoteModule module() {
        AddonModule module = plugin.getModuleManager().getModule("vote");
        if (module instanceof VoteModule votes && plugin.getModuleManager().isEnabled(module)) {
            return votes;
        }
        return null;
    }
}
