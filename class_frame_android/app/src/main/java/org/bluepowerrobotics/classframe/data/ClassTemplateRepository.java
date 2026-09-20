package org.bluepowerrobotics.classframe.data;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** classes_frame/*.json 班级模板。 */
public final class ClassTemplateRepository {

    private ClassTemplateRepository() {
    }

    public static List<String> listNames(Context context) {
        ConfigRepository.ensureInitialized(context);
        List<String> names = new ArrayList<>();
        File[] files = ConfigRepository.classDir(context).listFiles();
        if (files == null) return names;
        for (File file : files) {
            String name = file.getName();
            if (file.isFile() && name.toLowerCase().endsWith(".json")) {
                names.add(name.substring(0, name.length() - 5));
            }
        }
        Collections.sort(names);
        return names;
    }

    public static File fileOf(Context context, String name) {
        return new File(ConfigRepository.classDir(context), name + ".json");
    }

    public static JSONObject read(Context context, String name) throws Exception {
        File file = fileOf(context, name);
        return new JSONObject(ConfigRepository.readUtf8(file));
    }
}
