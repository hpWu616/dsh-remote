package com.example.dsh_apk;

import android.graphics.Color;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.URLSpan;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class MarkdownFormatter {
    private static final Pattern INLINE = Pattern.compile(
            "\\*\\*(.+?)\\*\\*|`([^`]+)`|\\[([^]]+)]\\((https?://[^)]+)\\)");
    private static final Pattern HEADING = Pattern.compile("^(#{1,4})\\s+(.+)$");
    private static final Pattern BULLET = Pattern.compile("^\\s*[-*]\\s+(.+)$");
    private static final int CODE_BACKGROUND = Color.rgb(237, 241, 247);

    private MarkdownFormatter() { }

    static CharSequence render(String markdown) {
        SpannableStringBuilder result = new SpannableStringBuilder();
        boolean fenced = false;
        String[] lines = markdown.replace("\r\n", "\n").split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.trim().startsWith("```")) {
                fenced = !fenced;
                continue;
            }
            if (result.length() > 0) result.append('\n');
            if (fenced) {
                int start = result.length();
                result.append(line.isEmpty() ? " " : line);
                result.setSpan(new TypefaceSpan("monospace"), start, result.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                result.setSpan(new BackgroundColorSpan(CODE_BACKGROUND), start, result.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                continue;
            }
            Matcher heading = HEADING.matcher(line);
            Matcher bullet = BULLET.matcher(line);
            if (heading.matches()) {
                int start = result.length();
                appendInline(result, heading.group(2));
                result.setSpan(new StyleSpan(Typeface.BOLD), start, result.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                result.setSpan(new RelativeSizeSpan(heading.group(1).length() == 1 ? 1.32f : 1.16f),
                        start, result.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (bullet.matches()) {
                result.append("•  ");
                appendInline(result, bullet.group(1));
            } else {
                appendInline(result, line);
            }
        }
        return result;
    }

    private static void appendInline(SpannableStringBuilder output, String line) {
        Matcher matcher = INLINE.matcher(line);
        int cursor = 0;
        while (matcher.find()) {
            output.append(line, cursor, matcher.start());
            int start = output.length();
            if (matcher.group(1) != null) {
                output.append(matcher.group(1));
                output.setSpan(new StyleSpan(Typeface.BOLD), start, output.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (matcher.group(2) != null) {
                output.append(matcher.group(2));
                output.setSpan(new TypefaceSpan("monospace"), start, output.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                output.setSpan(new BackgroundColorSpan(CODE_BACKGROUND), start, output.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                output.append(matcher.group(3));
                output.setSpan(new URLSpan(matcher.group(4)), start, output.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                output.setSpan(new ForegroundColorSpan(Color.rgb(52, 104, 220)), start,
                        output.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            cursor = matcher.end();
        }
        output.append(line, cursor, line.length());
    }
}
