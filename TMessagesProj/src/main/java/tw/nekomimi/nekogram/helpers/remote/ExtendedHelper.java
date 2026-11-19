package tw.nekomimi.nekogram.helpers.remote;

import org.json.JSONObject;

import java.util.ArrayList;

public class ExtendedHelper extends BaseRemoteHelper {
    private static final String EXTENDED_TAG = "extended";
    private static volatile ExtendedHelper Instance;

    public static ExtendedHelper getInstance() {
        ExtendedHelper localInstance = Instance;
        if (localInstance == null) {
            synchronized (ExtendedHelper.class) {
                localInstance = Instance;
                if (localInstance == null) {
                    Instance = localInstance = new ExtendedHelper();
                }
            }
        }
        return localInstance;
    }

    @Override
    protected void onError(String text, Delegate delegate) {
    }

    @Override
    protected String getTag() {
        return EXTENDED_TAG;
    }

    @Override
    protected void onLoadSuccess(ArrayList<JSONObject> responses, Delegate delegate) {
    }

    public void checkExtended() {
        // Remote check & clipboard reading disabled
    }

    public String getData() {
        return "";
    }

    public boolean hasExtended() {
        return true;
    }
}
