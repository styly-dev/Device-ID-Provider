package com.styly.deviceid;

/**
 * Outcome of a native Android device ID operation.
 */
public enum DeviceIdStatus {
    SUCCESS,
    NOT_FOUND,
    ACCESS_DENIED,
    UNSUPPORTED_API,
    IO_ERROR
}
