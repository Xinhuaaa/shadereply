package io.shadereply;

import java.util.Locale;

final class WeChatBubbleSettings {
    private static final String READ = """
            policy=/data/system/notification_policy.xml
            [ -r "$policy" ] || exit 1
            preference=$(abx2xml "$policy" - | awk -v uid=%d '
                /<package[[:space:]]/ && /name="com[.]tencent[.]mm"/ && index($0, "uid=" q uid q) {
                    if (index($0, "allow_bubble=" q "1" q)) print 1
                    else if (index($0, "allow_bubble=" q "2" q)) print 2
                    else print 0
                    exit
                }
                BEGIN { q = sprintf("%%c", 34) }
            ')
            echo "${preference:-0}"
            """;

    private WeChatBubbleSettings() {}

    static int read(int uid) {
        var result = RootCommand.run(String.format(Locale.ROOT, READ, uid));
        if (!result.succeeded()) return -1;
        return switch (result.output()) {
            case "0" -> 0;
            case "1" -> 1;
            case "2" -> 2;
            default -> -1;
        };
    }

    static boolean apply(int userId, int preference) {
        if (userId < 0 || preference < 0 || preference > 2) return false;
        return RootCommand.run("cmd notification set_bubbles com.tencent.mm " +
                preference + " " + userId).succeeded();
    }
}
