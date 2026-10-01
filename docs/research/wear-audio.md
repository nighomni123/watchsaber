# Play audio on wearables Stay organized with collections Save and categorize content based on your preferences.

- On this page
- Detect audio devices
- Choose preferred device for audio output
 - Let user choose media output device
 - Bluetooth headset
 - Built-in speakers
- Play audio
 - Prevent unintended media playback through built-in speakers

This guide describes how you can use familiar Android APIs to play audio on Wear OS apps.

## Detect audio devices

A Wear OS app must first detect if the wearable device has an appropriate audio output. Wearable devices typically have at least one of the following audio outputs:

- **`AudioDeviceInfo.TYPE_BUILTIN_SPEAKER`**: on devices with a built-in speaker.
- **`AudioDeviceInfo.TYPE_BLUETOOTH_A2DP`**: when a Bluetooth headset is paired and connected.
- **`AudioDeviceInfo.TYPE_BLE_BROADCAST`**: when a Bluetooth Low Energy (BLE) broadcast group device is paired and connected.
- **`AudioDeviceInfo.TYPE_BLE_HEADSET`**: when a BLE headset is paired and connected.
- **`AudioDeviceInfo.TYPE_BLE_SPEAKER`**: when a BLE speaker is paired and connected.

The following example uses the `getDevices()` method with the `FEATURE_AUDIO_OUTPUT` value to check if an audio output type is available.

```
private val audioManager: AudioManager by lazy {
    getSystemService(AUDIO_SERVICE) as AudioManager
}

fun audioOutputAvailable(type: Int): Boolean {
    if (!packageManager.hasSystemFeature(PackageManager.FEATURE_AUDIO_OUTPUT)) {
        return false
    }
    return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type == type }
}AudioActivity.kt
```

You can then use this method to check if an audio output type is available.

```
val hasSpeaker = audioOutputAvailable(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
val hasBluetoothHeadset = audioOutputAvailable(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
val hasBLEBroadcast = audioOutputAvailable(AudioDeviceInfo.TYPE_BLE_BROADCAST)
val hasBLEHeadset = audioOutputAvailable(AudioDeviceInfo.TYPE_BLE_HEADSET)
val hasBLESpeaker = audioOutputAvailable(AudioDeviceInfo.TYPE_BLE_SPEAKER)AudioActivity.kt
```

To provide the best user experience, only play media when Bluetooth headphones or speakers are connected to the watch.

## Choose preferred device for audio output

Depending on your app's use case and the importance of audio to its core experience, choose how users engage with your app's audio output.

### Let user choose media output device

Starting with Wear OS 5, the system provides a UI that lets users choose which device plays media and shows information about the playing media content.

If your app detects that there isn't a Bluetooth headset connected when you want to provide audio playback on devices running Wear OS 5 or higher, offer to take the user directly to the media output switcher. On devices that don't support the media output switcher, invoke the `ACTION_BLUETOOTH_SETTINGS` intent action, which takes the user to the Bluetooth page in system settings.

The `launchOutputSelection()` method, part of the Horologist library on GitHub, demonstrates how to let users choose their media output device.

### Bluetooth headset

Unlike built-in speakers, which are always available if present on the device, a Bluetooth headset can be paired or unpaired while an app runs. If your app requires a headset to continue, register a callback to detect when the user connects and disconnects a Bluetooth headset using `registerAudioDeviceCallback`:

