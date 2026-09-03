package com.styly.deviceid;

final class MediaCandidate {
    final long id;
    final long dateAdded;
    final String displayName;

    MediaCandidate(long id, long dateAdded, String displayName) {
        this.id = id;
        this.dateAdded = dateAdded;
        this.displayName = displayName;
    }
}
