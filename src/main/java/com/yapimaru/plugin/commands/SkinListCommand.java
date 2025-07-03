package com.yapimaru.plugin.commands;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

public class SkinListCommand implements CommandExecutor {
    private final BukkitAudiences adventure;
    private static final Map<String, String> SKIN_LIST_MAP = new LinkedHashMap<>();
    static {
        SKIN_LIST_MAP.put("KURU", "クル");
        SKIN_LIST_MAP.put("micro", "ミクロ");
        SKIN_LIST_MAP.put("Miloka", "ミロカ");
        SKIN_LIST_MAP.put("YAPIMARU", "やぴまる");
        SKIN_LIST_MAP.put("Yuchi", "ゆーちぃ");
        SKIN_LIST_MAP.put("8bit", "8ビット");
        SKIN_LIST_MAP.put("Amber", "アンバー");
        SKIN_LIST_MAP.put("Angelo", "アンジェロ");
        SKIN_LIST_MAP.put("Ash", "アッシュ");
        SKIN_LIST_MAP.put("Barley", "バーリー");
        SKIN_LIST_MAP.put("Bea", "ビー");
        SKIN_LIST_MAP.put("Belle", "ベル");
        SKIN_LIST_MAP.put("Berry", "ベリー");
        SKIN_LIST_MAP.put("Bibi", "ビビ");
        SKIN_LIST_MAP.put("Bo", "ボウ");
        SKIN_LIST_MAP.put("Bonnie", "ボニー");
        SKIN_LIST_MAP.put("Brock", "ブロック");
        SKIN_LIST_MAP.put("Bull", "ブル");
        SKIN_LIST_MAP.put("Buster", "バスター");
        SKIN_LIST_MAP.put("Buzz", "バズ");
        SKIN_LIST_MAP.put("Byron", "バイロン");
        SKIN_LIST_MAP.put("Carl", "カール");
        SKIN_LIST_MAP.put("Charlie", "チャーリー");
        SKIN_LIST_MAP.put("Chester", "チェスター");
        SKIN_LIST_MAP.put("Chuck", "チャック");
        SKIN_LIST_MAP.put("Clancy", "クランシー");
        SKIN_LIST_MAP.put("Colette", "コレット");
        SKIN_LIST_MAP.put("Colt", "コルト");
        SKIN_LIST_MAP.put("Cordelius", "コーデリアス");
        SKIN_LIST_MAP.put("Crow", "クロウ");
        SKIN_LIST_MAP.put("Darryl", "ダリル");
        SKIN_LIST_MAP.put("Doug", "ダグ");
        SKIN_LIST_MAP.put("Draco", "ドラコ");
        SKIN_LIST_MAP.put("Dynamike", "ダイナマイク");
        SKIN_LIST_MAP.put("Edgar", "エドガー");
        SKIN_LIST_MAP.put("El_Primo", "エル・プリモ");
        SKIN_LIST_MAP.put("Emz", "エムズ");
        SKIN_LIST_MAP.put("Eve", "イヴ");
        SKIN_LIST_MAP.put("Fang", "ファング");
        SKIN_LIST_MAP.put("Finx", "フィンクス");
        SKIN_LIST_MAP.put("Frank", "フランケン");
        SKIN_LIST_MAP.put("Gale", "ゲイル");
        SKIN_LIST_MAP.put("Gene", "ジーン");
        SKIN_LIST_MAP.put("Gray", "グレイ");
        SKIN_LIST_MAP.put("Griff", "グリフ");
        SKIN_LIST_MAP.put("Grom", "グロム");
        SKIN_LIST_MAP.put("Gus", "ガス");
        SKIN_LIST_MAP.put("Hank", "ハンク");
        SKIN_LIST_MAP.put("Jacky", "ジャッキー");
        SKIN_LIST_MAP.put("Jae_Yong", "ジェヨン");
        SKIN_LIST_MAP.put("Janet", "ジャネット");
        SKIN_LIST_MAP.put("Jessie", "ジェシー");
        SKIN_LIST_MAP.put("Juju", "ジュジュ");
        SKIN_LIST_MAP.put("Kaze", "カゼ");
        SKIN_LIST_MAP.put("Kenji", "ケンジ");
        SKIN_LIST_MAP.put("Kit", "キット");
        SKIN_LIST_MAP.put("Larry", "ラリー");
        SKIN_LIST_MAP.put("Lawrie", "ローリー");
        SKIN_LIST_MAP.put("Leon", "レオン");
        SKIN_LIST_MAP.put("Lily", "リリー");
        SKIN_LIST_MAP.put("Lola", "ローラ");
        SKIN_LIST_MAP.put("Lou", "ルー");
        SKIN_LIST_MAP.put("Lumi", "ルミ");
        SKIN_LIST_MAP.put("Maisie", "メイジー");
        SKIN_LIST_MAP.put("Mandy", "マンディ");
        SKIN_LIST_MAP.put("Max", "マックス");
        SKIN_LIST_MAP.put("Meeple", "ミープル");
        SKIN_LIST_MAP.put("Meg", "メグ");
        SKIN_LIST_MAP.put("Melodie", "メロディー");
        SKIN_LIST_MAP.put("Mico", "ミコ");
        SKIN_LIST_MAP.put("Moe", "モー");
        SKIN_LIST_MAP.put("Mortis", "モーティス");
        SKIN_LIST_MAP.put("Mrp", "ミスターP");
        SKIN_LIST_MAP.put("Nani", "ナーニ");
        SKIN_LIST_MAP.put("Nita", "ニタ");
        SKIN_LIST_MAP.put("Ollie", "オーリー");
        SKIN_LIST_MAP.put("Otis", "オーティス");
        SKIN_LIST_MAP.put("Pam", "パム");
        SKIN_LIST_MAP.put("Pearl", "パール");
        SKIN_LIST_MAP.put("Penny", "ペニー");
        SKIN_LIST_MAP.put("Piper", "エリザベス");
        SKIN_LIST_MAP.put("Poco", "ポコ");
        SKIN_LIST_MAP.put("Rico", "リコ");
        SKIN_LIST_MAP.put("Rosa", "ローサ");
        SKIN_LIST_MAP.put("RT", "R-T");
        SKIN_LIST_MAP.put("Ruffs", "ラフス");
        SKIN_LIST_MAP.put("Sam", "サム");
        SKIN_LIST_MAP.put("Sandy", "サンディ");
        SKIN_LIST_MAP.put("Shade", "シェィド");
        SKIN_LIST_MAP.put("Shelly", "シェリー");
        SKIN_LIST_MAP.put("Spike", "スパイク");
        SKIN_LIST_MAP.put("Sprout", "スプラウト");
        SKIN_LIST_MAP.put("Squeak", "スクウィーク");
        SKIN_LIST_MAP.put("Stu", "ストゥー");
        SKIN_LIST_MAP.put("Surge", "サージ");
        SKIN_LIST_MAP.put("Tara", "タラ");
        SKIN_LIST_MAP.put("Tick", "ティック");
        SKIN_LIST_MAP.put("Willow", "ウィロー");
    }

