package com.styly.deviceidprovider;

import androidx.annotation.Nullable;

final class Json {
    private Json() {}

    // Minimal JSON reader for {"device-id":"..."}
    @Nullable
    static String parseGuid(String json) {
        if (json == null) return null;
        int k = json.indexOf("\"device-id\"");
        if (k < 0) return null;
        int colon = json.indexOf(':', k);
        if (colon < 0) return null;
        int q1 = json.indexOf('"', colon + 1);
        if (q1 < 0) return null;
        int q2 = json.indexOf('"', q1 + 1);
        if (q2 <= q1) return null;
        return json.substring(q1 + 1, q2);
    }
}

