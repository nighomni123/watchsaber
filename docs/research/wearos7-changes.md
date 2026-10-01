# Test how your app handles behavior changes Stay organized with collections Save and categorize content based on your preferences.

- On this page
- Background audio hardening
- Local network access permissions

Wear OS 7 is based on Android 17 (API level 37). When you prepare your Wear OS app for use on Wear OS 7, handle the system behavior changes that affect all apps in Android 17, as well as the changes for apps that target Android 17.

Unless otherwise specified, the changes affect all apps that run on Wear OS 7 or higher, regardless of target SDK version.

The following underlying platform changes warrant particular focus when targeting Wear OS 7:

## Background audio hardening

To ensure background audio interactions are started intentionally by the user, Android 17 enforces restrictions on audio playback and focus requests. Apps interacting with audio while in the background must run a foreground service that is not of type `SHORT_SERVICE`. For apps targeting Android 17 (API level 37), this foreground service must typically possess **while-in-use (WIU)** capabilities.

For comprehensive details and mitigation strategies, see the guide on Background audio hardening.

## Local network access permissions

Apps targeting Android 17 must declare the `ACCESS_LOCAL_NETWORK` runtime permission to interact with devices on a local area network (LAN). This affects Wear OS apps that communicate directly with smart home devices or casting receivers over Wifi.

For more information, see the Local network permission documentation.

Was this helpful?

Content and code samples on this page are subject to the licenses described in the Content License. Java and OpenJDK are trademarks or registered trademarks of Oracle and/or its affiliates.

Last updated 2026-05-19 UTC.