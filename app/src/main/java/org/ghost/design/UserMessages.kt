package org.ghost.design

/** Present existing device/transport messages without changing the services producing them. */
fun String.userMessage(): String = when (this) {
    "Connect to mesh to find nearby phones." -> "Connect to GHOST to find nearby phones."
    "Connecting to mesh…" -> "Connecting to GHOST…"
    "Mesh is on. Nearby connections are automatic." -> "GHOST connects to nearby phones automatically."
    "Searching for nearby phones. Connections are automatic." -> "Looking for nearby phones. GHOST will connect automatically."
    "Mesh is on. Reconnecting automatically when phones are available." -> "Looking for nearby phones to reconnect."
    "Nearby permissions are missing. Allow them in app settings, then try again." -> "Allow GHOST to find nearby phones in app settings, then try again."
    "Google Play services is unavailable. Nearby cannot start on this device.",
    "Mesh service unavailable. Check Google Play services and try again." -> "GHOST needs Google Play services to connect to nearby phones. Check that it's available on this phone."
    "Nearby startup timed out. Check radios and retry.",
    "Mesh could not start. Check permissions, Bluetooth, Wi-Fi and location services, then retry.",
    "Nearby could not start. Check the signing key, permissions and Google Play services." -> "Couldn't connect to GHOST. Check Bluetooth, Wi-Fi and app permissions, then try again."
    "Receive queue full. The mesh will reconnect automatically." -> "This phone is busy receiving requests. GHOST will reconnect automatically."
    "Unsupported or oversized Nearby packet." -> "Couldn't read a message from a nearby phone. Try reconnecting."
    "Receive queue full. Reconnect to retry saved reports.",
    "Reply queue full. Reconnect to retry saved reports." -> "This phone is busy. Your saved requests are safe. Reconnect to try again."
    "Identity verification timed out. Reconnect to try again." -> "Couldn't finish connecting to a nearby phone. Reconnect to try again."
    "Phone identity verified. Public SOS relay is ready." -> "Connected to a nearby phone. Ready to pass along requests."
    "SOS saved from a nearby phone. Eligible reports are queued for relay." -> "A nearby phone's request was saved here. It can be passed along if it is still recent and within its sharing limit."
    "Signed receipt received. The next phone saved the SOS." -> "A nearby phone saved the request."
    "Receipt pending. The SOS stays saved for retry." -> "Waiting for the nearby phone to confirm saving. Your request is still saved here."
    "Send interrupted. The SOS stays saved for retry." -> "Sending stopped. Your request is still saved here and can be sent again."
    "Automatic retries paused for an SOS. Use Retry queued sends or reconnect." -> "Sending is paused. Tap Try sending again or reconnect to GHOST."
    "Retrying unacknowledged reports. Saved receipts are preserved." -> "Trying to send requests that haven't been confirmed as saved by another phone."
    "Expired packet ignored. Other SOS reports can still relay." -> "An old message was skipped. More recent requests can still be passed along."
    "Local storage unavailable. Relay is waiting.",
    "Local storage could not be opened." -> "Couldn't open saved requests on this phone. Try reopening GHOST."
    "Packet rejected or local storage unavailable. Reports remain saved; reconnect to retry.",
    "Reply interrupted. Reports remain saved; reconnect to retry.",
    "Reply unavailable. Reconnect to retry saved reports.",
    "Relay unavailable. Reports remain saved locally; retry when storage and radios are ready." -> "Couldn't pass along requests. They remain saved on this phone. Reconnect to GHOST to try again."
    "Signing identity unavailable. Reconnect to retry." -> "This phone isn't ready to send requests. Reconnect to GHOST and try again."
    "SOS saved. Queued for the mesh." -> "Request saved. Waiting to share with nearby phones."
    "SOS saved on this phone only." -> "Request saved on this phone only."
    "Draft could not be saved. Try again." -> "Couldn't save your unfinished request. Try again."
    "Could not load the SOS draft." -> "Couldn't open your unfinished request. Try again."
    "SOS could not be saved. Check storage and the signing key, then retry." -> "Couldn't save your request on this phone. Try again."
    "Cloud synchronization and coordination are not implemented yet." -> "Arranging help online isn't available yet."
    "Enter a valid people count.",
    "People count must be between 1 and 1000." -> "Choose between 1 and 1,000 people who need help."
    "Invalid request identifier." -> "Couldn't save this request. Close it and try again."
    "Local report storage is full. Wait for older reports to expire.",
    "Local send queue is full. Wait for older reports to expire." -> "This phone has reached its limit for saved requests. Wait for older requests to become inactive before adding more."
    else -> if (startsWith("Packet rejected: ")) "Couldn't read a message from a nearby phone. Check your phone's date and time, then reconnect." else this
}
