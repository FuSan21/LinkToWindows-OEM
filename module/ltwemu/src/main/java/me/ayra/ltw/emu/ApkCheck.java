package me.ayra.ltw.emu;

import android.content.pm.Signature;
import android.content.res.XmlResourceParser;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Command-line check, run by the module's scripts through app_process:
 *
 * <pre>CLASSPATH=LtwEmu.apk app_process /system/bin me.ayra.ltw.emu.ApkCheck \
 *     &lt;apk&gt; &lt;package&gt; &lt;cert SHA-256&gt; [&lt;cert SHA-256&gt;...]</pre>
 *
 * Exit code 0 only if Android's own APK signature verifier (the code PackageManager uses at
 * install: v1, v2, v3, whichever the APK carries) accepts the file, its signing certificate is one
 * of the given SHA-256 fingerprints (or the APK rotated away from one, v3 lineage), and its package
 * name matches. Anything else, including an error, is a failure: the APK is not used.
 */
public final class ApkCheck {
    private ApkCheck() {
    }

    public static void main(String[] args) {
        int rc;
        try {
            rc = check(args) ? 0 : 1;
        } catch (Throwable t) {
            System.err.println("ApkCheck: " + t);
            rc = 2;
        }
        System.exit(rc);
    }

    private static boolean check(String[] args) throws Exception {
        if (args.length < 3) {
            throw new IllegalArgumentException("usage: <apk> <package> <cert-sha256>...");
        }
        final Set<String> pins = new HashSet<>();
        for (int i = 2; i < args.length; i++) pins.add(args[i].toLowerCase(Locale.ROOT));

        final String pkg = packageName(args[0]);
        if (!args[1].equals(pkg)) {
            System.out.println("package " + pkg + ", expected " + args[1]);
            return false;
        }
        final Object details = verify(args[0]);
        final Signature[] current = (Signature[]) details.getClass()
                .getMethod("getSignatures").invoke(details);
        Signature[] past = null;
        try {
            past = (Signature[]) details.getClass()
                    .getMethod("getPastSigningCertificates").invoke(details);
        } catch (NoSuchMethodException ignored) {
        }
        boolean allPinned = current != null && current.length > 0;
        if (current != null) {
            for (Signature s : current) {
                final String sha = sha256(s);
                System.out.println("signer " + sha);
                allPinned &= pins.contains(sha);
            }
        }
        // v3 key rotation: a single new signer whose lineage contains a pinned certificate.
        boolean rotated = false;
        if (!allPinned && past != null && current != null && current.length == 1) {
            for (Signature s : past) rotated |= pins.contains(sha256(s));
        }
        return allPinned || rotated;
    }

    /** android.util.apk.ApkSignatureVerifier.verify: SigningDetails, or an exception. */
    private static Object verify(String apk) throws Exception {
        final Class<?> verifier = Class.forName("android.util.apk.ApkSignatureVerifier");
        final int anyScheme = 1; // SignatureSchemeVersion.JAR: the strongest scheme present wins
        for (Method m : verifier.getDeclaredMethods()) {
            final Class<?>[] p = m.getParameterTypes();
            if (!m.getName().equals("verify") || !Modifier.isStatic(m.getModifiers())
                    || p.length < 2 || p[p.length - 2] != String.class
                    || p[p.length - 1] != int.class) {
                continue;
            }
            m.setAccessible(true);
            final Object result;
            if (p.length == 3) { // Android 12+: (ParseInput, String, int) -> ParseResult
                result = m.invoke(null, parseInput(), apk, anyScheme);
                final Class<?> r = result.getClass();
                if ((Boolean) r.getMethod("isError").invoke(result)) {
                    throw new SecurityException("signature: "
                            + r.getMethod("getErrorMessage").invoke(result));
                }
                return r.getMethod("getResult").invoke(result);
            }
            if (p.length == 2) { // older: (String, int) -> SigningDetails, throws on failure
                return m.invoke(null, apk, anyScheme);
            }
        }
        throw new NoSuchMethodException("ApkSignatureVerifier.verify");
    }

    private static Object parseInput() throws Exception {
        final Class<?> impl = Class.forName("android.content.pm.parsing.result.ParseTypeImpl");
        for (String factory : new String[] {"forParsingWithoutPlatformCompat", "forDefaultParsing"}) {
            try {
                final Method m = impl.getDeclaredMethod(factory);
                m.setAccessible(true);
                return m.invoke(null);
            } catch (NoSuchMethodException ignored) {
            }
        }
        throw new NoSuchMethodException("ParseTypeImpl factory");
    }

    /** The manifest's package attribute (android.content.res.ApkAssets). */
    private static String packageName(String apk) throws Exception {
        final Class<?> apkAssets = Class.forName("android.content.res.ApkAssets");
        final Object assets = apkAssets.getMethod("loadFromPath", String.class).invoke(null, apk);
        final XmlResourceParser xml = (XmlResourceParser) apkAssets
                .getMethod("openXml", String.class).invoke(assets, "AndroidManifest.xml");
        try {
            int type;
            while ((type = xml.next()) != XmlResourceParser.END_DOCUMENT) {
                if (type == XmlResourceParser.START_TAG && "manifest".equals(xml.getName())) {
                    return xml.getAttributeValue(null, "package");
                }
            }
            return null;
        } finally {
            xml.close();
        }
    }

    private static String sha256(Signature s) throws Exception {
        final byte[] d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray());
        final StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) sb.append(String.format(Locale.ROOT, "%02x", b));
        return sb.toString();
    }
}
