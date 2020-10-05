package net.teamfruit.speedtest;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTCreationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.auth0.jwt.interfaces.Verification;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;

import java.io.IOException;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;

public final class SpeedTest extends JavaPlugin {
    public static String PluginName;
    public CallbackServerInstance serverInstance;

    private Algorithm algorithmPlugin;
    private Algorithm algorithmWeb;

    private Objective objectiveDown;
    private Objective objectiveUp;
    private Objective objectivePing;
    private Objective objectiveJitter;
    private Objective objectiveDone;

    private boolean enable;

    private Objective getOrRegisterObjective(Scoreboard sb, String name, String desc) {
        Objective obj = sb.getObjective(name);
        if (obj == null)
            obj = sb.registerNewObjective(name, "dummy", desc);
        return obj;
    }

    @Override
    public void onEnable() {
        // Plugin startup logic
        PluginName = getName();
        Log.log = getLogger();

        saveDefaultConfig();

        Scoreboard sb = getServer().getScoreboardManager().getMainScoreboard();
        objectiveDown = getOrRegisterObjective(sb, "sp_down", "ダウンロード速度 (Mbps)");
        objectiveUp = getOrRegisterObjective(sb, "sp_up", "アップロード速度 (Mbps)");
        objectivePing = getOrRegisterObjective(sb, "sp_ping", "Ping (ms)");
        objectiveJitter = getOrRegisterObjective(sb, "sp_jitter", "Jitter (ms)");
        objectiveDone = getOrRegisterObjective(sb, "sp_done", "測定済み");

        try {
            serverInstance = new CallbackServerInstance(this::onTestResult,
                    getConfig().getString("speedtest.allow-origin"),
                    getConfig().getInt("speedtest.port")
            );
        } catch (IOException e) {
            throw new RuntimeException("Could not create callback server", e);
        }

        algorithmPlugin = Algorithm.HMAC256(getConfig().getString("speedtest.key-plugin"));
        algorithmWeb = Algorithm.HMAC256(getConfig().getString("speedtest.key-web"));

        new BukkitRunnable() {
            @Override
            public void run() {
                if (!enable)
                    return;

                for (Player player : getServer().getOnlinePlayers()) {
                    Score downScore = objectiveDown.getScore(player.getName());
                    if (downScore.isScoreSet()) {
                        int downRaw = downScore.getScore();
                        double x = Math.log10(downRaw + 1);
                        int down = downRaw > 1300 ? 30 : (int) ((0.7444) * Math.pow(x, 3) + (-0.0614) * Math.pow(x, 2) + (1.5959) * x + -5.0349);
                        if (down > 0)
                            player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 100, down - 1));
                        else if (down < 0)
                            player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW, 100, -down - 1));
                    }

                    Score upScore = objectiveUp.getScore(player.getName());
                    if (upScore.isScoreSet()) {
                        int upRaw = objectiveUp.getScore(player.getName()).getScore();
                        double x0 = Math.log10(upRaw / 2 + 1);
                        int up = (int) ((0.7444) * Math.pow(x0, 3) + (-0.0614) * Math.pow(x0, 2) + (1.5959) * x0 + -1);
                        if (up > 0)
                            player.addPotionEffect(new PotionEffect(PotionEffectType.JUMP, 100, up - 1));
                    }

                    Score pingScore = objectivePing.getScore(player.getName());
                    if (pingScore.isScoreSet()) {
                        int pingRaw = pingScore.getScore();
                        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_DIGGING, 100, pingRaw > 40 ? 1 : 0));
                    }
                }
            }
        }.runTaskTimer(this, 0, 20);
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
        serverInstance.close();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if ("speedtestadmin".equals(command.getName())) {
            if ("on".equals(args[0])) {
                enable = true;
                sender.sendMessage("SpeedTestの速度が適用されました");
                return true;
            } else if ("off".equals(args[0])) {
                enable = false;
                sender.sendMessage("SpeedTestの速度が無効化されました");
                return true;
            } else if ("announce".equals(args[0])) {
                getServer().getOnlinePlayers().forEach(this::invite);
                sender.sendMessage("全員に招待しました");
                return true;
            }

            return false;
        } else if ("speedtest".equals(command.getName())) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("プレイヤーから実行してください");
                return true;
            }

            invite((Player) sender);

            return true;
        }

        return false;
    }

    private void invite(Player sender) {
        String entryPoint = getConfig().getString("speedtest.entry-point");
        String callbackPoint = getConfig().getString("speedtest.callback-point");
        String name = sender.getName();
        String callback = String.format(callbackPoint, serverInstance.getPort());

        String token;
        try {
            token = JWT.create()
                    .withClaim("ip", sender.getAddress().getHostString())
                    .withClaim("uuid", sender.getPlayerProfile().getId().toString())
                    .sign(algorithmPlugin);
        } catch (JWTCreationException e) {
            sender.sendMessage("トークン作成時にエラーが発生しました。コマンド勢にお問い合わせください");
            Log.log.log(Level.SEVERE, "Error while JWT creation (player: " + name + "): ", e);
            return;
        }

        String url = String.format("%s?name=%s&callback=%s&token=%s", entryPoint, name, callback, token);

        sender.sendMessage(
                new ComponentBuilder(ChatColor.GOLD + "[ここをクリックしてスピードテストを開始]")
                        .event(new ClickEvent(ClickEvent.Action.OPEN_URL, url))
                        .create()
        );
    }

    public void onTestResult(SpeedTestData.FeedbackData result) {
        DecodedJWT decodedResult = JWT.decode(result.token);
        Verification verifierResult = JWT.require(algorithmWeb);
        verifierResult.build().verify(decodedResult);

        String token = decodedResult.getClaim("token").asString();
        DecodedJWT decodedToken = JWT.decode(token);
        Verification verifierToken = JWT.require(algorithmPlugin);
        verifierToken.build().verify(decodedToken);

        double down = decodedResult.getClaim("sp_down").asDouble();
        double up = decodedResult.getClaim("sp_up").asDouble();
        double ping = decodedResult.getClaim("sp_ping").asDouble();
        double jitter = decodedResult.getClaim("sp_jitter").asDouble();

        String ip = decodedToken.getClaim("ip").asString();
        String uuid = decodedToken.getClaim("uuid").asString();

        String resultText = String.format(
                "下り: %f Mbps, 上り: %f Mbps, Ping: %f ms, Jitter: %f ms",
                down, up, ping, jitter
        );
        Log.log.log(
                Level.INFO,
                "ip: {0}, uuid: {1}, remoteip: {2}, {3}",
                new Object[]{ip, uuid, result.ip, resultText}
        );

        OfflinePlayer offlinePlayer = getServer().getOfflinePlayer(UUID.fromString(uuid));
        String name = offlinePlayer.getName();
        objectiveDown.getScore(name).setScore((int) down);
        objectiveUp.getScore(name).setScore((int) up);
        objectivePing.getScore(name).setScore((int) ping);
        objectiveJitter.getScore(name).setScore((int) jitter);
        objectiveDone.getScore(name).setScore(Objects.equals(ip, result.ip) ? 1 : 2);

        if (offlinePlayer.isOnline()) {
            Player player = offlinePlayer.getPlayer();
            player.sendMessage(ChatColor.GOLD + "スピードテストお疲れさまです！ あなたの結果は");
            player.sendMessage(ChatColor.GOLD + resultText);
            player.sendMessage(ChatColor.GOLD + "でした。");
        }
    }
}
