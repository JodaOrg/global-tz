/*
 * This file is in the public domain.
 */
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;

/**
 * Generates the global-tz from the IANA tz.
 */
public class GlobalTzMain {

    private static final Path IANA_DIR = Path.of("iana");
    private static final Path GLOBAL_DIR = Path.of("global");

    //-----------------------------------------------------------------------
    /**
     * Main entry point.
     * 
     * @param args ignored
     */
    public static void main(String[] args) {
        try {
            var main = new GlobalTzMain();

            main.init();
            System.out.println("Cloning");
            main.gitClone("https://github.com/eggert/tz.git", IANA_DIR);
            main.gitClone("https://github.com/JodaOrg/global-tz.git", GLOBAL_DIR);

            System.out.println("Preparing");
            main.gitGlobalTz("git", "checkout", "iana-tz");
            var lastMessage = main.gitLastMessage(GLOBAL_DIR);
            if (!lastMessage.startsWith("Reset to iana-tz ")) {
                throw new IllegalStateException("Unexpected message on IANA branch: " + lastMessage);
            }
            var lastProcessedIanaId = lastMessage.substring(17);
            var idsToProcess = new ArrayList<String>();
            var id = main.gitRevParse(IANA_DIR);
            while (!id.equals(lastProcessedIanaId)) {
                idsToProcess.add(0, id);
                main.gitIanaTz("git", "checkout", "HEAD~1");
                id = main.gitRevParse(IANA_DIR);
            }
            System.out.println(idsToProcess);
            for (var idToProcess : idsToProcess) {
                System.out.println("Process: " + idToProcess);

                main.gitIanaTz("git", "checkout", idToProcess);
                main.gitGlobalTz("git", "checkout", "iana-tz");
                main.copyIanaTz();
                //                var latestRealIanaId = main.gitRevParse(IANA_DIR);
                main.gitCommit("\"Reset to iana-tz " + idToProcess + "\"");
                var latestIanaId = main.gitRevParse(GLOBAL_DIR);

                System.out.println("Generating");
                // new generator needed each time
                var generator = new GlobalTzGenerator(IANA_DIR, GLOBAL_DIR);
                generator.generate();
                generator.write();

                System.out.println("Merging");
                main.gitCommit("\"Generated global-tz " + Instant.now() + "\"");
                var generatedIdIanaBranch = main.gitRevParse(GLOBAL_DIR);
                main.gitGlobalTz("git", "checkout", "main");
                // all this rubbish because there is no "-s theirs"
                main.gitGlobalTz("git", "merge", "-s", "ours", latestIanaId);
                main.gitGlobalTz("git", "branch", "temp");
                main.gitGlobalTz("git", "reset", "--hard", latestIanaId);
                main.gitGlobalTz("git", "reset", "--soft", "temp");
                main.gitGlobalTz("git", "commit", "--amend", "-C", generatedIdIanaBranch);
                main.gitGlobalTz("git", "branch", "-D", "temp");
                // cherry-pick the generated code and set it as the state of the merge commit
                main.gitGlobalTz("git", "cherry-pick", generatedIdIanaBranch, "-x");
                main.gitGlobalTz("git", "reset", "--soft", "HEAD~1");
                main.gitGlobalTz("git", "commit", "--amend", "-C", generatedIdIanaBranch);
                // tidy up iana-tz branch
                main.gitGlobalTz("git", "checkout", "iana-tz");
                main.gitGlobalTz("git", "reset", "--hard", latestIanaId);

                System.out.println("Pushing");
                main.gitGlobalTz("git", "push");
                main.gitGlobalTz("git", "checkout", "main");
                main.gitGlobalTz("git", "push");
            }
            System.out.println("Done");
            System.exit(0);

        } catch (Exception ex) {
            ex.printStackTrace();
            System.exit(1);
        }
    }

    // constructor
    private GlobalTzMain() {
    }

    //-----------------------------------------------------------------------
    // initializes the directory structure
    private void init() throws Exception {
        Files.createDirectories(IANA_DIR);
        deleteTree(IANA_DIR);
        Files.createDirectories(GLOBAL_DIR);
        deleteTree(GLOBAL_DIR);
    }

    // deletes a subdirectory tree
    private void deleteTree(Path path) throws IOException {
        if (Files.exists(path)) {
            try (var walkStream = Files.walk(path)) {
                walkStream.sorted(Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete);
            }
        }
    }

    //-----------------------------------------------------------------------
    // performs git clone
    private void gitClone(String repo, Path path) throws Exception {
        var pb = new ProcessBuilder("git", "clone", repo, path.toString());
        if (executeCommand(pb) != 0) {
            throw new IllegalStateException("Git clone failed");
        }
    }

    // copies iana-tz
    private void copyIanaTz() throws Exception {
        try (var walkStream = Files.walk(IANA_DIR)) {
            walkStream.filter(path -> GlobalTzGenerator.isFileToBeCopied(path))
                    .forEach(path -> {
                        try {
                            Files.copy(path, GLOBAL_DIR.resolve(path.getFileName()), REPLACE_EXISTING);
                        } catch (IOException ex) {
                            throw new UncheckedIOException(ex);
                        }
                    });
        }
    }

    // finds the ID
    private String gitRevParse(Path path) throws Exception {
        var pb = new ProcessBuilder("git", "rev-parse", "HEAD");
        pb.directory(path.toFile());
        pb.redirectErrorStream(true);
        var process = pb.start();
        var reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        var line = "";
        var hash = "";
        while ((line = reader.readLine()) != null) {
            System.out.println("git: " + line);
            if (hash.isEmpty()) {
                hash = line;
            }
        }
        process.waitFor();
        return hash;
    }

    // finds the last commit message
    private String gitLastMessage(Path path) throws Exception {
        var pb = new ProcessBuilder("git", "log", "-1", "--pretty=%s");
        pb.directory(path.toFile());
        pb.redirectErrorStream(true);
        var process = pb.start();
        var reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        var line = "";
        var hash = "";
        while ((line = reader.readLine()) != null) {
            System.out.println("git: " + line);
            if (hash.isEmpty()) {
                hash = line;
            }
        }
        process.waitFor();
        return hash;
    }

    // performs git commit
    private void gitCommit(String message) throws Exception {
        var pb1 = new ProcessBuilder("git", "add", "-A");
        pb1.directory(GLOBAL_DIR.toFile());
        if (executeCommand(pb1) != 0) {
            throw new IllegalStateException("Git add failed");
        }
        var pb2 = new ProcessBuilder("git", "commit", "-m", message);
        pb2.directory(GLOBAL_DIR.toFile());
        if (executeCommand(pb2) != 0) {
            throw new IllegalStateException("Git commit failed");
        }
    }

    // performs git
    private void gitIanaTz(String... args) throws Exception {
        var pb1 = new ProcessBuilder(args);
        pb1.directory(IANA_DIR.toFile());
        if (executeCommand(pb1) != 0) {
            throw new IllegalStateException("Git failed");
        }
    }

    // performs git
    private void gitGlobalTz(String... args) throws Exception {
        var pb1 = new ProcessBuilder(args);
        pb1.directory(GLOBAL_DIR.toFile());
        if (executeCommand(pb1) != 0) {
            throw new IllegalStateException("Git failed");
        }
    }

    // executes a process
    private int executeCommand(ProcessBuilder pb) throws Exception {
        pb.redirectErrorStream(true);
        var process = pb.start();
        var reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        var line = "";
        while ((line = reader.readLine()) != null) {
            System.out.println("git: " + line);
        }
        process.waitFor();
        return process.exitValue();
    }

}
