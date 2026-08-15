package top.xuyangjerry.mcmod.lcp.config;

public enum ChatHistoryView {
    GLOBAL("global"),
    CURRENT_VERSION("current_version"),
    CURRENT_WORLD("current_world");

    private final String key;

    ChatHistoryView(String key) {
        this.key = key;
    }

    public String getKey() {
        return key;
    }

    public static ChatHistoryView fromKey(String key) {
        for (ChatHistoryView view : values()) {
            if (view.key.equals(key)) {
                return view;
            }
        }
        return CURRENT_WORLD;
    }
}