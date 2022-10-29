/*
 * This file is in the public domain.
 */
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Generates the global-tz from the IANA tz.
 */
public class GlobalTzMain2 {

    private static final Path IANA_DIR = Path.of("iana");
    private static final Path GLOBAL_DIR = Path.of("global");
    private static final Path IANA_PRL_DIR = Path.of("ianaprl");

    private static final List<String> README_HEADER = List.of(
            "# Global Time Zone Database (global-tz)",
            "",
            "The Global Time Zone Database contains the history of how local time has changed around the world.",
            "It is derived from data in the [IANA Time Zone Database](https://github.com/eggert/tz).",
            "For many years this information could be reliably obtained from the IANA repository.",
            "In recent years however, the data available by default from the IANA repository has been reduced.",
            "This repository primarily exists to reinstate the data that has been effectively removed.",
            "",
            "For more info, please see the project home page:",
            "https://github.com/JodaOrg/global-tz",
            "",
            "The original README is included below.",
            "",
            "-----",
            "");

    /** Set to true for local testing. */
    private final boolean local;

    //-----------------------------------------------------------------------
    /**
     * Main entry point.
     * 
     * @param args ignored
     */
    public static void main(String[] args) {
        try {
            // set to true to run locally without pushing
            var tool = new GlobalTzMain2(true);

            // uncomment to perform initial local testing
            if (tool.local) {
                var generator = new Generator(IANA_PRL_DIR, GLOBAL_DIR);
                generator.generate();
                generator.write();
                System.exit(0);
            }

            System.exit(0);
            System.out.println("Preparing");
            tool.gitGlobalTz("git", "checkout", "iana-tz");
            tool.gitGlobalTz("git", "config", "--global", "user.email", "scolebourne@joda.org");
            tool.gitGlobalTz("git", "config", "--global", "user.name", "Stephen Colebourne");
            tool.gitIanaTz("git", "config", "--global", "user.email", "scolebourne@joda.org");
            tool.gitIanaTz("git", "config", "--global", "user.name", "Stephen Colebourne");

            var lastMessage = tool.gitLastMessage(GLOBAL_DIR);
            int resetPos = lastMessage.indexOf("Reset to iana-tz ");
            if (resetPos < 0) {
                throw new IllegalStateException("Unexpected message on IANA branch: " + lastMessage);
            }
            var lastProcessedIanaId = lastMessage.substring(resetPos + 17);
            System.out.println("Starting from IANA " + lastProcessedIanaId);
            var idsToProcess = new ArrayList<String>();
            var id = tool.gitRevParse(IANA_DIR);
            while (!id.equals(lastProcessedIanaId)) {
                idsToProcess.add(0, id);
                tool.gitIanaTz("git", "checkout", "HEAD~1");
                id = tool.gitRevParse(IANA_DIR);
            }
            System.out.println("Processing " + idsToProcess.size() + " IANA commits");
            var lastId = "";
            for (var idToProcess : idsToProcess) {
                System.out.println("Process: " + idToProcess);

                tool.gitIanaTz("git", "checkout", idToProcess);
                var msg = tool.gitLastMessage(IANA_DIR);
                msg = msg.indexOf('\n') < 0 ? msg : msg.substring(0, msg.indexOf('\n'));
                tool.gitGlobalTz("git", "checkout", "iana-tz");
                tool.copyIanaTz();
                tool.gitCommit(msg.replace("\"", "\\\"") + "\n\nReset to iana-tz " + idToProcess);
                var latestIanaId = tool.gitRevParse(GLOBAL_DIR);

                System.out.println("Generating");
                // new generator needed each time
                var generator = new Generator(IANA_DIR, GLOBAL_DIR);
                generator.generate();
                generator.write();

                System.out.println("Merging");
                tool.gitCommit("Generated global-tz " + Instant.now());
                var generatedIdIanaBranch = tool.gitRevParse(GLOBAL_DIR);
                tool.gitGlobalTz("git", "checkout", "global-tz");
                // all this rubbish because there is no "-s theirs"
                tool.gitGlobalTz("git", "merge", "-s", "ours", latestIanaId);
                tool.gitGlobalTz("git", "branch", "temp");
                tool.gitGlobalTz("git", "reset", "--hard", latestIanaId);
                tool.gitGlobalTz("git", "reset", "--soft", "temp");
                tool.gitGlobalTz("git", "commit", "--amend", "-C", generatedIdIanaBranch);
                tool.gitGlobalTz("git", "branch", "-D", "temp");
                // cherry-pick the generated code and set it as the state of the merge commit
                tool.gitGlobalTz("git", "cherry-pick", generatedIdIanaBranch, "-x");
                tool.gitGlobalTz("git", "reset", "--soft", "HEAD~1");
                tool.gitGlobalTz("git", "commit", "--amend", "-C", generatedIdIanaBranch);
                // tidy up iana-tz branch
                tool.gitGlobalTz("git", "checkout", "iana-tz");
                tool.gitGlobalTz("git", "reset", "--hard", latestIanaId);

                System.out.println("Pushing");
                tool.gitPush();
                tool.gitGlobalTz("git", "checkout", "global-tz");
                tool.gitPush();
                lastId = idToProcess;
            }
            if (!lastId.isEmpty()) {
                System.out.println("Updating READNE");
                var now = Instant.now();
                tool.gitGlobalTz("git", "checkout", "main");
                var readmePath = GLOBAL_DIR.resolve("README.md");
                var readmeLines = Files.lines(readmePath)
                        .collect(Collectors.toCollection(() -> new ArrayList<String>()));
                var generatedIndex = IntStream.range(0, readmeLines.size())
                        .filter(index -> readmeLines.get(index)
                                .startsWith("The Global Time Zone Database was last generated at "))
                        .findFirst()
                        .orElseThrow();
                readmeLines.set(
                        generatedIndex,
                        "The Global Time Zone Database was last generated at " + now + ".");
                var idIndex = IntStream.range(0, readmeLines.size())
                        .filter(index -> readmeLines.get(index)
                                .startsWith("It is up to date with commit "))
                        .findFirst()
                        .orElseThrow();
                readmeLines.set(
                        idIndex,
                        "It is up to date with commit " + lastId + " from the IANA Time Zone database.");
                Files.write(readmePath, readmeLines, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
                tool.gitCommit("Generated global-tz " + now);
                tool.gitPush();
            }
            tool.gitGlobalTz("git", "checkout", "global-tz");
            System.out.println("Done");
            System.exit(0);

        } catch (Exception ex) {
            ex.printStackTrace();
            System.exit(1);
        }
    }

    // constructor
    private GlobalTzMain2(boolean local) {
        this.local = local;
    }

    //-----------------------------------------------------------------------
    // copies iana-tz
    private void copyIanaTz() throws Exception {
        try (var walkStream = Files.walk(IANA_DIR)) {
            walkStream.filter(path -> isFileToBeCopied(path))
                    .forEach(path -> {
                        try {
                            Files.copy(path, GLOBAL_DIR.resolve(path.getFileName()), REPLACE_EXISTING);
                        } catch (IOException ex) {
                            throw new UncheckedIOException(ex);
                        }
                    });
        }
        System.out.println("Generate README");
        var readmePath = GLOBAL_DIR.resolve("README");
        var readmeLines = (List<String>) new ArrayList<String>(Files.readAllLines(readmePath));
        var first = readmeLines.indexOf("README for the tz distribution");
        if (first >= 0) {
            readmeLines = readmeLines.subList(first, readmeLines.size());
        }
        readmeLines.addAll(0, README_HEADER);
        Files.write(readmePath, readmeLines, WRITE, TRUNCATE_EXISTING);
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
        var pb = new ProcessBuilder("git", "log", "-1", "--pretty=%B");
        pb.directory(path.toFile());
        pb.redirectErrorStream(true);
        var process = pb.start();
        var reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        var line = "";
        var text = "";
        while ((line = reader.readLine()) != null) {
            System.out.println("git: " + line);
            if (!text.isEmpty()) {
                text = text + "\n";
            }
            text = text + line;
        }
        process.waitFor();
        if (text.endsWith("\n")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
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

    // performs git push
    private void gitPush() throws Exception {
        if (!local) {
            gitGlobalTz("git", "push");
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

    // is the file skipped
    static boolean isFileToBeCopied(Path file) {
        return Files.isRegularFile(file) &&
                !file.toString().contains(".git") &&
                !file.getFileName().toString().equals(".gitignore");
    }

    //-----------------------------------------------------------------------
    /**
     * Generator for global-tz from iana-tz.
     */
    static class Generator {

        // the input directory
        private final Path inputDir;
        // the output directory
        private final Path outputDir;
        // in-memory store of the mutable files
        private final Map<String, LoadedFile> fileMap = new HashMap<>();

        // constructor
        Generator(Path inputDir, Path outputDir) {
            this.inputDir = inputDir;
            this.outputDir = outputDir;
        }

        // section headers to split etcetera
        private static final List<String> SECTIONS = List.of(
                "# tzdb data for Africa .*",
                "africa",
                "# tzdb data for Antarctica.*",
                "antarctica",
                "# tzdb data for Asia.*",
                "asia",
                "# tzdb data for Australasia.*",
                "australasia",
                "# tzdb data for Europe.*",
                "europe",
                "# tzdb data for North and Central America .*",
                "northamerica",
                "# tzdb data for South America .*",
                "southamerica",
                "# tzdb data for ships .*",
                "etcetera",
                "# tzdb data for .* factory .*",
                "factory",
                "# tzdb links for backward compatibility",
                "backward",
                "# Local Variables.*|\\z");

        //-----------------------------------------------------------------------
        // loads the actions file and generates global-tz
        void generate() throws IOException {
            LoadedFile etc = new LoadedFile("etcetera", Files.readAllLines(inputDir.resolve("etcetera")));
            for (int i = 0; i < SECTIONS.size() - 1; i += 2) {
                Pattern startMatcher = Pattern.compile(SECTIONS.get(i));
                LoadedFile file = ensureFileLoaded(SECTIONS.get(i + 1));
                Pattern endMatcher = Pattern.compile(SECTIONS.get(i + 2));

                List<String> sectionLines = etc.findLines(startMatcher, endMatcher);
                file.addLines(sectionLines);
            }
        }

        //-----------------------------------------------------------------------
        // write all files
        void write() throws IOException {
            // write our changes
            for (var file : fileMap.values()) {
                Files.write(outputDir.resolve(file.fileName), file.lines, WRITE, TRUNCATE_EXISTING);
            }
        }

        //-----------------------------------------------------------------------
        // loads the file into the cache if it is not already there
        private LoadedFile ensureFileLoaded(String fileName) throws IOException {
            if (!fileMap.containsKey(fileName)) {
                var lines = Files.readAllLines(inputDir.resolve(fileName));
                fileMap.put(fileName, new LoadedFile(fileName, lines));
            }
            return fileMap.get(fileName);
        }

        //-----------------------------------------------------------------------
        static class LoadedFile {
            private final String fileName;
            private final ArrayList<String> lines;

            private LoadedFile(String fileName, List<String> lines) {
                this.fileName = Objects.requireNonNull(fileName, "fileName");
                this.lines = new ArrayList<>(lines);
            }

            // finds a line
            private List<String> findLines(Pattern startMatcher, Pattern endMatcher) {
                // find the first line
                for (int firstLine = 0; firstLine < lines.size(); firstLine++) {
                    var line = lines.get(firstLine).strip();
                    if (startMatcher.matcher(line).matches()) {
                        // find the last line
                        for (int lastLine = firstLine; lastLine < lines.size(); lastLine++) {
                            line = lines.get(lastLine).strip();
                            if (endMatcher.matcher(line).matches()) {
                                return lines.subList(firstLine, lastLine);
                            }
                        }
                        throw new IllegalStateException("endMatcher could be found: " + endMatcher);
                    }
                }
                throw new IllegalStateException("startMatcher could be found: " + startMatcher);
            }

            // adds the line at the insert point
            private void addLines(List<String> linesToAdd) {
                lines.addAll(linesToAdd);
            }
        }
    }
}
