package com.zin.jadxaimcp.server;

import com.zin.jadxaimcp.utils.SecurityConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

public class HeadlessJadxMcpServer {
    private static final Logger logger = LoggerFactory.getLogger(HeadlessJadxMcpServer.class);
    private static final int DEFAULT_PORT = 8650;

    public static void main(String[] args) throws InterruptedException {
        HeadlessOptions options = HeadlessOptions.parse(args);
        if (options.showHelp) {
            HeadlessOptions.printUsage();
            return;
        }

        try (HeadlessJadxProjectContext projectContext =
                     HeadlessJadxProjectContext.open(options.inputFiles, options.threadsCount)) {
            PluginServer server = new PluginServer(projectContext, options.port);
            server.start();

            CountDownLatch shutdownLatch = new CountDownLatch(1);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                logger.info("JADX-AI-MCP Headless: shutting down");
                server.stop();
                shutdownLatch.countDown();
            }, "JADX-AI-MCP-Headless-Shutdown"));

            logger.info("JADX-AI-MCP Headless server started on http://{}:{}/ for {} input file(s)",
                    SecurityConfig.LOOPBACK_HOST, options.port, options.inputFiles.size());
            shutdownLatch.await();
        }
    }

    private static class HeadlessOptions {
        private int port = DEFAULT_PORT;
        private int threadsCount = 0;
        private boolean showHelp = false;
        private final List<File> inputFiles = new ArrayList<>();

        private static HeadlessOptions parse(String[] args) {
            HeadlessOptions options = new HeadlessOptions();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "-h":
                    case "--help":
                        options.showHelp = true;
                        return options;
                    case "--port":
                        options.port = parsePositiveInt(nextValue(args, ++i, "--port"), "--port");
                        break;
                    case "--threads":
                        options.threadsCount = parsePositiveInt(nextValue(args, ++i, "--threads"), "--threads");
                        break;
                    case "--input":
                        options.inputFiles.add(readableFile(nextValue(args, ++i, "--input")));
                        break;
                    default:
                        if (arg.startsWith("-")) {
                            throw new IllegalArgumentException("Unknown option: " + arg);
                        }
                        options.inputFiles.add(readableFile(arg));
                        break;
                }
            }
            if (options.inputFiles.isEmpty()) {
                throw new IllegalArgumentException("Missing input file. Use --input <apk|dex|jar|zip>");
            }
            return options;
        }

        private static void printUsage() {
            System.err.println("Usage: java -cp <classpath> com.zin.jadxaimcp.server.HeadlessJadxMcpServer "
                    + "--input <apk|dex|jar|zip> [--port 8650] [--threads N]");
            System.err.println("Security: binds to 127.0.0.1 and requires Authorization: Bearer "
                    + SecurityConfig.TOKEN_ENV + " unless auth is explicitly disabled.");
        }

        private static String nextValue(String[] args, int index, String optionName) {
            if (index >= args.length) {
                throw new IllegalArgumentException("Missing value for " + optionName);
            }
            return args[index];
        }

        private static int parsePositiveInt(String value, String optionName) {
            try {
                int parsed = Integer.parseInt(value);
                if (parsed <= 0) {
                    throw new IllegalArgumentException(optionName + " must be positive");
                }
                return parsed;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(optionName + " must be an integer", e);
            }
        }

        private static File readableFile(String value) {
            File file = new File(value);
            if (!file.isFile() || !file.canRead()) {
                throw new IllegalArgumentException("Input file is not readable: " + value);
            }
            return file;
        }
    }
}
