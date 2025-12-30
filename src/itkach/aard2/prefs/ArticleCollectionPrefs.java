package itkach.aard2.prefs;

public class ArticleCollectionPrefs extends Prefs {
    public static final String PREF_FULLSCREEN = "fullscreen";
    public static final String PREF_DISABLE_TAB_LABELS = "disableTabLabels";

    private static ArticleCollectionPrefs instance;

    private static ArticleCollectionPrefs getInstance() {
        if (instance == null) {
            instance = new ArticleCollectionPrefs();
        }
        return instance;
    }

    protected ArticleCollectionPrefs() {
        super("articleCollection");
    }

    public static boolean isFullscreen() {
        return getInstance().prefs.getBoolean(PREF_FULLSCREEN, false);
    }

    public static void setFullscreen(boolean fullscreen) {
        getInstance().prefs.edit().putBoolean(PREF_FULLSCREEN, fullscreen).apply();
    }

    public static boolean disableTabLabels() {
        return getInstance().prefs.getBoolean(PREF_DISABLE_TAB_LABELS, false);
    }

    public static void setDisableTabLabels(boolean disableTabLabels) {
        getInstance().prefs.edit().putBoolean(PREF_DISABLE_TAB_LABELS, disableTabLabels).apply();
    }
}