    public SkinListCommand(BukkitAudiences adventure) { this.adventure = adventure; }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        adventure.sender(sender).sendMessage(Component.text("--- スキンリスト (クリックでコマンド入力) ---", NamedTextColor.GOLD));
        adventure.sender(sender).sendMessage(Component.text("スキン変更方法: /skin <水色の箇所>[数字]  (例: /skin Finx1)", NamedTextColor.GRAY));

        Map<String, String> filteredMap = SKIN_LIST_MAP;
        if (args.length > 0) {
            String searchTerm = args[0];
            boolean isJapanese = searchTerm.matches("^[\\u3040-\\u309F\\u30A0-\\u30FF]+$");

            filteredMap = SKIN_LIST_MAP.entrySet().stream()
                    .filter(entry -> {
                        if (isJapanese) {
                            return entry.getValue().startsWith(searchTerm);
                        } else {
                            return entry.getKey().toLowerCase().startsWith(searchTerm.toLowerCase());
                        }
                    })
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (e1, e2) -> e1, LinkedHashMap::new));

            if (filteredMap.isEmpty()) {
                adventure.sender(sender).sendMessage(Component.text("「" + searchTerm + "」に一致するスキンは見つかりませんでした。", NamedTextColor.RED));
                return true;
            }
        }

        filteredMap.forEach((original, display) -> {
            String commandToSuggest = "/skin " + original;
            Component msg = Component.text().append(Component.text(display, NamedTextColor.YELLOW))
                    .append(Component.text(" - ", NamedTextColor.GRAY)).append(Component.text(original, NamedTextColor.AQUA))
                    .hoverEvent(HoverEvent.showText(Component.text("コマンド '" + commandToSuggest + "' を入力")))
                    .clickEvent(ClickEvent.suggestCommand(commandToSuggest))
                    .build();
            adventure.sender(sender).sendMessage(msg);
        });
        return true;
    }
}