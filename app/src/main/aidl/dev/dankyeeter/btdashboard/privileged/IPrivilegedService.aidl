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

    /** Stops the helper. The app offers this so a stale helper can be replaced. */
    void shutdown(String token);
}
