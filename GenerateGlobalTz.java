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
public class GenerateGlobalTz {

    private static final String RULE = "Rule";
    private static final String ZONE = "Zone";
    private static final Path IANA_DIR = Path.of("iana");
    private static final Path GLOBAL_DIR = Path.of("global");

    // in-memory store of the mutable files
    private final Map<String, LoadedFile> fileMap = new HashMap<>();
    // in-memory copy of backzone
    private LoadedFile backzone;

    //-----------------------------------------------------------------------
    /**
     * Main entry point.
     * 
     * @param args ignored
     */
    public static void main(String[] args) {
        try {
            var generator = new GenerateGlobalTz();
            generator.init();
            System.out.println("Cloning");
            generator.gitClone("https://github.com/eggert/tz.git", IANA_DIR);
            generator.gitClone("https://github.com/JodaOrg/global-tz.git", GLOBAL_DIR);
            System.out.println("Generating");
            generator.generate();
            System.out.println("Writing");
            generator.write();
            System.out.println("Committing");
            generator.gitCommit();
            System.out.println("Pushing");
            generator.gitPush();
            System.out.println("Done");
            System.exit(0);

        } catch (Exception ex) {
            ex.printStackTrace();
            System.exit(1);
        }
    }

    // constructor
    private GenerateGlobalTz() {
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

    // performs git commit
    private void gitCommit() throws Exception {
        var pb1 = new ProcessBuilder("git", "add", "-A");
        pb1.directory(GLOBAL_DIR.toFile());
        if (executeCommand(pb1) != 0) {
            throw new IllegalStateException("Git add failed");
        }
        var pb2 = new ProcessBuilder("git", "commit", "-m", "\"Generated global-tz " + Instant.now() + "\"");
        pb2.directory(GLOBAL_DIR.toFile());
        if (executeCommand(pb2) != 0) {
            throw new IllegalStateException("Git commit failed");
        }
    }

    // performs git push
    private void gitPush() throws Exception {
        var pb1 = new ProcessBuilder("git", "push");
        pb1.directory(GLOBAL_DIR.toFile());
        if (executeCommand(pb1) != 0) {
            throw new IllegalStateException("Git push failed");
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

    //-----------------------------------------------------------------------
    // loads the actions file and generates global-tz
    private void generate() throws IOException {
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
    private void write() throws IOException {
        // copy latest IANA state
        Files.walkFileTree(IANA_DIR, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                return dir.equals(IANA_DIR) ? FileVisitResult.CONTINUE : FileVisitResult.SKIP_SUBTREE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!file.getFileName().toString().equals(".gitignore")) {
                    Files.copy(file, GLOBAL_DIR.resolve(file.getFileName()), COPY_ATTRIBUTES, REPLACE_EXISTING);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        // write our changes
        for (var file : fileMap.values()) {
            Files.write(GLOBAL_DIR.resolve(file.fileName), file.lines, WRITE, TRUNCATE_EXISTING);
        }
    }

    //-----------------------------------------------------------------------
    // loads the file into the cache if it is not already there
    private LoadedFile ensureFileLoaded(String fileName) throws IOException {
        if (backzone == null) {
            var lines = Files.readAllLines(IANA_DIR.resolve("backzone"));
            backzone = new LoadedFile("backzone", lines, null);
            fileMap.put("backzone", backzone);
        }
        if (!fileMap.containsKey(fileName)) {
            var lines = Files.readAllLines(IANA_DIR.resolve(fileName));
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
                    .orElseThrow(() -> new IllegalStateException("Ensure Link failed to find 'Link " + linkPair + "'"));
        }
    }

}