```
val audioDeviceCallback =
    object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            super.onAudioDevicesAdded(addedDevices)
            if (audioOutputAvailable(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) ||
                audioOutputAvailable(AudioDeviceInfo.TYPE_BLE_BROADCAST) ||
                audioOutputAvailable(AudioDeviceInfo.TYPE_BLE_HEADSET) ||
                audioOutputAvailable(AudioDeviceInfo.TYPE_BLE_SPEAKER)
            ) {
                // A Bluetooth or BLE device is connected and available for playback.
            }
        }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            super.onAudioDevicesRemoved(removedDevices)
            if (!(audioOutputAvailable(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)) &&
                !(audioOutputAvailable(AudioDeviceInfo.TYPE_BLE_BROADCAST)) &&
                !(audioOutputAvailable(AudioDeviceInfo.TYPE_BLE_HEADSET)) &&
                !(audioOutputAvailable(AudioDeviceInfo.TYPE_BLE_SPEAKER))
            ) {
                // No Bluetooth or BLE devices are connected anymore.
            }
        }
    }

audioManager.registerAudioDeviceCallback(audioDeviceCallback, /*handler=*/ null)AudioActivity.kt
```

If your app detects that there isn't a Bluetooth headset connected when you want to provide audio output, don't show an error message. Instead, offer to take the user directly to Bluetooth settings to make it easier for them to connect. You can do this by sending an intent with `ACTION_BLUETOOTH_SETTINGS`:

```
fun Context.launchBluetoothSettings(closeOnConnect: Boolean = true) {
    val intent = with(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        putExtra("EXTRA_CONNECTION_ONLY", true)
        if (closeOnConnect) {
            putExtra("EXTRA_CLOSE_ON_CONNECT", true)
        }
        putExtra("android.bluetooth.devicepicker.extra.FILTER_TYPE", FILTER_TYPE_AUDIO)
    }
    startActivity(intent)
}

internal const val FILTER_TYPE_AUDIO = 1BluetoothSettings.kt
```

### Built-in speakers

Most Wear OS devices have built-in speakers. If your app offers a non-media use case that uses sound, consider using speakers to offer an extra dimension of engagement. For example, a speaker-equipped Wear OS device might trigger a clock or timer alarm with an audio notification, and fitness apps might use the speaker to provide exercise instructions.

See the WearSpeakerSample for details.

## Play audio

After you detect and choose a suitable audio output, playing audio on Wear OS is the same as on mobile or other devices. For more information, see MediaPlayer overview. For easier access to advanced features, for example, streaming and downloading media, use ExoPlayer. Follow best practices for audio apps, for example, managing audio focus.

### Prevent unintended media playback through built-in speakers

Media apps can follow this guidance to prevent the app from unintentionally playing media on built-in watch speakers. The guidance varies depending on the player your app uses.

#### ExoPlayer

If your app uses ExoPlayer:

1. Call `setSuppressPlaybackOnUnsuitableOutput(true)` method while building the ExoPlayer instance:

```
val exoPlayer = ExoPlayer.Builder(context)
    .setAudioAttributes(AudioAttributes.DEFAULT, true)
    .setSuppressPlaybackOnUnsuitableOutput(true)
    .build()AudioActivity.kt
```

1. React to the playback suppression event by registering `WearUnsuitableOutputPlaybackSuppressionResolverListener` listener as a listener of the ExoPlayer instance:

```
exoPlayer.addListener(WearUnsuitableOutputPlaybackSuppressionResolverListener(context))AudioActivity.kt
```

#### Horologist Media toolkit

The Horologist MediaToolkit already contains logic to prevent unintended media playback on built-in watch speakers.

#### Other media players

- Ensure that media audio playback starts only when a suitable output device, for example, a headset or external speakers, is connected to the watch. The following list shows suitable output devices for media apps:
 - `AudioDeviceInfo.TYPE_BLUETOOTH_A2DP`
 - `AudioDeviceInfo.TYPE_BLE_BROADCAST`
 - `AudioDeviceInfo.TYPE_BLE_HEADSET`
 - `AudioDeviceInfo.TYPE_BLE_SPEAKER`
- If the `AudioManager` notifies your app that an external audio output device disconnects from the watch, pause playback.
- When the user attempts to initiate media playback but hasn't connected an external audio device, prompt them to connect a device to their watch.

Was this helpful?

Content and code samples on this page are subject to the licenses described in the Content License. Java and OpenJDK are trademarks or registered trademarks of Oracle and/or its affiliates.

Last updated 2026-09-22 UTC.