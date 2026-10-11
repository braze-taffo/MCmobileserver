import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.system.Os;
import com.mcmobile.server.core.console.ConsoleBridgeServer;
import kotlin.Unit;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Runs the actual application bridge on ART with real Android pipes/sockets.
 * Synthetic logs only; does not launch a JVM, world, or application service. */
public final class ConsolePipeProbe {
    private static final AtomicReference<ConsoleBridgeServer> active = new AtomicReference<>();
    private static ConsoleBridgeServer bridge(String name, File log) {
        return new ConsoleBridgeServer(name, log, line -> {
            if (line.equals("save-all flush")) active.get().appendLine("Saved the game");
            return Unit.INSTANCE;
        }, control -> Unit.INSTANCE);
    }
    public static void main(String[] args) throws Exception {
        try { run(args); } catch (Throwable error) { error.printStackTrace(System.out); System.exit(1); }
    }
    private static void run(String[] args) throws Exception {
        if (args.length > 1 && args[1].equals("abrupt")) {
            ConsoleBridgeServer disk = bridge("mcs.audit.abrupt." + android.os.Process.myPid(), new File("/data/local/tmp/mcs-audit-abrupt.log"));
            for (int i = 0; i < 1000; i++) disk.appendLine("ABRUPT_" + i);
            System.out.println("ABRUPT_WRITTEN=1000 (no bridge.close or per-line fsync)");
            Runtime.getRuntime().halt(0);
            return;
        }
        boolean expectFixed = args[0].equals("fixed");
        String name = "mcs.audit." + args[0] + "." + android.os.Process.myPid();
        ConsoleBridgeServer bridge = bridge(name, null);
        active.set(bridge);
        bridge.start();
        bridge.broadcastState("{\"status\":\"RUNNING\"}");
        LocalSocket slow = new LocalSocket();
        slow.connect(new LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT));
        slow.setReceiveBufferSize(1024);
        slow.setSoTimeout(3000);
        new BufferedReader(new InputStreamReader(slow.getInputStream(), StandardCharsets.UTF_8)).readLine();
        // The client now stops reading. Producer/pump mirror ServerForegroundService.
        FileDescriptor[] pipe = Os.pipe();
        AtomicInteger produced = new AtomicInteger();
        AtomicInteger pumped = new AtomicInteger();
        CountDownLatch complete = new CountDownLatch(1);
        Thread pump = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(pipe[0]), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) { bridge.appendLine(line); pumped.incrementAndGet(); }
            } catch (Exception e) { e.printStackTrace(System.out); }
        }, "audit-log-pump");
        Thread producer = new Thread(() -> {
            byte[] line = ("x".repeat(1023) + "\n").getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = new FileOutputStream(pipe[1])) {
                for (int i = 0; i < 20000; i++) { out.write(line); produced.incrementAndGet(); }
                complete.countDown();
            } catch (Exception e) { e.printStackTrace(System.out); }
            finally { try { Os.close(pipe[1]); } catch (Exception ignored) {} }
        }, "audit-jvm-stdout");
        pump.setDaemon(true); producer.setDaemon(true); pump.start(); producer.start();
        boolean earlyComplete = complete.await(3, TimeUnit.SECONDS);
        int firstCount = produced.get();
        Thread.sleep(300);
        boolean stalled = !earlyComplete && firstCount == produced.get();
        CountDownLatch stateSent = new CountDownLatch(1);
        Thread broadcaster = new Thread(() -> { bridge.broadcastState("{\"status\":\"STOPPING\"}"); stateSent.countDown(); });
        broadcaster.setDaemon(true); broadcaster.start();
        boolean stateUnblocked = stateSent.await(300, TimeUnit.MILLISECONDS);
        System.out.println("PIPE mode=" + args[0] + " produced=" + produced.get() + "/20000 pumped=" + pumped.get()
            + " stalled=" + stalled + " stateUnblocked=" + stateUnblocked);
        if (expectFixed) {
            LocalSocket healthy = new LocalSocket();
            healthy.connect(new LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT));
            healthy.setSoTimeout(3000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(healthy.getInputStream(), StandardCharsets.UTF_8));
            String first = reader.readLine();
            if (!first.startsWith("S\t")) throw new AssertionError("New client state was delayed behind history");
            healthy.getOutputStream().write("C\tsave-all flush\n".getBytes(StandardCharsets.UTF_8));
            boolean acknowledged = false;
            for (int i = 0; i < 5000; i++) {
                String frame = reader.readLine();
                if (frame == null) break;
                if (frame.equals("L\tSaved the game")) { acknowledged = true; break; }
                if (frame.startsWith("L\t") && frame.contains("AUDIT_")) throw new AssertionError("History was not marked as replay");
            }
            if (!acknowledged) throw new AssertionError("Live save confirmation was lost behind log flood");
            System.out.println("CONTROL stateFirst=true liveSaveReply=true slowClientStillConnected=true");
            healthy.shutdownInput(); healthy.shutdownOutput(); healthy.close();
        }
        slow.shutdownInput(); slow.shutdownOutput(); slow.close();
        if (!complete.await(10, TimeUnit.SECONDS)) throw new AssertionError("Producer failed to resume after client close");
        pump.join(10000);
        if (pump.isAlive()) throw new AssertionError("Log pump did not drain");
        bridge.close();
        if (expectFixed) {
            long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            int remaining;
            do {
                remaining = 0;
                for (Thread thread : Thread.getAllStackTraces().keySet())
                    if (thread.isAlive() && (thread.getName().equals("mcs-console-send") || thread.getName().equals("mcs-console-client"))) remaining++;
                if (remaining > 0) Thread.sleep(20);
            } while (remaining > 0 && System.nanoTime() < limit);
            if (remaining != 0) throw new AssertionError("Leaked client threads: " + remaining);
            System.out.println("CLOSE remainingClientThreads=0");
        }
        if (expectFixed && (!earlyComplete || !stateUnblocked)) throw new AssertionError("Backpressure is still coupled to producer/state");
        if (!expectFixed && (!stalled || stateUnblocked)) throw new AssertionError("Old coupling was not reproduced");
        File log = new File("/data/local/tmp/mcs-audit-" + args[0] + ".log");
        ConsoleBridgeServer disk = bridge(name + ".disk", log);
        long began = System.nanoTime();
        for (int i = 0; i < 1000; i++) disk.appendLine("AUDIT_" + i + " " + "y".repeat(120));
        long appendNanos = System.nanoTime() - began;
        disk.close();
        int lines = 0;
        try (BufferedReader reader = new BufferedReader(new FileReader(log))) { while (reader.readLine() != null) lines++; }
        if (lines != 1000) throw new AssertionError("Log file lost lines: " + lines);
        System.out.println("DISK mode=" + args[0] + " lines=" + lines + " appendMs=" + appendNanos / 1000000.0);
        if (!log.delete()) throw new AssertionError("Could not remove synthetic log");
        System.out.println("ANDROID_CONSOLE_PROBE_PASSED " + args[0]);
        System.exit(0);
    }
}
