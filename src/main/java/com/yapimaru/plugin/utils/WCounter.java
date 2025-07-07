package com.yapimaru.plugin.utils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * チャットメッセージのwカウントを計算するユーティリティクラス。
 * 最終仕様v3に基づいています。
 */
public class WCounter {

    // ルールCで定義されたキーワード
    private static final Pattern KEYWORD_PATTERN = Pattern.compile("kusa|草|wara|笑|lol", Pattern.CASE_INSENSITIVE);
    // ルールAで定義された連続するw
    private static final Pattern CONSECUTIVE_W_PATTERN = Pattern.compile("w{2,}", Pattern.CASE_INSENSITIVE);

    /**
     * 指定されたメッセージからw_countの仕様(v3)に基づいてカウントします。
     * @param message カウント対象のチャットメッセージ
     * @return 計算されたwカウント
     */
    public static int countW(String message) {
        if (message == null || message.isEmpty()) {
            return 0;
        }

        // ステップ1 & 2: 括弧とその中身をすべて削除
        String processedMessage = removeAllBracketsAndContent(message);

        int count = 0;

        // ステップ3: 最終的な文字列に対するカウント

        // ルールC: kusa, 草, wara, 笑, lolをそれぞれ+1としてカウント
        Matcher keywordMatcher = KEYWORD_PATTERN.matcher(processedMessage);
        while (keywordMatcher.find()) {
            count++;
        }
        // カウントしたキーワードを削除して、wの重複カウントを防ぐ
        processedMessage = keywordMatcher.replaceAll(" ");

        // ルールA: 文中でwが2つ以上連続している部分を、その数だけカウント (例: wwは2, wwwは3)
        Matcher consecutiveWMatcher = CONSECUTIVE_W_PATTERN.matcher(processedMessage);
        while (consecutiveWMatcher.find()) {
            count += consecutiveWMatcher.group().length();
        }
        // カウントした連続wを削除
        processedMessage = consecutiveWMatcher.replaceAll(" ");

        // ルールB: 文字列の末尾にあるwは、1つでもカウント
        if (processedMessage.trim().toLowerCase().endsWith("w")) {
            count++;
        }

        return count;
    }

    /**
     * 文字列からすべての括弧（入れ子含む）と、その中身を削除します。
     * @param text 対象の文字列
     * @return 括弧ブロックが削除された文字列
     */
    private static String removeAllBracketsAndContent(String text) {
        String current = text;
        String previous;
        do {
            previous = current;
            // 最も内側の単純な括弧（半角・全角）を削除する処理を繰り返す
            current = previous.replaceAll("\\([^()]*\\)", "").replaceAll("（[^（）]*）", "");
        } while (!current.equals(previous)); // 変更がなくなるまで繰り返す
        return current;
    }
}