package com.styly.deviceid;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CandidateSelector {
    private static final Pattern GUID_PNG_PATTERN = Pattern.compile(
            "^([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\.png$");

    private static final Comparator<MediaCandidate> CANDIDATE_ORDER =
            Comparator.comparingLong((MediaCandidate candidate) -> candidate.dateAdded)
                    .thenComparingLong(candidate -> candidate.id);

    private CandidateSelector() {
    }

    static Selection select(List<MediaCandidate> candidates) {
        List<MediaCandidate> valid = new ArrayList<>();
        for (MediaCandidate candidate : candidates) {
            if (extractGuid(candidate.displayName) != null) {
                valid.add(candidate);
            }
        }
        valid.sort(CANDIDATE_ORDER);

        if (valid.isEmpty()) {
            return new Selection(null, 0);
        }
        MediaCandidate winner = valid.get(0);
        return new Selection(extractGuid(winner.displayName), valid.size());
    }

    static String extractGuid(String displayName) {
        if (displayName == null) {
            return null;
        }
        Matcher matcher = GUID_PNG_PATTERN.matcher(displayName);
        return matcher.matches() ? matcher.group(1) : null;
    }

    static final class Selection {
        final String deviceId;
        final int candidateCount;

        Selection(String deviceId, int candidateCount) {
            this.deviceId = deviceId;
            this.candidateCount = candidateCount;
        }

        boolean isPresent() {
            return deviceId != null;
        }
    }
}
