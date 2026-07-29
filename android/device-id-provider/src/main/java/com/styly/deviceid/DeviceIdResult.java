package com.styly.deviceid;

/**
 * Immutable result returned by the native Android device ID provider.
 *
 * <p>The core library never requests runtime permissions or starts an Activity. Callers can
 * inspect {@link #getStatus()} and decide whether to request permission, retry, or continue
 * without a device ID.</p>
 */
public final class DeviceIdResult {
    private final DeviceIdStatus status;
    private final String deviceId;
    private final int candidateCount;
    private final boolean mintAttempted;
    private final String diagnosticMessage;

    private DeviceIdResult(
            DeviceIdStatus status,
            String deviceId,
            int candidateCount,
            boolean mintAttempted,
            String diagnosticMessage) {
        this.status = status;
        this.deviceId = deviceId;
        this.candidateCount = candidateCount;
        this.mintAttempted = mintAttempted;
        this.diagnosticMessage = diagnosticMessage == null ? "" : diagnosticMessage;
    }

    static DeviceIdResult success(String deviceId, int candidateCount, boolean mintAttempted) {
        String diagnostic = candidateCount > 1
                ? "Multiple valid device ID images exist; the deterministic oldest entry was selected."
                : "";
        return new DeviceIdResult(
                DeviceIdStatus.SUCCESS,
                deviceId,
                candidateCount,
                mintAttempted,
                diagnostic);
    }

    static DeviceIdResult failure(
            DeviceIdStatus status,
            boolean mintAttempted,
            String diagnosticMessage) {
        if (status == DeviceIdStatus.SUCCESS) {
            throw new IllegalArgumentException("Use success() for a successful result.");
        }
        return new DeviceIdResult(status, null, 0, mintAttempted, diagnosticMessage);
    }

    public DeviceIdStatus getStatus() {
        return status;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public int getCandidateCount() {
        return candidateCount;
    }

    public boolean wasMintAttempted() {
        return mintAttempted;
    }

    public String getDiagnosticMessage() {
        return diagnosticMessage;
    }
}
