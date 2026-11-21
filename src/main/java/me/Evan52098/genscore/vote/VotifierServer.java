package me.Evan52098.genscore.vote;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * GensCore's own Votifier receiver, so the server does not need the Votifier/NuVotifier plugin. A faithful port of
 * BlockvilleCore's VotifierServer: the same key files, the same greeting ({@code VOTIFIER 2 <challenge>}), protocol v1
 * (a 256-byte RSA/ECB/PKCS1 block decrypted with {@code rsa/private.key}) and NuVotifier protocol v2 (magic 0x733A,
 * length, JSON with an HMAC-SHA256 signature made with {@code token.txt}, and the per-connection challenge).
 *
 * <p>Runs entirely off the game threads: one accept thread and a small bounded worker pool. Hardening added on top of
 * BlockvilleCore's version:
 * <ul>
 *   <li>Per-IP rate limit (thread-safe), at most two open connections per IP, a cap on connections being handled at
 *       once (excess connections are closed), and a hard 5-second deadline per connection (not per read), so slow
 *       senders cannot hold the worker threads and starve real votes.</li>
 *   <li>Replay protection: v2 is bound to the per-connection challenge; an exact v1 block seen in the last 24 hours is
 *       rejected (v1 has no nonce). Votes for the same name and site within the duplicate window are also ignored by
 *       the vote service.</li>
 *   <li>Strict field validation (username characters and lengths, service name length) before anything is stored.</li>
 * </ul>
 * Keys are created on first start ({@code rsa/public.key} is what you give voting sites; {@code token.txt} is the v2
 * token, which the owner may replace with their own). Nothing secret is in code or config defaults.
 */
public final class VotifierServer {

    public record IncomingVote(String serviceName, String username, String address, String timestamp, String fromIp, int protocol) {
    }

