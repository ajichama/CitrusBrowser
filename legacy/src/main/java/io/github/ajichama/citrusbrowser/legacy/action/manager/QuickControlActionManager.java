package io.github.ajichama.citrusbrowser.legacy.action.manager;

import android.content.Context;

import io.github.ajichama.citrusbrowser.legacy.action.Action;
import io.github.ajichama.citrusbrowser.legacy.action.ActionList;
import io.github.ajichama.citrusbrowser.legacy.action.ListActionManager;
import io.github.ajichama.citrusbrowser.legacy.action.SingleAction;

public class QuickControlActionManager extends ListActionManager {
    private static final String FOLDER_NAME = "action1_qc";

    private static final int LEVEL1 = 0x011;
    private static final int LEVEL2 = 0x012;
    private static final int LEVEL3 = 0x013;

    public final ActionArrayFile level1 = new ActionArrayFile(FOLDER_NAME, LEVEL1);
    public final ActionArrayFile level2 = new ActionArrayFile(FOLDER_NAME, LEVEL2);
    public final ActionArrayFile level3 = new ActionArrayFile(FOLDER_NAME, LEVEL3);

    @Override
    public void addAction(int id, Action action) {
        getActionList(id).add(action);
    }

    @Override
    public ActionList getActionList(int id) {
        switch (id) {
            case LEVEL1:
                return level1.getList();
            case LEVEL2:
                return level2.getList();
            case LEVEL3:
                return level3.getList();
        }
        throw new IllegalArgumentException("Unknown id:" + id);
    }

    /** 内側の輪（Level 1）の既定の項目。よく使う移動とタブの一覧（#36） */
    static final int[] DEFAULT_LEVEL1 = {
            SingleAction.GO_BACK,
            SingleAction.GO_FORWARD,
            SingleAction.WEB_RELOAD_STOP,
            SingleAction.TAB_LIST,
    };

    /** 外側の輪（Level 2）の既定の項目。ページの先頭・末尾とタブの操作（#36） */
    static final int[] DEFAULT_LEVEL2 = {
            SingleAction.PAGE_TOP,
            SingleAction.PAGE_BOTTOM,
            SingleAction.NEW_TAB,
            SingleAction.CLOSE_TAB,
            SingleAction.RESTORE_TAB,
    };

    /** Level 1〜3 に項目が1つもないか */
    public boolean isEmpty() {
        return level1.getList().isEmpty() && level2.getList().isEmpty() && level3.getList().isEmpty();
    }

    /** 既定の項目に置き換える。新しく入れたときと、空のまま有効にしたときの案内から使う（#36） */
    public void setDefaultItems() {
        fill(level1.getList(), DEFAULT_LEVEL1);
        fill(level2.getList(), DEFAULT_LEVEL2);
        level3.getList().clear();
    }

    private static void fill(ActionList list, int[] ids) {
        list.clear();
        for (int id : ids) {
            list.add(SingleAction.makeInstance(id));
        }
    }

    public static QuickControlActionManager getInstance(Context context) {
        QuickControlActionManager manager = new QuickControlActionManager();
        manager.load(context);
        return manager;
    }
}
