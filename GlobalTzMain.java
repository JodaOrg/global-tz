/*
 * This file is in the public domain.
 */
import static java.nio.file.StandardCopyOption.COPY_ATTRIBUTES;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

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
                var generator = new Generator(IANA_DIR, GLOBAL_DIR);
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
            walkStream.filter(path -> isFileToBeCopied(path))
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

    // is the file skipped
    static boolean isFileToBeCopied(Path file) {
        return Files.isRegularFile(file) &&
                !file.toString().contains(".git") &&
                !file.getFileName().toString().equals(".gitignore") &&
                !file.getFileName().toString().startsWith("README");
    }

    //-----------------------------------------------------------------------
    /**
     * Generator for global-tz from iana-tz.
     */
    static class Generator {

        private static final String RULE = "Rule";
        private static final String ZONE = "Zone";

        // the work directory for iana-tz
        private Path ianaDir;
        // the work directory for global-tz
        private Path globalDir;
        // in-memory store of the mutable files
        private final Map<String, LoadedFile> fileMap = new HashMap<>();
        // in-memory copy of backzone
        private LoadedFile backzone;

        // constructor
        Generator(Path ianaDir, Path globalDir) {
            this.ianaDir = ianaDir;
            this.globalDir = globalDir;
        }

        //-----------------------------------------------------------------------
        // loads the actions file and generates global-tz
        void generate() throws IOException {
            // read the actions file
            var actionLines = Files.readAllLines(Path.of("actions.txt"));

            // one file is worked on at a time
            var currentFile = (LoadedFile) null;
            for (int actionIndex = 0; actionIndex < actionLines.size(); actionIndex++) {
                var actionLine = actionLines.get(actionIndex);
                if (actionLine.startsWith("#") || actionLine.isEmpty()) {
                    // ignore comment and empty lines

                } else if (actionLine.startsWith("Edit ")) {
                    currentFile = ensureFileLoaded(actionLine.substring(5));

                } else if (actionLine.startsWith("Remove Lines ")) {
                    currentFile.removeLines(actionLine.substring(13));

                } else if (actionLine.startsWith("Replace Line ")) {
                    currentFile.replaceLine(actionLine.substring(13));

                } else if (actionLine.startsWith("Insert Line ")) {
                    currentFile.insertLine(actionLine.substring(12));

                } else if (actionLine.startsWith("Add Line ")) {
                    currentFile.addLine(actionLine.substring(9));

                } else if (actionLine.startsWith("Find Line ")) {
                    currentFile.setInsertPoint(actionLine.substring(10));

                } else if (actionLine.startsWith("Reinstate Rule ")) {
                    currentFile.resinstateRule(actionLine.substring(15));

                } else if (actionLine.startsWith("Reinstate Zone ")) {
                    currentFile.resinstateZone(actionLine.substring(15));

                } else if (actionLine.startsWith("Reinstate Link")) {
                    currentFile.reinstateLink(actionLine.substring(15));

                } else if (actionLine.startsWith("Add Link ")) {
                    currentFile.addLink(actionLine.substring(9));

                } else if (actionLine.startsWith("Remove Link ")) {
                    currentFile.removeLink(actionLine.substring(12));

                } else if (actionLine.startsWith("Ensure Link ")) {
                    currentFile.ensureLink(actionLine.substring(12));

                } else {
                    throw new IllegalStateException("Unknown action line: " + actionLine);
                }
            }
        }

        //-----------------------------------------------------------------------
        // write all files
        void write() throws IOException {
            // copy latest IANA state
            Files.walkFileTree(ianaDir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    return dir.equals(ianaDir) ? FileVisitResult.CONTINUE : FileVisitResult.SKIP_SUBTREE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (isFileToBeCopied(file)) {
                        Files.copy(file, globalDir.resolve(file.getFileName()), COPY_ATTRIBUTES, REPLACE_EXISTING);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            // write our changes
            for (var file : fileMap.values()) {
                Files.write(globalDir.resolve(file.fileName), file.lines, WRITE, TRUNCATE_EXISTING);
            }
        }

        //-----------------------------------------------------------------------
        // loads the file into the cache if it is not already there
        private LoadedFile ensureFileLoaded(String fileName) throws IOException {
            if (backzone == null) {
                var lines = Files.readAllLines(ianaDir.resolve("backzone"));
                backzone = new LoadedFile("backzone", lines, null);
                fileMap.put("backzone", backzone);
            }
            if (!fileMap.containsKey(fileName)) {
                var lines = Files.readAllLines(ianaDir.resolve(fileName));
                fileMap.put(fileName, new LoadedFile(fileName, lines, backzone));
            }
            return fileMap.get(fileName);
        }

        //-----------------------------------------------------------------------
        class LoadedFile {
            private final String fileName;
            private final ArrayList<String> lines;
            private final LoadedFile backzone;
            private int insertIndex;

            private LoadedFile(String fileName, List<String> lines, LoadedFile backzone) {
                this.fileName = Objects.requireNonNull(fileName, "fileName");
                this.lines = new ArrayList<>(lines);
                this.backzone = backzone;
                this.insertIndex = -1;
            }

            // removes lines starting with the search string
            private void removeLines(String search) {
                lines.removeIf(line -> line.startsWith(search));
            }

            // replaces the line at the insert point
            private void replaceLine(String newLine) {
                if (insertIndex < 1) {
                    throw new IllegalStateException("Replace Line requires an insert location");
                }
                lines.set(insertIndex - 1, newLine);
            }

            // inserts the line above the insert point
            private void insertLine(String newLine) {
                if (insertIndex < 1) {
                    throw new IllegalStateException("Replace Line requires an insert location");
                }
                lines.add(insertIndex - 1, newLine);
            }

            // adds the line at the insert point
            private void addLine(String newLine) {
                if (insertIndex < 0) {
                    throw new IllegalStateException("Replace Line requires an insert location");
                }
                lines.add(insertIndex++, newLine);
            }

            // finds the line starting with the search string
            private void setInsertPoint(String search) {
                for (int i = 0; i < lines.size(); i++) {
                    if (lines.get(i).startsWith(search)) {
                        insertIndex = i + 1;
                        return;
                    }
                }
                throw new IllegalStateException("Find Line failed to find '" + search + "'");
            }

            // reinstates the rule from backzone
            private void resinstateRule(String searchName) {
                var linesToCopy = backzone.findLines(RULE, searchName);
                insertLines(RULE, "# Rule\tNAME\tFROM\tTO\t-\tIN\tON\tAT\tSAVE\tLETTER/S", linesToCopy);
            }

            // reinstates the rule from backzone
            private void resinstateZone(String searchName) {
                var linesToCopy = backzone.findLines(ZONE, searchName);
                insertLines(ZONE, "# Zone\tNAME\t\tSTDOFF\tRULES\tFORMAT\t[UNTIL]", linesToCopy);
            }

            // insert lines at the insert index
            private void insertLines(String type, String header, List<String> body) {
                if (insertIndex < 0) {
                    throw new IllegalStateException("Reinstate " + type + " requires an insert location");
                }
                lines.add(insertIndex++, header);
                lines.addAll(insertIndex, body);
                insertIndex += body.size();
                if (lines.size() == insertIndex || !lines.get(insertIndex).strip().isEmpty()) {
                    lines.add(insertIndex, "");
                }
                insertIndex++;
            }

            // reinstates a link
            private void reinstateLink(String linkPair) {
                var splitPos = linkPair.indexOf(' ');
                var newId = linkPair.substring(0, splitPos);
                var oldId = linkPair.substring(splitPos + 1);
                for (int i = 0; i < lines.size(); i++) {
                    var line = lines.get(i);
                    if (line.startsWith("Link") && line.endsWith(oldId)) {
                        lines.set(i, "Link\t" + newId + "\t" + oldId);
                        return;
                    }
                }
                throw new IllegalStateException("Reinstate Link did not find ID");
            }

            // finds the rule/zone lines in backzone
            private List<String> findLines(String type, String searchName) {
                var otherType = type.equals(RULE) ? ZONE : RULE;
                // find the first line of the rule
                var pattern = Pattern.compile(type + "[ \\t]+" + searchName + "[ \\t].*");
                for (int firstLine = 0; firstLine < lines.size(); firstLine++) {
                    var backzoneLine = lines.get(firstLine).strip();
                    if (pattern.matcher(backzoneLine).matches()) {
                        // find the last line of the rule
                        for (int lastLine = firstLine; lastLine < lines.size(); lastLine++) {
                            backzoneLine = lines.get(lastLine).strip();
                            // no blank line before Sierra Leone
                            if (backzoneLine.isEmpty() ||
                                    backzoneLine.startsWith(otherType) ||
                                    backzoneLine.startsWith("Link") ||
                                    backzoneLine.equals("# Sierra Leone")) {
                                return lines.subList(firstLine, lastLine);
                            }
                        }
                    }
                }
                throw new IllegalStateException("Reinstate " + type + " failed to find '" + searchName + "'");
            }

            // adds a link line
            private void addLink(String linkPair) {
                if (insertIndex < 0) {
                    throw new IllegalStateException("Add Link requires an insert location");
                }
                var linkLine = "Link\t" + linkPair.replace(" ", "\t");
                lines.add(insertIndex++, linkLine);
            }

            // removes a link line
            private void removeLink(String linkPair) {
                var pattern = Pattern.compile("Link[ \\t]+" + linkPair.replace(" ", "[ \\t]+") + "([ \\t].*|$)");
                if (!lines.removeIf(line -> pattern.matcher(line).matches())) {
                    throw new IllegalStateException("Remove Link failed to find 'Link " + linkPair + "'");
                }
            }

            // ensures that the link specified exists
            private void ensureLink(String linkPair) {
                var pattern = Pattern.compile("Link[ \\t]+" + linkPair.replace(" ", "[ \\t]+") + "([ \\t].*|$)");
                lines.stream()
                        .filter(line -> pattern.matcher(line).matches())
                        .findAny()
                        .orElseThrow(
                                () -> new IllegalStateException("Ensure Link failed to find 'Link " + linkPair + "'"));
            }
        }

    }
}
