package io.shadereply;

final class SystemUiRestart {
    private static final String COMMAND = """
            [ "$(id -u)" = 0 ] || exit 2
            set -- $(pidof com.android.systemui)
            [ "$#" = 1 ] || exit 3
            old="$1"
            case "$old" in ''|*[!0-9]*) exit 3 ;; esac
            kill -KILL "$old" || exit 3
            for attempt in 1 2 3 4 5 6 7 8 9 10; do
                sleep 1
                set -- $(pidof com.android.systemui)
                if [ "$#" = 1 ] && [ "$1" != "$old" ]; then exit 0; fi
            done
            exit 4
            """;

    private SystemUiRestart() {}

    static int run() {
        return RootCommand.run(COMMAND).status();
    }
}
