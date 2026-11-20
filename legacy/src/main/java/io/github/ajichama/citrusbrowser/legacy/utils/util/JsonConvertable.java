package io.github.ajichama.citrusbrowser.legacy.utils.util;

public interface JsonConvertable {
    String toJsonString();

    boolean fromJsonString(String str);
}
