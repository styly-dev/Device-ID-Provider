# What is a device ID?

**English** | [日本語](device-id.md)

**A device ID is a unique identifier (UUID) that tells you which device you are working with.**

## What is it used for?

- **Communication, such as NetSync**: Identify devices when exchanging messages or making RPCs (calls that run a procedure on another device).
- **Device management through MDM**: Associate device status, content delivery, and operations with individual devices.
- **Development and operations**: Match IDs in logs and management screens to determine which device an event came from.

Even if a device name or IP address changes, the same stored ID provides a basis for treating it as the same device.

## Core principles

### Keep the same ID for the same device

Generate the ID once, store it, and keep using it. Share it across apps and services that work with the same device, rather than generating a new ID for each app, launch, or connection. Device ID Provider handles this generation, storage, and retrieval.

The ID is not a value embedded in the hardware, such as a manufacturer's serial number. Deleting the stored file or resetting the device can change its ID.

### Use different IDs for different devices

UUIDs make collisions between independently generated IDs practically negligible. However, **copying an ID file to another device gives both devices the same ID.** There is no central check that compares every device to prevent duplicates, so device cloning and backup deployment must also avoid duplicating IDs.

## Where is it stored?

The current Device ID Provider uses the following locations.

| Environment | Location and format |
| --- | --- |
| Android | `Pictures/Device-ID-Provider/<UUID>.png` in shared image storage. A 1×1-pixel PNG image |
| Windows | `%LOCALAPPDATA%\Styly\Device-ID-Provider\device.id`. A text file containing the UUID |
| macOS | `~/Library/Application Support/Styly/Device-ID-Provider/device.id`. A text file containing the UUID |
| Unity Editor (Windows and macOS) | `<hash-of-project-path>/device.id` inside the OS-specific `Device-ID-Provider` folder above |

### Why Android uses an image

To let multiple apps access the same ID within Android's storage and permission constraints, the provider uses MediaStore, Android's shared media system. As long as the image remains, an app can reuse the same ID after reinstallation.

The filename contains **the actual UUID**, for example `123e4567-e89b-42d3-a456-426614174000.png`; it is not literally `UUID.png`. The ID is recorded in the filename, not in the image's visual content.

### ID scope on PCs and in the Unity Editor

Regular Windows and macOS apps store the ID **per OS user**. Different OS users on the same PC have different IDs.

The Unity Editor separates IDs **by project path** so that different test environments can be distinguished. Projects or clones in different locations on the same PC have different IDs. Moving a project can therefore change its ID.

If the OS storage location cannot be resolved, the provider uses Unity's `Application.persistentDataPath` as the base directory. In this case, the scope of ID sharing may differ from the normal location.

## Operational guidelines

- **Do not delete ID files or change their names or contents.**
- **Do not copy ID files to another device.** When cloning a device, make sure the clone does not inherit the source device's ID.
- **If ID retrieval fails, check permissions and storage availability.** Generating a replacement ID can cause the same device to be treated as a different device.
- **Check the actual ID after a reset or device replacement.** If it has changed, review the MDM registration and communication target mappings as well.

---

See the [README](../README.md) for installation instructions and API details.
