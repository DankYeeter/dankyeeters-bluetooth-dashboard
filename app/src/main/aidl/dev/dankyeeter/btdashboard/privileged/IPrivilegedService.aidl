package dev.dankyeeter.btdashboard.privileged;

/**
 * What the privileged helper offers the app.
 *
 * Every entry point on this interface is surface on a process running with
 * shell privileges, so the set is closed and each member is listed by name in
 * PrivilegedProtocol.PrivilegedOperation together with whether it mutates.
 * PrivilegedProtocolTest reflects over this interface and fails if the two ever
 * disagree — a method added here and forgotten there does not get past the
 * test suite.
 *
 * Every call re-checks two independent things: the token, and that the caller
 * is the app's own uid. See PrivilegedServer.
 *
 * ## Why the codec operations are typed and not shell commands
 *
 * `cmd bluetooth_manager` offers enable/disable/enableBle/disableBle/
 * factoryReset/wait-for-state and nothing else — verified on the device. There
 * is no shell command anywhere in Android that sets an A2DP codec, so it has
 * to be a direct call to BluetoothA2dp.setCodecConfigPreference inside a
 * process that holds BLUETOOTH_PRIVILEGED. `com.android.shell` holds it
 * (granted=true, verified on the device), and the helper runs as that uid.
 *
 * Typed parameters rather than a string command keep the same property the
 * exec whitelist has: there is no argument the caller can vary into something
 * the helper did not intend to offer.
 *
 * ## Read and write are separate methods on purpose
 *
 * codecStatus only observes; setCodecPreference is the first — and so far
 * only — operation in this app that changes the device. Making it a flag on a
 * read would have put the two behind one door.
 *
 * Requests and replies travel as the already-tested PrivilegedProtocol strings
 * rather than as custom Parcelables. That keeps one encoding across the
 * interface and needs no Parcelable to review.
 */
interface IPrivilegedService {

    /** Helper build, so the app can refuse a stale one left over from an older APK. */
    int version();

    /**
     * Runs a whitelisted command.
     *
     * @param token the value the app generated and passed in on the command line
     * @param command the argument vector, matched in full against the whitelist
     * @return an encoded PrivilegedProtocol result or error line
     */
    String exec(String token, in List<String> command);

    /**
     * Reads the negotiated codec for one device through the privileged API.
     *
     * The app can already read a codec via dumpsys, but not the list of codecs
     * the headphone advertises as selectable — that only comes out of
     * getCodecStatus(), which needs BLUETOOTH_PRIVILEGED.
     *
     * @return an encoded CODEC line, or an ERR line
     */
    String codecStatus(String token, String address);

    /**
     * Asks the stack to renegotiate the codec for one device.
     *
     * Zero means "state no preference for this field". Values are plain, not
     * AOSP bitmasks: sampleRateHz is in Hz, bitsPerSample is 16/24/32,
     * channelMode is ChannelModes.MONO/STEREO/DUAL, ldacQuality is AOSP's
     * codecSpecific1 (1000..1003).
     *
     * @return an encoded CODEC line describing what the helper **read back**
     *   afterwards, or an ERR line. A reply is never a claim that the request
     *   was honoured; it is a report of what was observed.
     */
    String setCodecPreference(
        String token,
        String address,
        int codecType,
        int sampleRateHz,
        int bitsPerSample,
        int channelMode,
        long ldacQuality
    );

    /**
     * Reads Android's per-device "HD audio" switch — AOSP's optional codecs.
     *
     * Separate from codecStatus even though both describe one device's audio:
     * this one is the gate in front of the codec negotiation rather than a
     * property of it, and folding it into the codec reply would mean an old
     * helper's shorter CODEC line had to be told apart from a malformed one.
     *
     * @return an encoded HDAUDIO line, or an ERR line
     */
    String optionalCodecs(String token, String address);

    /**
     * Turns HD audio on or off for one device.
     *
     * @param preference 1 enable, 0 disable, -1 hand the decision back to
     *   Android. The three are AOSP's own OPTIONAL_CODECS_PREF_* values, passed
     *   through rather than re-encoded, so there is no second vocabulary to
     *   keep in step with the platform's.
     * @return an encoded HDAUDIO line describing what the helper **read back**,
     *   or an ERR line. As with setCodecPreference, a reply is a report of what
     *   was observed and never a claim that the request was honoured.
     */
    String setOptionalCodecs(String token, String address, int preference);

    /**
     * Turns Bluetooth off and on again.
     *
     * Deliberately its own operation rather than four whitelist entries: it
     * changes the state of the phone, and the whitelist is classified read-only.
     * Taking no arguments is the point — there is nothing for a caller to vary,
     * so this cannot become a general way to drive `cmd bluetooth_manager`.
     *
     * @return an encoded result line, or an ERR line when the adapter did not
     *   reach the expected state. The helper waits for each transition rather
     *   than sleeping and hoping.
     */
    String restartBluetooth(String token);

    /**
     * Grants this app WRITE_SECURE_SETTINGS, once.
     *
     * Takes neither a package nor a permission name on purpose: both are fixed
     * in the helper. A method that accepted them would be a way to grant any
     * development permission to any package, which is a far larger thing than
     * what this is for.
     */
    String grantSecureSettings(String token);

    void shutdown(String token);
}