    private static final int V2_MAGIC = 0x733A;
    private static final int MAX_V2_LENGTH = 8192;
    private static final int MAX_ACTIVE = 16;
    /** Worker threads; MAX_ACTIVE - WORKERS connections may wait in the queue. */
    private static final int WORKERS = 8;
    /** Hard limit for a whole connection once a worker picks it up (not per read), so a slow sender cannot pin a worker. */
    private static final long CONNECTION_DEADLINE_MS = 5_000L;
    /** Connections one address may have open (queued or being handled) at once. */
    private static final int MAX_OPEN_PER_IP = 2;
    private static final long REPLAY_WINDOW_MS = 24L * 3_600_000L;
    private static final int MAX_REPLAY_ENTRIES = 50_000;
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_.\\-*]{1,32}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Logger logger;
    private final File folder;
    private final String host;
    private final int port;
    private final int maxPerIpPerMinute;
    private final Consumer<IncomingVote> onVote;
    private final Map<String, AtomicInteger> perIp = new ConcurrentHashMap<>();
    private final Map<String, Integer> openPerIp = new ConcurrentHashMap<>();
    private final Map<String, Long> seenV1 = new ConcurrentHashMap<>();
    private final AtomicInteger active = new AtomicInteger();
    private volatile long minuteStarted = System.currentTimeMillis();
    private PrivateKey privateKey;
    private byte[] token;
    private ServerSocket socket;
    private ExecutorService workers;
    private volatile boolean running;

    public VotifierServer(Logger logger, File folder, String host, int port, int maxPerIpPerMinute, Consumer<IncomingVote> onVote) {
        this.logger = logger;
        this.folder = folder;
        this.host = host;
        this.port = port;
        this.maxPerIpPerMinute = Math.max(1, maxPerIpPerMinute);
        this.onVote = onVote;
    }

    /** Loads or creates the keys and starts listening. Call off the game threads (key generation takes a moment). */
    public void start() throws Exception {
        loadOrCreateKeys();
        socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(InetAddress.getByName(host), port));
        AtomicInteger n = new AtomicInteger();
        // Bounded: never more queued work than connections we accept.
        workers = new ThreadPoolExecutor(WORKERS, WORKERS, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(MAX_ACTIVE), r -> {
            Thread t = new Thread(r, "GensCore-Votifier-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        }, new ThreadPoolExecutor.AbortPolicy());
        running = true;
        Thread acceptThread = new Thread(this::acceptLoop, "GensCore-Votifier");
        acceptThread.setDaemon(true);
        acceptThread.start();
        logger.info("[votes] Votifier listening on " + host + ":" + port + " (protocol v1 + v2).");
    }

    public void stop() {
        running = false;
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
            // closing anyway
        }
        if (workers != null) {
            workers.shutdownNow();
        }
    }

    private void acceptLoop() {
        while (running) {
            Socket client;
            try {
                client = socket.accept();
            } catch (IOException e) {
                if (running) {
                    logger.log(Level.WARNING, "[votes] Votifier accept failed", e);
                }
                continue;
            }
            String ip = client.getInetAddress().getHostAddress();
            if (!allow(ip) || active.get() >= MAX_ACTIVE) {
                close(client);
                continue;
            }
            if (!openFor(ip)) {
                close(client); // this address already has MAX_OPEN_PER_IP connections open
                continue;
            }
            active.incrementAndGet();
            try {
                workers.execute(() -> {
                    try {
                        handle(client, ip);
                    } finally {
                        active.decrementAndGet();
                        closedFor(ip);
                        close(client);
                    }
                });
            } catch (RuntimeException rejected) {
                active.decrementAndGet();
                closedFor(ip);
                close(client);
            }
        }
    }

    /** Counts an open connection for the address; false (nothing counted) when it already has the maximum open. */
    private boolean openFor(String ip) {
        boolean[] ok = {false};
        openPerIp.compute(ip, (k, n) -> {
            int open = n == null ? 0 : n;
            if (open >= MAX_OPEN_PER_IP) {
                return n;
            }
            ok[0] = true;
            return open + 1;
        });
        return ok[0];
    }

    private void closedFor(String ip) {
        openPerIp.computeIfPresent(ip, (k, n) -> n <= 1 ? null : n - 1);
    }

    /** Rate limit per connecting address, per minute. */
    private boolean allow(String ip) {
        long now = System.currentTimeMillis();
        if (now - minuteStarted > 60_000L) {
            synchronized (this) {
                if (now - minuteStarted > 60_000L) {
                    perIp.clear();
                    minuteStarted = now;
                }
            }
        }
        return perIp.computeIfAbsent(ip, k -> new AtomicInteger()).incrementAndGet() <= maxPerIpPerMinute;
    }

    private void handle(Socket client, String ip) {
        try {
            long deadline = System.currentTimeMillis() + CONNECTION_DEADLINE_MS;
            client.setSoTimeout((int) CONNECTION_DEADLINE_MS);
            String challenge = randomToken(24);
            OutputStream out = client.getOutputStream();
            out.write(("VOTIFIER 2 " + challenge + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            DataInputStream in = new DataInputStream(new DeadlineInputStream(client, deadline));
            int first = in.readUnsignedShort();
            if (first == V2_MAGIC) {
                handleV2(in, out, challenge, ip);
            } else {
                byte[] block = new byte[256];
                block[0] = (byte) (first >> 8);
                block[1] = (byte) first;
                in.readFully(block, 2, 254);
                handleV1(block, ip);
            }
        } catch (IOException e) {
            // timeouts, port scanners, sites closing early: nothing to do
        } catch (Exception e) {
            logger.warning("[votes] Rejected a vote from " + ip + ": " + e.getMessage());
        }
    }

    private void handleV1(byte[] block, String ip) throws Exception {
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.DECRYPT_MODE, privateKey);
        String text = new String(cipher.doFinal(block), StandardCharsets.UTF_8);
        String[] lines = text.split("\n", -1);
        if (lines.length < 5 || !"VOTE".equals(lines[0].trim())) {
            throw new IllegalArgumentException("not a v1 vote block");
        }
        String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(block));
        long now = System.currentTimeMillis();
        Long seen = seenV1.putIfAbsent(fingerprint, now);
        if (seen != null && now - seen < REPLAY_WINDOW_MS) {
            throw new IllegalArgumentException("replayed v1 vote block");
        }
        seenV1.put(fingerprint, now);
        pruneReplays(now);
        deliver(new IncomingVote(lines[1].trim(), lines[2].trim(), lines[3].trim(), lines[4].trim(), ip, 1));
    }

    private void pruneReplays(long now) {
        if (seenV1.size() > MAX_REPLAY_ENTRIES) {
            seenV1.values().removeIf(t -> now - t >= REPLAY_WINDOW_MS);
            if (seenV1.size() > MAX_REPLAY_ENTRIES) {
                seenV1.clear(); // under a flood, fall back to the duplicate-window check in the vote service
            }
        }
    }

    private void handleV2(DataInputStream in, OutputStream out, String challenge, String ip) throws Exception {
        int length = in.readUnsignedShort();
        if (length <= 0 || length > MAX_V2_LENGTH) {
            throw new IllegalArgumentException("v2 message length " + length);
        }
        byte[] body = new byte[length];
        in.readFully(body);
        try {
            JsonObject envelope = JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
            String payloadText = string(envelope, "payload");
            byte[] signature = Base64.getDecoder().decode(string(envelope, "signature"));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(token, "HmacSHA256"));
            byte[] expected = mac.doFinal(payloadText.getBytes(StandardCharsets.UTF_8));
            if (!MessageDigest.isEqual(expected, signature)) {
                throw new IllegalArgumentException("bad v2 signature (wrong token on the site?)");
            }
            JsonObject payload = JsonParser.parseString(payloadText).getAsJsonObject();
            // Replay protection: the signed payload must carry this connection's fresh challenge.
            if (!MessageDigest.isEqual(challenge.getBytes(StandardCharsets.UTF_8), string(payload, "challenge").getBytes(StandardCharsets.UTF_8))) {
                throw new IllegalArgumentException("v2 challenge mismatch");
            }
            deliver(new IncomingVote(string(payload, "serviceName"), string(payload, "username"),
                    optional(payload, "address"), optional(payload, "timestamp"), ip, 2));
            reply(out, "{\"status\":\"ok\"}");
        } catch (Exception e) {
            JsonObject error = new JsonObject();
            error.addProperty("status", "error");
            error.addProperty("cause", e.getClass().getSimpleName());
            error.addProperty("error", String.valueOf(e.getMessage()));
            reply(out, error.toString());
            throw e;
        }
    }

    private static String string(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive()) {
            throw new IllegalArgumentException("missing " + key);
        }
        return e.getAsString();
    }

    private static String optional(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }

    /** Rejects malformed votes before they reach the database. */
    private void deliver(IncomingVote vote) {
        String name = vote.username().trim();
        if (!USERNAME.matcher(name).matches()) {
            throw new IllegalArgumentException("bad username '" + abbreviate(name) + "'");
        }
        String service = vote.serviceName().trim();
        if (service.isEmpty() || service.length() > 64 || service.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("bad service name '" + abbreviate(service) + "'");
        }
        onVote.accept(new IncomingVote(service, name, abbreviate(vote.address()), abbreviate(vote.timestamp()), vote.fromIp(), vote.protocol()));
    }

    private static String abbreviate(String s) {
        String clean = s == null ? "" : s.replaceAll("\\p{Cntrl}", "");
        return clean.length() > 64 ? clean.substring(0, 64) : clean;
    }

    private static void reply(OutputStream out, String json) throws IOException {
        out.write((json + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private void loadOrCreateKeys() throws Exception {
        File rsa = new File(folder, "rsa");
        File pub = new File(rsa, "public.key");
        File priv = new File(rsa, "private.key");
        if (!pub.isFile() || !priv.isFile()) {
            if (!rsa.isDirectory() && !rsa.mkdirs()) {
                throw new IOException("cannot create " + rsa);
            }
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048, RANDOM);
            KeyPair pair = generator.generateKeyPair();
            Files.writeString(pub.toPath(), Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
            Files.writeString(priv.toPath(), Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()));
            restrict(priv);
            logger.info("[votes] Created a new Votifier key pair in " + rsa.getPath() + " - give public.key to the voting sites.");
        }
        byte[] encoded = Base64.getDecoder().decode(Files.readString(priv.toPath()).trim());
        privateKey = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(encoded));
        File tokenFile = new File(folder, "token.txt");
        if (!tokenFile.isFile()) {
            Files.writeString(tokenFile.toPath(), randomToken(26) + "\n");
            restrict(tokenFile);
            logger.info("[votes] Created a Votifier v2 token in " + tokenFile.getPath() + " - keep it secret; give it only to v2 voting sites.");
        }
        String text = Files.readString(tokenFile.toPath()).trim();
        if (text.length() < 16) {
            throw new IllegalStateException(tokenFile.getPath() + " holds a token shorter than 16 characters");
        }
        token = text.getBytes(StandardCharsets.UTF_8);
    }

    private static void restrict(File file) {
        try {
            Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------"));
        } catch (Exception ignored) {
            // not a POSIX file system (Windows test server)
        }
    }

    private static String randomToken(int length) {
        String alphabet = "abcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder out = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            out.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        }
        return out.toString();
    }

    /**
     * Reads from the socket with one total deadline: before every read the socket timeout is set to the time left, so a
     * client trickling one byte at a time is cut off at the deadline instead of resetting a per-read timeout forever.
     */
    private static final class DeadlineInputStream extends InputStream {
        private final Socket socket;
        private final InputStream in;
        private final long deadline;

        DeadlineInputStream(Socket socket, long deadline) throws IOException {
            this.socket = socket;
            this.in = socket.getInputStream();
            this.deadline = deadline;
        }

        private void arm() throws IOException {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) {
                throw new SocketTimeoutException("connection deadline passed");
            }
            socket.setSoTimeout((int) Math.max(1L, left));
        }

        @Override
        public int read() throws IOException {
            arm();
            return in.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            arm();
            return in.read(b, off, len);
        }
    }

    private static void close(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // already closed
        }
    }
}
