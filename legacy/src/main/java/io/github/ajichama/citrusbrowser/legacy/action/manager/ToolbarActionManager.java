package io.github.ajichama.citrusbrowser.legacy.action.manager;

import android.content.Context;

import io.github.ajichama.citrusbrowser.legacy.action.Action;
import io.github.ajichama.citrusbrowser.legacy.action.SingleAction;
import io.github.ajichama.citrusbrowser.legacy.action.SoftButtonActionArrayManagerBase;

public class ToolbarActionManager extends SoftButtonActionArrayManagerBase {
    private static final String FOLDER_NAME = "action1_custombar";

    //the same in attrs.xml
    public static final int FIELD_ID = 0xFF000000;
    public static final int FIELD_NO = 0x00FF0000;
    public static final int FIELD_ID_SHIFT = 6 * 4;
    public static final int FIELD_NO_SHIFT = 4 * 4;
    public static final int CUSTOMBAR_1 = 0x01000000;
    public static final int FLOATBAR_1 = 0x02000000;


    public final SoftButtonActionArrayFile custombar1 = new SoftButtonActionArrayFile(FOLDER_NAME, CUSTOMBAR_1);
    // フローティングツールバーのボタン（#35）
    public final SoftButtonActionArrayFile floatbar1 = new SoftButtonActionArrayFile(FOLDER_NAME, FLOATBAR_1);

    @Override
    public Action getAction(int id) {
        return getActionArrayFile(id).getActionList((id & FIELD_NO) >> FIELD_NO_SHIFT).getAction(id);
    }

    @Override
    public SoftButtonActionArrayFile getActionArrayFile(int id) {
        switch (id & FIELD_ID) {
            case CUSTOMBAR_1:
                return custombar1;
            case FLOATBAR_1:
                return floatbar1;
        }
        throw new IllegalArgumentException("Unknown id:" + id);
    }

    @Override
    public int makeActionIdFromPosition(int id, int position) {
        return id | (position << FIELD_NO_SHIFT);
    }

    /**
     * フローティングツールバーのボタンを一度も保存していなければ、既定のボタンを入れる（#35）。
     * 新しく入れた人にも、前から使っている人にも、有効にした時点でボタンがあるようにする。
     */
    private void applyFloatbarDefaults(Context context) {
        if (!floatbar1.getList().isEmpty() || floatbar1.getFile(context).exists()) return;
        floatbar1.add(SingleAction.makeInstance(SingleAction.GO_BACK));
        floatbar1.add(SingleAction.makeInstance(SingleAction.GO_FORWARD));
        floatbar1.add(SingleAction.makeInstance(SingleAction.TAB_LIST));
        floatbar1.add(SingleAction.makeInstance(SingleAction.OPEN_OPTIONS_MENU));
    }

    public static ToolbarActionManager getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new ToolbarActionManager();
            sInstance.load(context);
            sInstance.applyFloatbarDefaults(context);
        }
        return sInstance;
    }

    private static ToolbarActionManager sInstance = null;
}
