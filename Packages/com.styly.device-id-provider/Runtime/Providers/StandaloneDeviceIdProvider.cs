#if UNITY_2018_4_OR_NEWER
using System;
using System.IO;
using UnityEngine;

namespace Styly.Device
{
    /// <summary>
    /// Windows, macOS and Editor implementation using an application data file.
    /// </summary>
    internal sealed class StandaloneDeviceIdProvider : IDeviceIdProvider
    {
        private const string VendorFolderName = "Styly";
        private const string ProductFolderName = "Device-ID-Provider";
        private const string DeviceIdFileName = "device.id";
        private static readonly object FileLock = new object();

        public string GetDeviceID()
        {
            lock (FileLock)
            {
                var path = ResolveStoragePath();
                var existing = TryReadGuid(path);
                if (!string.IsNullOrEmpty(existing))
                    return existing;

                var newGuid = Guid.NewGuid().ToString("D").ToLowerInvariant();
                PersistGuid(path, newGuid);

                var verified = TryReadGuid(path);
                if (!string.IsNullOrEmpty(verified))
                    return verified;

                throw new IOException("Failed to persist device ID to disk.");
            }
        }

        private static string ResolveStoragePath()
        {
            string baseDirectory = null;
            try
            {
                if (Application.platform == RuntimePlatform.OSXPlayer || Application.platform == RuntimePlatform.OSXEditor)
                {
                    // OSX
                    baseDirectory = Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData);
                }
                else if (Application.platform == RuntimePlatform.WindowsPlayer || Application.platform == RuntimePlatform.WindowsEditor)
                {
                    // Windows
                    baseDirectory = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
                }
            }
            catch (Exception ex)
            {
                Debug.LogWarning($"[DeviceIdProvider] Failed to resolve system application data path: {ex}");
            }

            if (string.IsNullOrEmpty(baseDirectory))
            {
                baseDirectory = Application.persistentDataPath;
            }

            var directory = Path.Combine(baseDirectory, VendorFolderName, ProductFolderName);
            return Path.Combine(directory, DeviceIdFileName);
        }

        private static string TryReadGuid(string path)
        {
            if (string.IsNullOrEmpty(path))
                return null;

            try
            {
                if (!File.Exists(path))
                    return null;

                using (var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read))
                using (var reader = new StreamReader(stream))
                {
                    var raw = reader.ReadToEnd().Trim();
                    if (DeviceIdRegexes.GuidRegex.IsMatch(raw) && Guid.TryParse(raw, out var guid))
                    {
                        return guid.ToString("D").ToLowerInvariant();
                    }
                }

                Debug.LogWarning($"[DeviceIdProvider] Invalid device ID file contents at {path}. Recreating.");
            }
            catch (Exception ex)
            {
                Debug.LogWarning($"[DeviceIdProvider] Failed to read existing device ID from {path}: {ex}");
            }

            return null;
        }

        private static void PersistGuid(string path, string guid)
        {
            if (string.IsNullOrEmpty(path))
                throw new InvalidOperationException("Device ID storage path is not available.");

            var directory = Path.GetDirectoryName(path);
            if (string.IsNullOrEmpty(directory))
                throw new InvalidOperationException("Device ID storage directory is not available.");

            try
            {
                Directory.CreateDirectory(directory);

                var tempPath = path + ".tmp";
                using (var stream = new FileStream(tempPath, FileMode.Create, FileAccess.Write, FileShare.None))
                using (var writer = new StreamWriter(stream))
                {
                    writer.Write(guid);
                }

                if (File.Exists(path))
                {
                    File.Delete(path);
                }

                File.Move(tempPath, path);
            }
            catch (Exception ex)
            {
                Debug.LogError($"[DeviceIdProvider] Failed to persist device ID to {path}: {ex}");
                throw;
            }
        }
    }
}
#endif
