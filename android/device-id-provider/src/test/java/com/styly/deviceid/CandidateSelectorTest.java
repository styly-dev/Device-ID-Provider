package com.styly.deviceid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public final class CandidateSelectorTest {
    private static final String GUID_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String GUID_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";

    @Test
    public void select_usesDateAddedThenIdAsTotalOrder() {
        CandidateSelector.Selection selection = CandidateSelector.select(Arrays.asList(
                candidate(20, 100, GUID_B),
                candidate(10, 100, GUID_A)));

        assertTrue(selection.isPresent());
        assertEquals(GUID_A, selection.deviceId);
        assertEquals(2, selection.candidateCount);
    }

    @Test
    public void select_prefersOlderDateBeforeLowerId() {
        CandidateSelector.Selection selection = CandidateSelector.select(Arrays.asList(
                candidate(1, 200, GUID_A),
                candidate(99, 100, GUID_B)));

        assertEquals(GUID_B, selection.deviceId);
    }

    @Test
    public void select_ignoresInvalidAndUppercaseNames() {
        CandidateSelector.Selection selection = CandidateSelector.select(Arrays.asList(
                new MediaCandidate(1, 1, "not-a-guid.png"),
                new MediaCandidate(2, 2, GUID_A.toUpperCase() + ".png"),
                candidate(3, 3, GUID_B)));

        assertEquals(GUID_B, selection.deviceId);
        assertEquals(1, selection.candidateCount);
    }

    @Test
    public void select_returnsAbsentWhenNoValidCandidateExists() {
        CandidateSelector.Selection selection = CandidateSelector.select(Collections.singletonList(
                new MediaCandidate(1, 1, "thumbnail.png")));

        assertFalse(selection.isPresent());
        assertNull(selection.deviceId);
        assertEquals(0, selection.candidateCount);
    }

    private static MediaCandidate candidate(long id, long dateAdded, String guid) {
        return new MediaCandidate(id, dateAdded, guid + ".png");
    }
}
