package com.yapimaru.plugin.commands;

import org.bukkit.GameMode;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Minecraftサーバーのゲームモードを設定するためのコマンドを処理します。
 * プレイヤーにクリエイティブモードまたはサバイバルモードを切り替える機能を提供します。
 */
public class ServerCommand implements CommandExecutor {

    /**
     * コマンドが実行されたときに呼び出されるメソッドです。
     *
     * @param sender コマンドの送信者（プレイヤーまたはコンソール）
     * @param command 実行されたコマンドのオブジェクト
     * @param label コマンドのエイリアス
     * @param args コマンドに続く引数の配列
     * @return コマンドが正常に処理された場合はtrue、そうでない場合はfalse
     */
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // コマンドの送信者がプレイヤーであるかを確認し、Playerオブジェクトにキャストします。
        // コンソールからの実行の場合、playerはnullになります。
        Player player = null;
        if (sender instanceof Player) {
            player = (Player) sender;
        }

        // 引数の数が適切かどうかをチェックします。
        // 引数が0の場合（例: /server のみ）、使用方法を表示します。
        if (args.length == 0) {
            sender.sendMessage("§c使用方法: /server <creative|survival>");
            return true;
        }

        // コマンドの送信者がプレイヤー以外（例: コンソール）である場合、
        // このコマンドはプレイヤーのみが実行できることを伝えます。
        if (player == null) {
            sender.sendMessage("§cこのコマンドはプレイヤーのみ実行できます。");
            return true;
        }

        // コマンドの最初の引数をゲームモードの文字列として取得し、小文字に変換します。
        // 例: "Creative" -> "creative"
        String serverMode = args[0].toLowerCase();

        // 設定するゲームモードを保持するための変数を初期化します。
        GameMode gameMode = null;

        // 指定されたゲームモードの文字列に基づいて、対応するGameMode列挙型を設定します。
        // "creative" が指定された場合はCREATIVEモードに、"survival" が指定された場合はSURVIVALモードに設定します。
        if (serverMode.equals("creative")) {
            gameMode = GameMode.CREATIVE;
        } else if (serverMode.equals("survival")) {
            gameMode = GameMode.SURVIVAL;
        } else {
            // "creative" または "survival" 以外の無効なゲームモードが指定された場合、
            // プレイヤーにエラーメッセージを表示します。
            player.sendMessage("§c不明なゲームモードです。creativeまたはsurvivalを指定してください。");
            return true;
        }

        // プレイヤーのゲームモードを、決定されたGameModeに設定します。
        player.setGameMode(gameMode);
        // プレイヤーにゲームモードが正常に設定されたことを通知するメッセージを表示します。
        player.sendMessage("§aゲームモードを" + serverMode + "に設定しました。");

        // コマンドが正常に処理されたことを呼び出し元に通知するためにtrueを返します。
        return true;
    }
}
