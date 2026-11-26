package com.scansafe;

import com.github.sarxos.webcam.Webcam;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.net.URI;
import java.net.InetAddress;
import java.net.URLEncoder;
import java.net.URLDecoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.prefs.Preferences;
import java.util.regex.*;

public class Main extends JFrame {

    private static final Color BG = new Color(14, 20, 35);
    private static final Color PANEL = new Color(25, 34, 54);
    private static final Color ACCENT = new Color(64, 190, 160);
    private static final Color TEXT = new Color(235, 240, 248);

    private static final Color SAFE = new Color(45, 190, 125);
    private static final Color WARNING = new Color(235, 170, 55);
    private static final Color DANGER = new Color(225, 75, 75);

    private final JTextArea result = new JTextArea(7, 35);
    private final JTextField urlField = new JTextField();

    private final DefaultListModel<String> historyModel =
            new DefaultListModel<>();

    private final JLabel status =
            new JLabel("Ready — upload a QR image or use your camera");

    private final JLabel preview =
            new JLabel("Camera preview", SwingConstants.CENTER);

    private final JLabel verdictLabel =
            new JLabel("WAITING FOR SCAN", SwingConstants.CENTER);

    private final JButton openLinkButton = button("Open Link");

    private final ExecutorService worker =
            Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "qr-shield-worker");
                t.setDaemon(true);
                return t;
            });

    private volatile boolean cameraRunning;
    private volatile Webcam webcam;

    private URI currentUrl;
    private String currentVerdict = "UNKNOWN";

    private final Preferences prefs =
            Preferences.userRoot().node("qr-shield");

    private record Assessment(
            String verdict,
            int score,
            URI url,
            List<String> reasons,
            String advice
    ) {}

    public Main() {

        super("QR Shield | QR Code Safety Checker");

        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(900, 650));
        setSize(1100, 760);
        setLocationRelativeTo(null);

        getContentPane().setBackground(BG);
        setLayout(new BorderLayout(16, 16));

        JLabel heading =
                new JLabel("  QR SHIELD   |   QR CODE SAFETY CHECKER");

        heading.setForeground(ACCENT);
        heading.setFont(new Font("SansSerif", Font.BOLD, 22));
        heading.setBorder(new EmptyBorder(18, 18, 8, 18));

        add(heading, BorderLayout.NORTH);

        JPanel content = new JPanel(new GridLayout(1, 2, 16, 0));
        content.setOpaque(false);
        content.setBorder(new EmptyBorder(0, 18, 10, 18));

        JPanel left = panel();
        left.setLayout(new BorderLayout(10, 10));

        preview.setForeground(new Color(160, 175, 195));
        preview.setBackground(Color.BLACK);
        preview.setOpaque(true);
        preview.setPreferredSize(new Dimension(440, 280));

        left.add(preview, BorderLayout.CENTER);

        JPanel actions = new JPanel(new GridLayout(2, 2, 8, 8));
        actions.setOpaque(false);

        JButton upload = button("Upload QR Image");
        JButton start = button("Start Camera");
        JButton stop = button("Stop Camera");
        JButton clear = button("Clear Result");

        actions.add(upload);
        actions.add(start);
        actions.add(stop);
        actions.add(clear);

        left.add(actions, BorderLayout.SOUTH);

        JPanel right = panel();
        right.setLayout(new BorderLayout(10, 10));

        JLabel rtitle = new JLabel("SCAN RESULT");
        rtitle.setForeground(ACCENT);
        rtitle.setFont(new Font("SansSerif", Font.BOLD, 15));

        right.add(rtitle, BorderLayout.NORTH);

        JPanel resultArea = new JPanel(new BorderLayout(8, 8));
        resultArea.setOpaque(false);

        verdictLabel.setOpaque(true);
        verdictLabel.setBackground(new Color(55, 65, 80));
        verdictLabel.setForeground(Color.WHITE);
        verdictLabel.setFont(new Font("SansSerif", Font.BOLD, 16));
        verdictLabel.setBorder(new EmptyBorder(10, 8, 10, 8));

        resultArea.add(verdictLabel, BorderLayout.NORTH);

        result.setEditable(false);
        result.setLineWrap(true);
        result.setWrapStyleWord(true);
        result.setBackground(new Color(12, 18, 30));
        result.setForeground(TEXT);
        result.setFont(new Font("Monospaced", Font.PLAIN, 13));
        result.setBorder(new EmptyBorder(12, 12, 12, 12));

        resultArea.add(new JScrollPane(result), BorderLayout.CENTER);

        JPanel resultActions = new JPanel(new BorderLayout(8, 8));
        resultActions.setOpaque(false);

        openLinkButton.setEnabled(false);
        resultActions.add(openLinkButton, BorderLayout.WEST);

        JPanel check = new JPanel(new BorderLayout(6, 6));
        check.setOpaque(false);

        JLabel l = new JLabel("Manual URL safety check");
        l.setForeground(TEXT);

        check.add(l, BorderLayout.NORTH);

        urlField.setBackground(new Color(12, 18, 30));
        urlField.setForeground(TEXT);
        urlField.setCaretColor(TEXT);

        check.add(urlField, BorderLayout.CENTER);

        JButton verify = button("Check URL");
        check.add(verify, BorderLayout.EAST);

        resultActions.add(check, BorderLayout.SOUTH);
        resultArea.add(resultActions, BorderLayout.SOUTH);

        right.add(resultArea, BorderLayout.CENTER);

        content.add(left);
        content.add(right);

        add(content, BorderLayout.CENTER);

        JPanel bottom = panel();
        bottom.setLayout(new BorderLayout(8, 8));

        bottom.add(status, BorderLayout.NORTH);
        status.setForeground(new Color(180, 195, 215));

        JList<String> history = new JList<>(historyModel);
        history.setBackground(new Color(12, 18, 30));
        history.setForeground(TEXT);
        history.setVisibleRowCount(3);

        bottom.add(new JScrollPane(history), BorderLayout.CENTER);

        bottom.setPreferredSize(new Dimension(100, 135));
        bottom.setBorder(new EmptyBorder(8, 18, 14, 18));

        add(bottom, BorderLayout.SOUTH);

        upload.addActionListener(e -> chooseImage());
        start.addActionListener(e -> startCamera());
        stop.addActionListener(e -> stopCamera());
        clear.addActionListener(e -> clearResult());

        verify.addActionListener(e -> checkUrl(urlField.getText()));

        openLinkButton.addActionListener(e -> openCurrentLink());

        history.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                String v = history.getSelectedValue();

                if (v != null) {
                    int first = v.indexOf(" | ");
                    int second = first < 0 ? -1 :
                            v.indexOf(" | ", first + 3);

                    if (second >= 0) {
                        urlField.setText(v.substring(second + 3));
                    } else if (first >= 0) {
                        urlField.setText(v.substring(first + 3));
                    }
                }
            }
        });

        addWindowListener(new java.awt.event.WindowAdapter() {
            public void windowClosing(java.awt.event.WindowEvent e) {
                stopCamera();
                worker.shutdownNow();
            }
        });

        loadHistory();
    }

    private JPanel panel() {
        JPanel p = new JPanel();
        p.setBackground(PANEL);
        p.setBorder(new EmptyBorder(14, 14, 14, 14));
        return p;
    }

    private static JButton button(String s) {
        JButton b = new JButton(s);
        b.setFocusPainted(false);
        b.setBackground(ACCENT);
        b.setForeground(new Color(8, 25, 30));
        b.setFont(new Font("SansSerif", Font.BOLD, 12));
        return b;
    }

    // ---------------------------------------------------------
    // QR IMAGE UPLOAD
    // ---------------------------------------------------------

    private void chooseImage() {

        JFileChooser c = new JFileChooser();
        c.setDialogTitle("Choose a QR code image");

        if (c.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {

            File f = c.getSelectedFile();
            status.setText("Reading QR image...");

            worker.submit(() -> {
                try {
                    BufferedImage im = ImageIO.read(f);

                    if (im == null) {
                        throw new Exception("Unsupported image");
                    }

                    String decoded = decode(im);

                    SwingUtilities.invokeLater(() ->
                            showDecoded(decoded));

                } catch (Exception ex) {
                    showError("Could not read a QR code from this image.");
                }
            });
        }
    }

    // ---------------------------------------------------------
    // QR DECODING
    // ---------------------------------------------------------

    private static String decode(BufferedImage image)
            throws NotFoundException {

        LuminanceSource src =
                new BufferedImageLuminanceSource(image);

        BinaryBitmap bitmap =
                new BinaryBitmap(new HybridBinarizer(src));

        Map<DecodeHintType, Object> hints =
                new EnumMap<>(DecodeHintType.class);

        hints.put(DecodeHintType.TRY_HARDER, true);
        hints.put(DecodeHintType.ALSO_INVERTED, true);

        hints.put(
                DecodeHintType.POSSIBLE_FORMATS,
                List.of(BarcodeFormat.QR_CODE)
        );

        return new MultiFormatReader()
                .decode(bitmap, hints)
                .getText();
    }

    // ---------------------------------------------------------
    // CAMERA
    // ---------------------------------------------------------

    private void startCamera() {

        if (cameraRunning) return;

        cameraRunning = true;
        status.setText("Opening camera...");

        worker.submit(() -> {

            try {

                webcam = Webcam.getDefault();

                if (webcam == null) {
                    throw new Exception("No webcam was found.");
                }

                webcam.setViewSize(new Dimension(640, 480));

                if (!webcam.open()) {
                    throw new Exception("Could not open webcam.");
                }

                SwingUtilities.invokeLater(() ->
                        status.setText(
                                "Camera running — hold a QR code in view"
                        ));

                while (cameraRunning) {

                    BufferedImage frame = webcam.getImage();

                    if (frame == null) continue;

                    BufferedImage scaled = new BufferedImage(
                            440, 280, BufferedImage.TYPE_INT_RGB
                    );

                    Graphics2D g = scaled.createGraphics();
                    g.drawImage(frame, 0, 0, 440, 280, null);
                    g.dispose();

                    SwingUtilities.invokeLater(() ->
                            preview.setIcon(new ImageIcon(scaled)));

                    try {
                        String value = decode(frame);

                        cameraRunning = false;

                        SwingUtilities.invokeLater(() ->
                                showDecoded(value));

                    } catch (NotFoundException ignored) {
                    }

                    Thread.sleep(120);
                }

            } catch (Exception ex) {
                showError("Camera error: " + ex.getMessage());

            } finally {

                if (webcam != null) {
                    try {
                        webcam.close();
                    } catch (Exception ignored) {
                    }

                    webcam = null;
                }

                cameraRunning = false;
            }
        });
    }

    private void stopCamera() {

        cameraRunning = false;

        if (webcam != null) {
            try {
                webcam.close();
            } catch (Exception ignored) {
            }
        }

        preview.setIcon(null);
        preview.setText("Camera preview");
        status.setText("Camera stopped");
    }

    // ---------------------------------------------------------
    // CLEAR RESULT
    // ---------------------------------------------------------

    private void clearResult() {

        result.setText("");
        urlField.setText("");

        currentUrl = null;
        currentVerdict = "UNKNOWN";

        verdictLabel.setText("WAITING FOR SCAN");
        verdictLabel.setBackground(new Color(55, 65, 80));

        openLinkButton.setEnabled(false);
    }

    // ---------------------------------------------------------
    // QR TYPE DETECTION
    // ---------------------------------------------------------

    private static String detectQrType(String value) {

        String text = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);

        if (text.startsWith("upi://")) return "UPI PAYMENT";
        if (text.startsWith("wifi:")) return "WIFI NETWORK";
        if (text.startsWith("market://") || text.startsWith("intent://") ||
                isAppStoreUrl(value) || getInstallerExtension(value) != null) {
            return "APP";
        }

        if (text.startsWith("https://") || text.startsWith("http://")) {
            return parseUrl(value) == null ? "INVALID URL" : "WEBSITE";
        }

        // Catch malformed URL-like strings such as "ht!tp://bad url".
        if (text.startsWith("ht") && text.contains("://")) return "INVALID URL";

        if (text.startsWith("mailto:")) return "EMAIL";
        if (text.startsWith("tel:")) return "PHONE NUMBER";

        if (text.startsWith("sms:") || text.startsWith("smsto:")) return "SMS";
        if (text.startsWith("geo:")) return "LOCATION";

        return "TEXT";
    }

    private static String getContentHeading(String qrType) {

        return switch (qrType) {
            case "WEBSITE" -> "WEBSITE URL";
            case "INVALID URL" -> "INVALID URL CONTENT";
            case "APP" -> "APPLICATION LINK OR INSTALLER";
            case "UPI PAYMENT" -> "UPI QR CONTENT";
            case "WIFI NETWORK" -> "WIFI QR CONTENT";
            case "EMAIL" -> "EMAIL CONTENT";
            case "PHONE NUMBER" -> "PHONE NUMBER CONTENT";
            case "SMS" -> "SMS CONTENT";
            case "LOCATION" -> "LOCATION CONTENT";
            default -> "DECODED TEXT";
        };
    }

    private static boolean isAppStoreUrl(String value) {

        try {
            URI uri = URI.create(value.trim());
            String host = uri.getHost();
            String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);

            if (host == null) return false;
            host = host.toLowerCase(Locale.ROOT);

            return (host.equals("play.google.com") && path.startsWith("/store/apps/")) ||
                    (host.equals("apps.apple.com") && path.contains("/app/"));

        } catch (Exception ex) {
            return false;
        }
    }

    private static String getInstallerExtension(String value) {

        if (value == null || value.isBlank()) return null;

        String path = value.trim();
        try {
            URI uri = URI.create(path);
            if (uri.getPath() != null) path = uri.getPath();
        } catch (Exception ignored) {
            path = path.split("[?#]", 2)[0];
        }

        Matcher matcher = Pattern.compile(
                "(?i)\\.((?:apk|exe|msi|dmg|deb|rpm|appimage|jar|bat|cmd|scr))$"
        ).matcher(path);

        return matcher.find() ? matcher.group(1).toLowerCase(Locale.ROOT) : null;
    }

    private static Map<String, String> parseAppFields(String value) {

        Map<String, String> fields = new LinkedHashMap<>();
        String lower = value.toLowerCase(Locale.ROOT);

        if (lower.startsWith("market://")) {
            fields.put("source", "Android app market link");
        } else if (lower.startsWith("intent://")) {
            fields.put("source", "Android intent link");
        } else if (lower.contains("play.google.com")) {
            fields.put("source", "Google Play listing link");
        } else if (lower.contains("apps.apple.com")) {
            fields.put("source", "Apple App Store listing link");
        } else {
            fields.put("source", "Application or installer link");
        }

        Matcher packageMatcher = Pattern.compile(
                "(?i)(?:[?&;]id=|[;]package=)([a-z0-9_]+(?:\\.[a-z0-9_]+)+)"
        ).matcher(value);
        if (packageMatcher.find()) {
            fields.put("package", packageMatcher.group(1));
        }

        Matcher appStoreId = Pattern.compile("(?i)/id(\\d+)(?:[/?#]|$)").matcher(value);
        if (appStoreId.find()) {
            fields.put("appStoreId", appStoreId.group(1));
        }

        String extension = getInstallerExtension(value);
        if (extension != null) fields.put("installer", extension.toUpperCase(Locale.ROOT));

        return fields;
    }

    // ---------------------------------------------------------
    // QUERY STRING HELPERS
    // ---------------------------------------------------------

    private static String decodeQueryPart(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return value;
        }
    }

    private static Map<String, String> parseQuery(String query) {

        Map<String, String> fields = new LinkedHashMap<>();

        if (query == null || query.isBlank()) return fields;

        for (String item : query.split("&")) {

            int equals = item.indexOf('=');

            if (equals < 0) continue;

            String key = decodeQueryPart(
                    item.substring(0, equals)
            ).trim().toLowerCase(Locale.ROOT);

            String value = decodeQueryPart(
                    item.substring(equals + 1)
            ).trim();

            fields.put(key, value);
        }

        return fields;
    }

    // ---------------------------------------------------------
    // UPI DETAILS PARSER
    // ---------------------------------------------------------

    private static Map<String, String> parseUpiFields(String text) {

        int q = text.indexOf('?');

        if (q < 0 || q == text.length() - 1) {
            return new LinkedHashMap<>();
        }

        return parseQuery(text.substring(q + 1));
    }

    // ---------------------------------------------------------
    // WIFI DETAILS PARSER - CORRECTED
    // ---------------------------------------------------------

    private static Map<String, String> parseWifiFields(String text) {

        Map<String, String> fields = new LinkedHashMap<>();

        if (text == null) return fields;

        text = text.trim().replaceFirst("^\uFEFF", "");

        if (!text.regionMatches(true, 0, "WIFI:", 0, 5)) {
            return fields;
        }

        int i = 5;

        while (i < text.length()) {

            while (i < text.length() && text.charAt(i) == ';') {
                i++;
            }

            if (i >= text.length()) break;

            int keyStart = i;

            while (i < text.length()
                    && text.charAt(i) != ':'
                    && text.charAt(i) != ';') {
                i++;
            }

            if (i >= text.length() || text.charAt(i) != ':') {
                break;
            }

            String key = text.substring(keyStart, i)
                    .trim()
                    .toUpperCase(Locale.ROOT);

            i++;

            StringBuilder value = new StringBuilder();

            while (i < text.length()) {

                char ch = text.charAt(i);

                if (ch == '\\' && i + 1 < text.length()) {
                    value.append(text.charAt(i + 1));
                    i += 2;
                    continue;
                }

                if (ch == ';') {
                    i++;
                    break;
                }

                value.append(ch);
                i++;
            }

            if (!key.isBlank()) {
                fields.put(key, value.toString());
            }
        }

        return fields;
    }

    // ---------------------------------------------------------
    // EMAIL DETAILS PARSER - CORRECTED
    // ---------------------------------------------------------

    private static Map<String, String> parseEmailFields(String text) {

        Map<String, String> fields = new LinkedHashMap<>();

        if (text == null) return fields;

        text = text.trim().replaceFirst("^\uFEFF", "");

        if (!text.regionMatches(true, 0, "mailto:", 0, 7)) {
            return fields;
        }

        String content = text.substring(7);

        int question = content.indexOf('?');

        String addressPart = question >= 0
                ? content.substring(0, question)
                : content;

        String queryPart = question >= 0
                ? content.substring(question + 1)
                : "";

        String address = decodeQueryPart(
                addressPart.replace("+", "%2B")
        ).trim();

        if (!address.isBlank()) {
            fields.put("to", address);
        }

        Map<String, String> queryFields = parseQuery(queryPart);

        for (Map.Entry<String, String> entry : queryFields.entrySet()) {
            String key = entry.getKey().toLowerCase(Locale.ROOT);
            fields.put(key, entry.getValue());
        }

        return fields;
    }

    // ---------------------------------------------------------
    // SMS DETAILS PARSER - CORRECTED
    // ---------------------------------------------------------

    private static Map<String, String> parseSmsFields(String text) {

        Map<String, String> fields = new LinkedHashMap<>();

        if (text == null) return fields;

        text = text.trim().replaceFirst("^\uFEFF", "");

        String content;

        boolean isSmsto = text.regionMatches(
                true, 0, "SMSTO:", 0, 6
        );

        boolean isSms = text.regionMatches(
                true, 0, "SMS:", 0, 4
        );

        if (isSmsto) {
            content = text.substring(6);

        } else if (isSms) {
            content = text.substring(4);

        } else {
            return fields;
        }

        int question = content.indexOf('?');

        String numberPart = question >= 0
                ? content.substring(0, question)
                : content;

        String queryPart = question >= 0
                ? content.substring(question + 1)
                : "";

        String number;
        String message = "";

        int messageSeparator = numberPart.indexOf(':');

        if (messageSeparator >= 0) {

            number = numberPart.substring(0, messageSeparator).trim();

            message = numberPart.substring(messageSeparator + 1).trim();

        } else {

            number = numberPart.trim();
        }

        if (!number.isBlank()) {
            fields.put("number", number);
        }

        Map<String, String> queryFields = parseQuery(queryPart);

        fields.putAll(queryFields);

        if (queryFields.containsKey("body")) {
            message = queryFields.get("body");
        }

        if (!message.isBlank()) {
            fields.put("body", message);
        }

        return fields;
    }

    // ---------------------------------------------------------
    // RISK WORD DETECTION
    // ---------------------------------------------------------

    private static Set<String> findRiskWords(String text) {

        Set<String> found = new LinkedHashSet<>();

        String lower = text.toLowerCase(Locale.ROOT);

        String[] words = {
                "urgent", "immediately", "verify", "verification", "password",
                "otp", "bank", "blocked", "locked", "suspended", "account",
                "refund", "reward", "winner", "prize", "claim", "pay",
                "free gift", "limited time", "act now", "kyc",
                "send money", "payment required", "click here", "click",
                "confirm your identity", "security alert", "update", "restore",
                "install", "remote access", "access code", "cracked", "modded",
                "premium unlocked", "download"
        };

        for (String word : words) {
            if (lower.contains(word)) {
                found.add(word);
            }
        }

        return found;
    }

    private static boolean containsWebLink(String text) {

        return Pattern.compile(
                "(?i)https?://|www\\."
        ).matcher(text).find();
    }

    private static boolean containsCredentialRequest(String text) {

        String lower = text.toLowerCase(Locale.ROOT);

        return lower.matches("(?s).*\\b(enter|send|share|provide|submit)\\b.*" +
                "\\b(password|otp|pin|passcode|cvv|card number)\\b.*");
    }

    // ---------------------------------------------------------
    // NON-WEBSITE ASSESSMENTS
    // ---------------------------------------------------------

    private Assessment assessNonWebsite(String text, String qrType) {

        List<String> reasons = new ArrayList<>();
        int score = 0;

        switch (qrType) {

            case "WIFI NETWORK" -> {

                Map<String, String> fields = parseWifiFields(text);

                String ssid = fields.getOrDefault("S", "");
                String security = fields.getOrDefault("T", "");
                String password = fields.getOrDefault("P", "");

                if (ssid.isBlank()) {
                    score += 1;
                    reasons.add("The Wi-Fi network name is missing.");
                }

                if (security.isBlank()) {
                    reasons.add(
                            "The QR code does not specify the Wi-Fi security type. Confirm the network settings with its owner."
                    );

                } else if (security.equalsIgnoreCase("nopass")) {
                    reasons.add(
                            "This is an open Wi-Fi network with no password. Open Wi-Fi is not automatically malicious, " +
                            "but other people on the network may be able to observe unencrypted activity."
                    );

                } else if (security.equalsIgnoreCase("WEP")) {

                    score += 3;
                    reasons.add(
                            "WEP is an outdated and weak Wi-Fi security method."
                    );
                }

                if (!password.isBlank() && password.length() < 8) {
                    score += 2;
                    reasons.add(
                            "The Wi-Fi password is shorter than 8 characters and may be weak."
                    );
                }

                Set<String> words = findRiskWords(ssid);

                if (!words.isEmpty()) {
                    score += 2;
                    reasons.add(
                            "The network name contains potentially misleading wording: " +
                            String.join(", ", words) + "."
                    );
                }

                if (reasons.isEmpty()) {
                    reasons.add(
                            "No obvious configuration warning was found. " +
                            "QR Shield cannot verify who operates this Wi-Fi network."
                    );
                }
            }

            case "EMAIL" -> {

                Map<String, String> fields = parseEmailFields(text);

                String recipient = fields.getOrDefault("to", "");
                String subject = fields.getOrDefault("subject", "");
                String body = fields.getOrDefault("body", "");

                String message = subject + " " + body;

                if (recipient.isBlank() ||
                        !recipient.contains("@") ||
                        !recipient.contains(".")) {

                    score += 2;
                    reasons.add(
                            "The email recipient is missing or does not look like a complete email address."
                    );
                }

                Set<String> words = findRiskWords(message);

                if (!words.isEmpty()) {
                    score += Math.min(4, words.size() * 2);
                    reasons.add(
                            "The email contains potentially suspicious wording: " +
                            String.join(", ", words) + "."
                    );
                }

                if (containsCredentialRequest(message)) {
                    score += 4;
                    reasons.add(
                            "The message appears to request passwords, OTPs, PINs or other sensitive credentials."
                    );
                }

                if (containsWebLink(message)) {
                    score += 2;
                    reasons.add(
                            "The email contains a web link. Verify its destination before opening it."
                    );
                }

                if (reasons.isEmpty()) {
                    reasons.add(
                            "No obvious suspicious wording was found. " +
                            "QR Shield cannot verify the sender or recipient's identity."
                    );
                }
            }

            case "SMS" -> {

                Map<String, String> fields = parseSmsFields(text);

                String number = fields.getOrDefault("number", "");
                String body = fields.getOrDefault("body", "");

                if (number.isBlank()) {
                    score++;
                    reasons.add("The SMS recipient number is missing.");
                }

                Set<String> words = findRiskWords(body);

                if (!words.isEmpty()) {
                    score += Math.min(4, words.size() * 2);
                    reasons.add(
                            "The message contains potentially suspicious wording: " +
                            String.join(", ", words) + "."
                    );
                }

                if (containsCredentialRequest(body)) {
                    score += 4;
                    reasons.add(
                            "The message appears to request passwords, OTPs, PINs or other sensitive information."
                    );
                }

                if (containsWebLink(body)) {
                    score += 2;
                    reasons.add(
                            "The SMS contains a web link. Verify the destination independently."
                    );
                }

                if (reasons.isEmpty()) {
                    reasons.add(
                            "No obvious scam-related wording was found. " +
                            "QR Shield cannot verify the recipient or sender."
                    );
                }
            }

            case "PHONE NUMBER" -> {

                String number = text.substring(4).trim();

                String digits = number.replaceAll("[^0-9]", "");

                if (digits.length() < 7 || digits.length() > 15) {
                    score += 2;
                    reasons.add(
                            "The phone number has an unusual length. Check it before calling."
                    );
                }

                if (number.matches(".*[A-Za-z].*")) {
                    score += 2;
                    reasons.add(
                            "The phone number contains letters or unexpected characters."
                    );
                }

                if (number.startsWith("+") &&
                        !number.matches("^\\+[0-9() .-]+$")) {
                    score += 2;
                    reasons.add(
                            "The international phone number format looks unusual."
                    );
                }

                if (reasons.isEmpty()) {
                    reasons.add(
                            "The phone number format has no obvious warning signs. " +
                            "QR Shield cannot check the number owner's identity or reputation."
                    );
                }
            }

            case "LOCATION" -> {

                String coordinates = text.substring(4);

                String pair = coordinates.split("[?;]", 2)[0];
                String[] parts = pair.split(",", 2);

                try {

                    if (parts.length != 2) {
                        throw new IllegalArgumentException("A latitude and longitude pair is required.");
                    }

                    double latitude = Double.parseDouble(parts[0]);
                    double longitude = Double.parseDouble(parts[1]);

                    if (!Double.isFinite(latitude) || latitude < -90 || latitude > 90) {
                        score += 3;
                        reasons.add("The latitude is invalid. It must be between -90 and 90 degrees.");
                    }

                    if (!Double.isFinite(longitude) || longitude < -180 || longitude > 180) {
                        score += 3;
                        reasons.add("The longitude is invalid. It must be between -180 and 180 degrees.");
                    }

                    if (score == 0) {
                        reasons.add(
                                "The latitude and longitude are within valid coordinate ranges. " +
                                "This does not verify whether the destination is trustworthy."
                        );
                    }

                } catch (Exception ex) {

                    score += 3;
                    reasons.add(
                            "The location coordinates are missing, malformed or could not be read as numbers. " +
                            "A valid geo QR code needs numeric latitude and longitude values."
                    );
                }
            }

            default -> {

                Set<String> words = findRiskWords(text);

                if (!words.isEmpty()) {
                    score += Math.min(4, words.size() * 2);
                    reasons.add(
                            "The text contains potentially suspicious wording: " +
                            String.join(", ", words) + "."
                    );
                }

                if (containsCredentialRequest(text)) {
                    score += 4;
                    reasons.add(
                            "The text appears to request passwords, OTPs, PINs or other sensitive information."
                    );
                }

                if (containsWebLink(text)) {
                    score += 2;
                    reasons.add(
                            "The text contains a web link. Check the destination before opening it."
                    );
                }

                if (reasons.isEmpty()) {
                    reasons.add(
                            "No obvious suspicious wording was detected. " +
                            "This does not guarantee that the text or its source is trustworthy."
                    );
                }
            }
        }

        boolean suspicious = score >= 3;

        String verdict = suspicious ? "SUSPICIOUS" : switch (qrType) {
            case "WIFI NETWORK" -> "REVIEW NETWORK";
            case "EMAIL" -> "REVIEW EMAIL";
            case "SMS" -> "REVIEW MESSAGE";
            case "PHONE NUMBER" -> "REVIEW NUMBER";
            case "LOCATION" -> "REVIEW LOCATION";
            default -> "REVIEW TEXT";
        };

        String advice;

        if (suspicious) {

            if (qrType.equals("WIFI NETWORK")) {

                Map<String, String> wifiFields = parseWifiFields(text);
                String wifiSecurity = wifiFields.getOrDefault("T", "");

                if (wifiSecurity.equalsIgnoreCase("WEP")) {
                    advice =
                            "WARNING: WEP is an outdated and weak Wi-Fi security method. " +
                            "Do not use it for sensitive activity. Ask the network owner to upgrade to WPA2 or WPA3.";
                } else {
                    advice =
                            "WARNING: This Wi-Fi QR code has a configuration concern. " +
                            "Verify the network owner and settings before connecting.";
                }

            } else {

                advice =
                        "WARNING: QR Shield detected potentially suspicious indicators. " +
                        "Do not follow instructions, open links, send payments or share personal information " +
                        "unless you independently verify the source.";
            }

        } else {

            advice = switch (qrType) {

                case "WIFI NETWORK" -> {
                    Map<String, String> wifiFields = parseWifiFields(text);
                    String wifiSecurity = wifiFields.getOrDefault("T", "");
                    if (wifiSecurity.equalsIgnoreCase("nopass")) {
                        yield "REVIEW NETWORK: This is an open Wi-Fi network, which is not automatically malicious. " +
                                "Confirm that it is the intended network and avoid sensitive activity unless you trust it.";
                    }
                    yield "REVIEW NETWORK: Confirm that this is the correct network and that you trust its owner. " +
                            "Use WPA2 or WPA3 security for a protected network.";
                }

                case "EMAIL" ->
                        "REVIEW EMAIL: Check the recipient, subject and message before sending. " +
                        "Never email passwords, OTPs, payment PINs or banking credentials.";

                case "SMS" ->
                        "REVIEW MESSAGE: Confirm the phone number and message before sending. " +
                        "Never send OTPs, passwords or banking information.";

                case "PHONE NUMBER" ->
                        "REVIEW NUMBER: Confirm the number belongs to the person or organisation you intend to call. " +
                        "Do not share sensitive information with unknown callers.";

                case "LOCATION" ->
                        "REVIEW LOCATION: Check the destination on a trusted map before travelling there.";

                default ->
                        "REVIEW TEXT: Read the decoded content carefully. " +
                        "Do not follow unexpected instructions or share sensitive information.";
            };
        }

        return new Assessment(
                verdict,
                score,
                null,
                reasons,
                advice
        );
    }

    // ---------------------------------------------------------
    // APPLICATION / INSTALLER ASSESSMENT
    // ---------------------------------------------------------

    private Assessment assessApp(String text, URI webUri) {

        List<String> reasons = new ArrayList<>();
        int score = 0;
        Map<String, String> fields = parseAppFields(text);
        String installer = fields.getOrDefault("installer", "");
        String lower = text.toLowerCase(Locale.ROOT);

        if (!installer.isBlank()) {
            score += 4;
            reasons.add(
                    "The QR code points to an application installer file (" + installer + "). " +
                    "Installing files from an unknown source can expose your device to malware."
            );
        }

        Matcher cracked = Pattern.compile(
                "(?i)\\b(cracked|crack|modded|mod[- ]?apk|premium[- ]?unlocked|patched[- ]?app)\\b"
        ).matcher(lower);
        if (cracked.find()) {
            score += 3;
            reasons.add(
                    "The link contains wording associated with cracked, modified or unlocked software. " +
                    "Such downloads may be tampered with or unsafe."
            );
        }

        boolean knownStore = fields.getOrDefault("source", "").contains("Google Play") ||
                fields.getOrDefault("source", "").contains("Apple App Store");

        if (knownStore) {
            reasons.add(
                    "This appears to use a Google Play or Apple App Store listing URL. " +
                    "Check the app developer, reviews and requested permissions before installing."
            );
        } else {
            reasons.add(
                    "QR Shield cannot verify the app publisher or whether this source is trustworthy. " +
                    "Prefer the developer's verified website or the official app store."
            );
        }

        String verdict = score >= 7 ? "DANGEROUS" :
                score > 0 ? "SUSPICIOUS" : "REVIEW APP";

        String advice;
        if (verdict.equals("DANGEROUS")) {
            advice =
                    "DO NOT INSTALL THIS APP. The link combines an installer download with wording associated " +
                    "with modified or cracked software. Use a verified official store instead.";
        } else if (verdict.equals("SUSPICIOUS")) {
            advice =
                    "PROCEED WITH EXTREME CAUTION. Do not install the file unless you independently verify " +
                    "the publisher and download source. Prefer Google Play, the Apple App Store or the " +
                    "software developer's verified website.";
        } else {
            advice =
                    "REVIEW APP: Confirm the developer's identity, ratings and permissions before installing. " +
                    "A store-style URL or a lack of detected warning signs is not a guarantee that an app is safe.";
        }

        return new Assessment(verdict, score, webUri, reasons, advice);
    }

    // ---------------------------------------------------------
    // MAIN SAFETY ASSESSMENT
    // ---------------------------------------------------------

    private Assessment assess(String value) {

        String text = value.trim();

        List<String> reasons = new ArrayList<>();

        URI uri = parseUrl(text);
        int score = 0;

        String qrType = detectQrType(text);

        if (qrType.equals("APP")) {
            return assessApp(text, uri);
        }

        if (qrType.equals("INVALID URL")) {
            return new Assessment(
                    "REVIEW URL",
                    2,
                    null,
                    List.of("The content looks like a URL, but its format is invalid or incomplete. " +
                            "Check the spelling and enter a complete http:// or https:// address."),
                    "DO NOT OPEN THIS LINK until the address is corrected and independently verified."
            );
        }

        // UPI PAYMENT

        if (qrType.equals("UPI PAYMENT")) {

            Map<String, String> fields = parseUpiFields(text);

            String payeeAddress = fields.getOrDefault("pa", "");
            String payeeName = fields.getOrDefault("pn", "");
            String note = fields.getOrDefault("tn", "");

            String decoded = (text + " " + payeeAddress + " " +
                    payeeName + " " + note).toLowerCase(Locale.ROOT);

            for (String word : List.of(
                    "verify", "urgent", "reward", "login",
                    "password", "otp", "refund", "kyc", "winner"
            )) {
                if (Pattern.compile(
                        "(?i)\\b" + Pattern.quote(word) + "\\b"
                ).matcher(decoded).find()) {
                    score++;
                }
            }

            if (Pattern.compile(
                    "(?i)\\b(receive|refund|cashback|win|prize|claim|reward)\\b|get money"
            ).matcher(payeeName + " " + note).find()) {

                score += 4;

                reasons.add(
                        "The payment text contains wording associated with receiving money, refunds or rewards. " +
                        "A genuine incoming payment never requires your UPI PIN."
                );
            }

            if (payeeAddress.isBlank()) {
                score += 2;
                reasons.add("The UPI QR does not clearly include a payee address (pa).");
            }

            if (reasons.isEmpty()) {
                reasons.add(
                        "No obvious suspicious wording was detected. " +
                        "QR Shield cannot verify the recipient's bank account or identity."
                );
            }

            return new Assessment(
                    "REVIEW PAYMENT",
                    score,
                    null,
                    reasons,
                    "REVIEW PAYMENT: Confirm the recipient's UPI ID, name and exact amount " +
                    "inside your trusted payment app before proceeding. " +
                    "Never enter your UPI PIN to receive money."
            );
        }

        // NON-WEBSITE CONTENT

        if (!qrType.equals("WEBSITE")) {
            return assessNonWebsite(text, qrType);
        }

        // WEBSITE ASSESSMENT

        if (uri == null) {
            return new Assessment(
                    "REVIEW URL",
                    2,
                    null,
                    List.of("The content looks like a website address, but it is not a valid HTTP or HTTPS URL."),
                    "DO NOT OPEN THIS LINK until the address is corrected and independently verified."
            );
        }

        String host = uri.getHost().toLowerCase(Locale.ROOT);
        String lower = text.toLowerCase(Locale.ROOT);

        if (isPrivateHost(host)) {
            score = 3;
            reasons.add(
                    "The address points to a local/private network or localhost rather than a public website."
            );
        }

        if (uri.getScheme().equalsIgnoreCase("http")) {
            score += 1;
            reasons.add("The link uses HTTP instead of encrypted HTTPS.");
        }

        int port = uri.getPort();
        boolean standardPort = port == -1 ||
                (uri.getScheme().equalsIgnoreCase("http") && port == 80) ||
                (uri.getScheme().equalsIgnoreCase("https") && port == 443);
        if (!standardPort) {
            score += 1;
            reasons.add(
                    "The URL uses a non-standard port (" + port + "). This is not proof of danger, " +
                    "but you should verify why the website uses it."
            );
        }

        if (host.contains("xn--")) {
            score += 3;
            reasons.add(
                    "The domain uses Punycode, which can be used to imitate familiar website names."
            );
        }

        if (uri.getRawAuthority() != null &&
                uri.getRawAuthority().contains("@")) {
            score += 2;
            reasons.add(
                    "The URL contains an @ symbol in its authority, which can disguise the actual destination."
            );
        }

        String[] parts = host.split("\\.");
        String tld = parts.length > 0 ? parts[parts.length - 1] : "";

        if (Set.of("xyz", "top", "buzz", "click", "loan", "work", "gq", "tk")
                .contains(tld)) {
            score += 2;
            reasons.add(
                    "The domain extension is sometimes associated with low-trust or abusive sites. " +
                    "This alone does not prove danger."
            );
        }

        if (isShortenedHost(host)) {
            score += 2;
            reasons.add(
                    "This is a shortened link, so its final destination is not obvious."
            );
        }

        Matcher severe = Pattern.compile(
                "(?i)(?:\\.(?:exe|msi|bat|cmd|scr|jar|apk|dmg|deb|rpm|appimage)(?:$|[?#/])|" +
                "malware|trojan|phishing|steal[-_/]?password|" +
                "credential[-_/]?steal|ransomware|keylogger|spyware)"
        ).matcher(lower);

        if (severe.find()) {
            score += 6;
            reasons.add(
                    "The URL contains a high-risk malware, credential-theft, phishing, or executable-download indicator."
            );
        }

        Matcher words = Pattern.compile(
                "(?i)(verify|urgent|immediately|reward|login|password|otp|refund|kyc|winner|claim|update|pay)"
        ).matcher(lower);

        Set<String> found = new LinkedHashSet<>();

        while (words.find()) {
            found.add(words.group().toLowerCase(Locale.ROOT));
        }

        if (!found.isEmpty()) {
            score += Math.min(2, found.size());
            reasons.add(
                    "The URL contains potentially manipulative or credential-related words: " +
                    String.join(", ", found) + "."
            );
        }

        String verdict = score >= 7 ? "DANGEROUS" :
                score >= 1 ? "SUSPICIOUS" : "SAFE";

        if (reasons.isEmpty()) {
            reasons.add(
                    "No obvious warning signs were detected by local checks. " +
                    "This does not guarantee that the website is safe."
            );
        }

        String advice;

        if (verdict.equals("DANGEROUS")) {

            advice =
                    "DO NOT OPEN THIS WEBSITE. Strong warning signs were detected. " +
                    "Do not enter passwords, OTPs, banking information or personal details.";

        } else if (verdict.equals("SUSPICIOUS")) {

            advice =
                    "PROCEED WITH EXTREME CAUTION. Verify the domain independently before opening it. " +
                    "Do not enter sensitive information unless you are certain the website is genuine.";

        } else {

            advice =
                    "No obvious warning signs were detected by local checks. " +
                    "This does not guarantee that the website is safe. " +
                    "Never share passwords, OTPs or payment PINs on an untrusted page.";
        }

        return new Assessment(verdict, score, uri, reasons, advice);
    }

    // ---------------------------------------------------------
    // DISPLAY SCAN RESULT
    // ---------------------------------------------------------

    private void showDecoded(String value) {

        String qrContent = value;

        String qrType = detectQrType(qrContent);
        String contentHeading = getContentHeading(qrType);

        Assessment a = assess(qrContent);

        currentUrl = a.url();
        currentVerdict = a.verdict();

        urlField.setText(qrContent);

        updateVerdictLabel(a.verdict(), a.score());

        StringBuilder out = new StringBuilder();

        out.append("QR TYPE\n");
        out.append(qrType).append("\n\n");

        out.append(contentHeading).append("\n\n");
        out.append(qrContent).append("\n");

        // UPI DETAILS

        if (qrType.equals("APP")) {

            Map<String, String> fields = parseAppFields(qrContent);

            out.append("\nAPPLICATION DETAILS\n");
            out.append("Link type: ")
                    .append(fields.getOrDefault("source", "Not identified"))
                    .append("\n");
            out.append("Android package: ")
                    .append(fields.getOrDefault("package", "Not provided"))
                    .append("\n");
            out.append("Apple App Store ID: ")
                    .append(fields.getOrDefault("appStoreId", "Not provided"))
                    .append("\n");
            out.append("Installer type: ")
                    .append(fields.getOrDefault("installer", "Not an identified installer file"))
                    .append("\n");
            out.append("Publisher check: QR Shield cannot verify the developer or publisher from the QR content alone.\n");
        }

        // UPI DETAILS

        if (qrType.equals("UPI PAYMENT")) {

            Map<String, String> fields = parseUpiFields(qrContent.trim());

            out.append("\nUPI PAYMENT DETAILS\n");

            out.append("Payee address (VPA): ")
                    .append(fields.getOrDefault("pa", "Not provided"))
                    .append("\n");

            out.append("Payee name: ")
                    .append(fields.getOrDefault("pn", "Not provided"))
                    .append("\n");

            out.append("Amount: ")
                    .append(fields.getOrDefault("am", "Not specified"))
                    .append("\n");

            out.append("Transaction note: ")
                    .append(fields.getOrDefault("tn", "None"))
                    .append("\n");

            out.append(
                    "Recipient verification: QR Shield cannot verify bank account ownership. " +
                    "Confirm the recipient in your payment app.\n"
            );
        }

        // WIFI DETAILS

        if (qrType.equals("WIFI NETWORK")) {

            Map<String, String> fields = parseWifiFields(qrContent);

            out.append("\nWIFI DETAILS\n");

            out.append("Network name: ")
                    .append(fields.getOrDefault("S", "Not provided"))
                    .append("\n");

            out.append("Security type: ")
                    .append(fields.getOrDefault("T", "Not specified"))
                    .append("\n");

            String wifiPassword = fields.getOrDefault("P", "");

            out.append("Password: ")
                    .append(wifiPassword.isBlank() ? "Not provided" : wifiPassword)
                    .append("\n");

            out.append("Hidden network: ")
                    .append(fields.getOrDefault("H", "Not specified"))
                    .append("\n");

            if (!wifiPassword.isBlank()) {
                out.append(
                        "Privacy warning: The Wi-Fi password is visible above. " +
                        "Do not share screenshots or the QR code with untrusted people.\n"
                );
            } else {
                out.append("Privacy note: This QR code does not contain a Wi-Fi password.\n");
            }
        }

        // EMAIL DETAILS

        if (qrType.equals("EMAIL")) {

            Map<String, String> fields = parseEmailFields(qrContent);

            out.append("\nEMAIL DETAILS\n");

            out.append("Recipient: ")
                    .append(fields.getOrDefault("to", "Not provided"))
                    .append("\n");

            out.append("CC: ")
                    .append(fields.getOrDefault("cc", "Not provided"))
                    .append("\n");

            out.append("BCC: ")
                    .append(fields.getOrDefault("bcc", "Not provided"))
                    .append("\n");

            out.append("Subject: ")
                    .append(fields.getOrDefault("subject", "No subject"))
                    .append("\n");

            out.append("Message:\n")
                    .append(fields.getOrDefault("body", "No message"))
                    .append("\n");

            out.append(
                    "Note: Review the recipient and message before sending the email.\n"
            );
        }

        // SMS DETAILS

        if (qrType.equals("SMS")) {

            Map<String, String> fields = parseSmsFields(qrContent);

            out.append("\nSMS DETAILS\n");

            out.append("Phone number: ")
                    .append(fields.getOrDefault("number", "Not provided"))
                    .append("\n");

            out.append("Message: ")
                    .append(fields.getOrDefault("body", "Not provided"))
                    .append("\n");
        }

        // LOCATION DETAILS

        if (qrType.equals("LOCATION")) {
            String coordinates = qrContent.substring(4);
            String pair = coordinates.split("[?;]", 2)[0];
            String[] parts = pair.split(",", 2);
            Map<String, String> locationQuery = new LinkedHashMap<>();
            int queryIndex = coordinates.indexOf('?');
            if (queryIndex >= 0) {
                locationQuery = parseQuery(coordinates.substring(queryIndex + 1));
            }

            out.append("\nLOCATION DETAILS\n");
            if (parts.length == 2) {
                out.append("Latitude: ").append(parts[0].trim()).append("\n");
                out.append("Longitude: ").append(parts[1].trim()).append("\n");
            } else {
                out.append("Coordinates: Invalid or incomplete\n");
            }
            out.append("Place label: ")
                    .append(locationQuery.getOrDefault("q", "Not provided"))
                    .append("\n");
        }

        // ASSESSMENT

        out.append("\nASSESSMENT\n");

        for (String reason : a.reasons()) {
            out.append("• ").append(reason).append("\n");
        }

        out.append("\nRECOMMENDED ACTION\n");
        out.append(a.advice()).append("\n");

        // LIVE REPUTATION CHECK

        if (a.url() != null) {

            out.append("\nLIVE REPUTATION CHECK\n");

            if (isSafeBrowsingConfigured()) {
                out.append(
                        "CHECKING: Google Safe Browsing reputation check is in progress..."
                );
            } else {
                out.append(
                        "NOT CONFIGURED: Local checks only. " +
                        "Set SAFE_BROWSING_API_KEY to enable Google Safe Browsing."
                );
            }
        }

        result.setText(out.toString());

        openLinkButton.setEnabled(
                a.url() != null &&
                !a.verdict().equals("DANGEROUS") &&
                !isSafeBrowsingConfigured()
        );

        status.setText("QR decoded and assessed: " + a.verdict());

        addHistory(qrContent, a.verdict());

        if (a.url() != null && isSafeBrowsingConfigured()) {
            runSafeBrowsing(a.url(), qrContent);
        }
    }

    private void updateVerdictLabel(String verdict, int score) {

        verdictLabel.setText(
                verdict + " | Risk score: " +
                (score < 0 ? "N/A" : Integer.toString(score))
        );

        if (verdict.equals("SAFE")) {
            verdictLabel.setBackground(SAFE);

        } else if (verdict.equals("DANGEROUS")) {
            verdictLabel.setBackground(DANGER);

        } else if (verdict.equals("SUSPICIOUS") ||
                verdict.startsWith("REVIEW")) {
            verdictLabel.setBackground(WARNING);

        } else {
            verdictLabel.setBackground(new Color(85, 100, 120));
        }
    }

    // ---------------------------------------------------------
    // GOOGLE SAFE BROWSING
    // ---------------------------------------------------------

    private void runSafeBrowsing(URI uri, String original) {

        worker.submit(() -> {

            String message;
            boolean flagged = false;
            boolean completed = false;

            try {

                String key = System.getenv("SAFE_BROWSING_API_KEY");

                if (key == null || key.isBlank()) {
                    throw new Exception("API key is not configured.");
                }

                String body =
                        "{\"client\":{\"clientId\":\"qr-shield\",\"clientVersion\":\"1.0\"}," +
                        "\"threatInfo\":{\"threatTypes\":[\"MALWARE\",\"SOCIAL_ENGINEERING\"," +
                        "\"UNWANTED_SOFTWARE\",\"POTENTIALLY_HARMFUL_APPLICATION\"]," +
                        "\"platformTypes\":[\"ANY_PLATFORM\"]," +
                        "\"threatEntryTypes\":[\"URL\"]," +
                        "\"threatEntries\":[{\"url\":\"" +
                        jsonEscape(uri.toString()) +
                        "\"}]}}";

                HttpRequest req = HttpRequest.newBuilder(
                                URI.create(
                                        "https://safebrowsing.googleapis.com/v4/threatMatches:find?key=" +
                                        URLEncoder.encode(key, StandardCharsets.UTF_8)
                                )
                        )
                        .timeout(Duration.ofSeconds(12))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();

                HttpResponse<String> res =
                        HttpClient.newHttpClient().send(
                                req,
                                HttpResponse.BodyHandlers.ofString()
                        );

                if (res.statusCode() == 200) {

                    Matcher m = Pattern.compile(
                            "\"threatType\"\\s*:\\s*\"([A-Z_]+)\""
                    ).matcher(res.body());

                    List<String> threats = new ArrayList<>();

                    while (m.find()) {
                        threats.add(m.group(1));
                    }

                    completed = true;

                    if (!threats.isEmpty()) {

                        flagged = true;

                        message =
                                "THREAT DETECTED: Google Safe Browsing flagged this URL. " +
                                "Threat types: " + String.join(", ", threats) +
                                ". Do not open this website.";

                    } else {

                        message =
                                "CHECK COMPLETED: Google Safe Browsing returned no known threats. " +
                                "This does not guarantee that the website is safe.";
                    }

                } else {

                    message =
                            "CHECK UNAVAILABLE: Google Safe Browsing returned HTTP " +
                            res.statusCode() + ". Local checks only.";
                }

            } catch (Exception ex) {

                message =
                        "CHECK FAILED: Google Safe Browsing could not complete the request. " +
                        "Local checks only. Check your API key and internet connection.";
            }

            final boolean isFlagged = flagged;
            final boolean checkCompleted = completed;
            final String finalMessage = message;

            SwingUtilities.invokeLater(() -> {

                if (!Objects.equals(
                        urlField.getText().trim(),
                        original.trim()
                )) {
                    return;
                }

                updateLiveReputationText(finalMessage);

                if (isFlagged) {

                    currentVerdict = "DANGEROUS";
                    openLinkButton.setEnabled(false);

                    verdictLabel.setText(
                            "DANGEROUS | Flagged by Google Safe Browsing"
                    );

                    verdictLabel.setBackground(DANGER);

                    status.setText("Dangerous link detected — do not open");

                    updateHistoryVerdict(original, "DANGEROUS");

                    updateRecommendation(
                            "DO NOT OPEN THIS WEBSITE. " +
                            "Google Safe Browsing detected a known threat. " +
                            "Do not enter passwords, OTPs, payment information or personal details."
                    );

                } else {

                    openLinkButton.setEnabled(
                            currentUrl != null &&
                            !currentVerdict.equals("DANGEROUS")
                    );

                    if (checkCompleted) {
                        status.setText(
                                "Google Safe Browsing check completed — review the result"
                        );
                    } else {
                        status.setText(
                                "Live reputation check unavailable — local checks only"
                        );
                    }
                }
            });
        });
    }

    private static String jsonEscape(String value) {

        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private void updateLiveReputationText(String message) {

        String current = result.getText();
        String marker = "LIVE REPUTATION CHECK\n";

        int index = current.lastIndexOf(marker);

        if (index >= 0) {
            result.setText(
                    current.substring(0, index) +
                    marker +
                    message
            );
        } else {
            result.append("\n\n" + marker + message);
        }
    }

    private void updateRecommendation(String recommendation) {

        String current = result.getText();
        String marker = "RECOMMENDED ACTION\n";

        int start = current.lastIndexOf(marker);

        if (start < 0) return;

        int end = current.indexOf(
                "\n\nLIVE REPUTATION CHECK",
                start
        );

        if (end < 0) end = current.length();

        result.setText(
                current.substring(0, start) +
                marker +
                recommendation +
                current.substring(end)
        );
    }

    // ---------------------------------------------------------
    // OPEN LINK
    // ---------------------------------------------------------

    private void openCurrentLink() {

        if (currentUrl == null ||
                currentVerdict.equals("DANGEROUS")) {
            return;
        }

        String warning;
        if (currentVerdict.equals("SUSPICIOUS")) {
            warning = "WARNING: This QR code has suspicious indicators. " +
                    "Only continue if you independently trust the sender and destination.";
        } else if (currentVerdict.startsWith("REVIEW")) {
            warning = "REVIEW REQUIRED: QR Shield could not fully verify this destination or app publisher. " +
                    "Check the source independently before continuing.";
        } else {
            warning = "QR Shield found no obvious warning signs, but this is not a guarantee of safety.";
        }

        String message =
                "You are about to open:\n\n" +
                currentUrl +
                "\n\n" + warning +
                "\n\nContinue?";

        if (JOptionPane.showConfirmDialog(
                this,
                message,
                currentVerdict.equals("SUSPICIOUS")
                        ? "Suspicious link — confirm carefully"
                        : currentVerdict.startsWith("REVIEW")
                                ? "Review destination — confirm carefully"
                                : "Confirm external link",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE
        ) == JOptionPane.YES_OPTION) {

            try {

                if (!Desktop.isDesktopSupported() ||
                        !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    throw new Exception(
                            "Opening links is not supported on this computer."
                    );
                }

                Desktop.getDesktop().browse(currentUrl);

            } catch (Exception ex) {
                showError("Could not open link: " + ex.getMessage());
            }
        }
    }

    // ---------------------------------------------------------
    // MANUAL URL / QR CONTENT CHECK
    // ---------------------------------------------------------

    private void checkUrl(String raw) {

        if (raw == null || raw.isBlank()) {
            showError("Please enter or scan QR content.");
            return;
        }

        showDecoded(raw);
    }

    // ---------------------------------------------------------
    // URL HELPERS
    // ---------------------------------------------------------

    private static URI parseUrl(String s) {

        try {

            URI u = URI.create(s.trim());

            if (u.getHost() == null ||
                    u.getScheme() == null ||
                    !(u.getScheme().equalsIgnoreCase("http") ||
                            u.getScheme().equalsIgnoreCase("https"))) {
                return null;
            }

            return u;

        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isShortenedHost(String host) {

        List<String> domains = List.of(
                "bit.ly", "tinyurl.com", "t.co", "goo.gl",
                "cutt.ly", "is.gd", "rb.gy", "shorturl.at",
                "t.ly", "lnkd.in", "buff.ly", "ow.ly",
                "short.io", "rebrand.ly", "soo.gd"
        );

        return domains.stream().anyMatch(
                d -> host.equals(d) || host.endsWith("." + d)
        );
    }

    private static boolean isPrivateHost(String host) {

        try {

            String h = host.toLowerCase(Locale.ROOT);

            if (h.equals("localhost") ||
                    h.endsWith(".localhost") ||
                    h.endsWith(".local")) {
                return true;
            }

            if (!h.matches("[0-9.]+|\\[?[0-9a-f:]+\\]?")) {
                return false;
            }

            InetAddress a = InetAddress.getByName(
                    h.replaceAll("[\\[\\]]", "")
            );

            return a.isAnyLocalAddress() ||
                    a.isLoopbackAddress() ||
                    a.isLinkLocalAddress() ||
                    a.isSiteLocalAddress();

        } catch (Exception e) {
            return true;
        }
    }

    private static boolean isSafeBrowsingConfigured() {

        String key = System.getenv("SAFE_BROWSING_API_KEY");

        return key != null && !key.isBlank();
    }

    // ---------------------------------------------------------
    // HISTORY
    // Wi-Fi passwords are hidden in saved history.
    // ---------------------------------------------------------

    private String safeHistoryValue(String value) {

        if (detectQrType(value).equals("WIFI NETWORK")) {

            Map<String, String> fields = parseWifiFields(value);

            if (fields.containsKey("P")) {
                fields.put("P", "[PASSWORD HIDDEN]");
            }

            StringBuilder safe = new StringBuilder("WIFI:");

            for (Map.Entry<String, String> entry : fields.entrySet()) {

                safe.append(entry.getKey())
                        .append(":")
                        .append(entry.getValue()
                                .replace("\\", "\\\\")
                                .replace(";", "\\;"))
                        .append(";");
            }

            return safe.toString();
        }

        return value.replace("\n", " ");
    }

    private void addHistory(String value, String verdict) {

        String historyValue = safeHistoryValue(value);

        String item =
                java.time.LocalDateTime.now()
                        .toString()
                        .replace('T', ' ')
                        .substring(0, 19) +
                " | " + verdict +
                " | " + historyValue;

        historyModel.insertElementAt(item, 0);

        while (historyModel.size() > 50) {
            historyModel.removeElementAt(historyModel.size() - 1);
        }

        persistHistory();
    }

    private void updateHistoryVerdict(String original, String verdict) {

        String expected = safeHistoryValue(original);

        for (int i = 0; i < historyModel.size(); i++) {

            String item = historyModel.get(i);

            int first = item.indexOf(" | ");

            int second = first < 0 ? -1 :
                    item.indexOf(" | ", first + 3);

            if (second >= 0 &&
                    item.substring(second + 3).equals(expected)) {

                historyModel.set(
                        i,
                        item.substring(0, first + 3) +
                                verdict +
                                item.substring(second)
                );

                persistHistory();
                return;
            }
        }
    }

    private void persistHistory() {

        StringBuilder b = new StringBuilder();

        for (int i = 0; i < historyModel.size(); i++) {
            b.append(historyModel.get(i).replace("\n", " "))
                    .append("\n");
        }

        prefs.put("history", b.toString());
    }

    private void loadHistory() {

        String h = prefs.get("history", "");

        for (String line : h.split("\\R")) {
            if (!line.isBlank()) {
                historyModel.addElement(line);
            }
        }
    }

    // ---------------------------------------------------------
    // ERROR HANDLING
    // ---------------------------------------------------------

    private void showError(String message) {

        SwingUtilities.invokeLater(() -> {

            status.setText(message);

            JOptionPane.showMessageDialog(
                    this,
                    message,
                    "QR Shield",
                    JOptionPane.WARNING_MESSAGE
            );
        });
    }

    // ---------------------------------------------------------
    // MAIN METHOD
    // ---------------------------------------------------------

    public static void main(String[] args) {

        SwingUtilities.invokeLater(() -> {

            try {
                UIManager.setLookAndFeel(
                        UIManager.getSystemLookAndFeelClassName()
                );
            } catch (Exception ignored) {
            }

            new Main().setVisible(true);
        });
    }
}
