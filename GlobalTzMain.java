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
public class GlobalTzMain {

    private static final Path IANA_DIR = Path.of("iana");
    private static final Path GLOBAL_DIR = Path.of("global");

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
            // the `iana` subfolder must point at the commit that is to be treated as the latest from IANA
            // the `global` subfolder must have consistent `global-tz` and `iana-tz` branches representing the state to be updated
            var tool = new GlobalTzMain(false);

            // uncomment to perform initial local testing
//            if (tool.local) {
//                var generator = new Generator(IANA_DIR, GLOBAL_DIR);
//                generator.generate();
//                generator.write();
//                System.exit(0);
//            }

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
            // skip commits where actions.txt doesn't work
            if (lastProcessedIanaId.equals("02fb5dae537003c4c6a1b9a7d90a01db206b02cb")) {
                lastProcessedIanaId = "164727dadb9401837c07d3822615432e1c6facba";
            }
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
    private GlobalTzMain(boolean local) {
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
//                System.out.println(">> " + actionLine);
                if (actionLine.startsWith("#") || actionLine.isEmpty()) {
                    // ignore comment and empty lines

                } else if (actionLine.startsWith("Edit ") && !actionLine.contains(":")) {
                    currentFile = ensureFileLoaded(actionLine.substring(5));

                } else if (actionLine.startsWith("Edit ")) {
                    var colonIndex = actionLine.indexOf(':');
                    var fromFileName = actionLine.substring(5, colonIndex);
                    var fromFile = ensureFileLoaded(fromFileName);
                    processAction(fromFile, actionLine.substring(colonIndex + 1).stripLeading());

                } else {
                    processAction(currentFile, actionLine);
                }
            }

            // fix the Makefile system
            // this is lenient, in case the Makefile changes again
            var makefile = ensureFileLoaded("Makefile");
            makefile.removeLines("\t\t  -v backcheck=backward");
            makefile.setInsertPoint("check_now:", "now.ck:", 1, false);
            // remove check_now
            makefile.addLine("\t\ttouch $@");
            makefile.addLine("# Original code:");
            makefile.addLine("check_now_original:\tchecknow.awk date tzdata.zi zdump zic zone1970.tab zonenow.tab");
        }

        // processes the action
        private void processAction(LoadedFile currentFile, String actionLine) throws IOException {
            if (actionLine.startsWith("Remove Line ")) {
                currentFile.removeLine(actionLine.substring(12));

            } else if (actionLine.startsWith("Remove Exact Line ")) {
                currentFile.removeExactLine(actionLine.substring(18));

            } else if (actionLine.startsWith("Remove Lines ")) {
                currentFile.removeLines(actionLine.substring(13));

            } else if (actionLine.startsWith("Replace Line ")) {
                currentFile.replaceLine(actionLine.substring(13));

            } else if (actionLine.startsWith("Insert Line ")) {
                currentFile.insertLine(actionLine.substring(12));

            } else if (actionLine.startsWith("Add Line ")) {
                currentFile.addLine(actionLine.substring(9));

            } else if (actionLine.startsWith("Insert Section ")) {
                currentFile.insertSection(actionLine.substring(15));

            } else if (actionLine.startsWith("Find Exact Line ")) {
                currentFile.setInsertPoint(actionLine.substring(16), 1, true);

            } else if (actionLine.startsWith("Find Line ")) {
                currentFile.setInsertPoint(actionLine.substring(10), 1, false);

            } else if (actionLine.startsWith("Find Line2 ")) {
                currentFile.setInsertPoint(actionLine.substring(11), 2, false);

            } else if (actionLine.startsWith("Find Line3 ")) {
                currentFile.setInsertPoint(actionLine.substring(11), 3, false);

            } else if (actionLine.startsWith("Find After Zone ")) {
                currentFile.findAfterZone(actionLine.substring(16));

            } else if (actionLine.startsWith("Find Next Line")) {
                currentFile.insertIndex++;

            } else if (actionLine.startsWith("Remove Next Line")) {
                currentFile.lines.remove(currentFile.insertIndex);

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

        //-----------------------------------------------------------------------
        // write all files
        void write() throws IOException {
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
        static class LoadedFile {
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

            // removes the first line matching the search string
            private void removeExactLine(String search) {
                for (int i = 0; i < lines.size(); i++) {
                    if (lines.get(i).equals(search)) {
                        lines.remove(i);
                        return;
                    }
                }
                throw new IllegalStateException("Remove Line not found: " + search);
            }

            // removes the first line starting with the search string
            private void removeLine(String search) {
                for (int i = 0; i < lines.size(); i++) {
                    if (lines.get(i).startsWith(search)) {
                        lines.remove(i);
                        return;
                    }
                }
                throw new IllegalStateException("Remove Line not found: " + search);
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

            // inserts the section above the insert point
            private void insertSection(String section) {
                if (insertIndex < 1) {
                    throw new IllegalStateException("Insert Section requires an insert location");
                }
                int start = insertIndex;
                dump(start);
                insertIndex--;
                if (lines.get(insertIndex - 2).isBlank() && lines.get(insertIndex - 1).isBlank()) {
                    insertIndex--;
                } else if (lines.get(insertIndex - 1).isBlank()) {
                    lines.add(insertIndex - 1, "");
                } else {
                    lines.add(insertIndex, "");
                    lines.add(insertIndex, "");
                    insertIndex++;
                }
                dump(start);
                lines.add(insertIndex, section);
                insertIndex++;
                dump(start);
//                System.out.println("==========================");
            }

            private void dump(int start) {
//                System.out.println("==========================");
//                for (int i = start - 3; i < start + 3; i++) {
//                    System.out.println(i + ": " + lines.get(i) + (i == insertIndex ? " <<<" : ""));
//                }
            }

            // inserts the line above the insert point
            private void insertLine(String newLine) {
                if (insertIndex < 1) {
                    throw new IllegalStateException("Insert Line requires an insert location");
                }
                lines.add(insertIndex - 1, newLine);
            }

            // adds the line at the insert point
            private void addLine(String newLine) {
                if (insertIndex < 0) {
                    throw new IllegalStateException("Add Line requires an insert location");
                }
                lines.add(insertIndex++, newLine);
            }

            // finds the line starting with the search string
            private void setInsertPoint(String search, int index, boolean exact) {
                setInsertPoint(search, search, index, exact);
            }

            // finds the line starting with one of the search strings
            private void setInsertPoint(String search, String search2, int index, boolean exact) {
                int count = 0;
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    if (exact ?
                            line.equals(search) || line.equals(search2) :
                            line.startsWith(search) || line.startsWith(search2)) {
                        count++;
                        if (count == index) {
                            insertIndex = i + 1;
                            return;
                        }
                    }
                }
                throw new IllegalStateException("Find Line failed to find '" + search + "'");
            }

            // finds the end of a zone
            private void findAfterZone(String zoneName) {
                var pattern = Pattern.compile("Zone[ \\t]+" + Pattern.quote(zoneName) + ".*");
                for (int i = 0; i < lines.size(); i++) {
                    if (pattern.matcher(lines.get(i)).matches()) {
                        for (; i < lines.size(); i++) {
                            if (lines.get(i).isBlank()) {
                                insertIndex = i;
                                return;
                            }
                        }
                        insertIndex = i;
                        return;
                    }
                }
                throw new IllegalStateException("Find After Zone failed to find '" + zoneName + "'");
            }

            // reinstates the rule from backzone
            private void resinstateRule(String searchName) {
                var linesToCopy = backzone.findLines(RULE, searchName);
                insertLines(RULE, "# Rule\tNAME\tFROM\tTO\t-\tIN\tON\tAT\tSAVE\tLETTER/S", linesToCopy);
                linesToCopy.clear();
            }

            // reinstates the zone from backzone
            private void resinstateZone(String searchName) {
                var linesToCopy = backzone.findLines(ZONE, searchName);
                insertLines(ZONE, "# Zone\tNAME\t\tSTDOFF\tRULES\tFORMAT\t[UNTIL]", linesToCopy);
                linesToCopy.clear();
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
                // find Link from the oldId and replace it with one to the newId
                var splitPos = linkPair.indexOf(' ');
                var newId = linkPair.substring(0, splitPos);
                newId = newId.equals("Europe/Oslo") ? newId + "\t" : newId;
                newId = newId.equals("Pacific/Pohnpei") ? newId + "\t" : newId;
                newId = newId.equals("Pacific/Chuuk") ? newId + "\t" : newId;
                var oldId = linkPair.substring(splitPos + 1);
                for (int i = 0; i < lines.size(); i++) {
                    var line = stripTrailingComment(lines.get(i));
                    if (line.startsWith("Link") && line.endsWith(oldId)) {
                        lines.set(i, "Link\t" + newId + "\t" + oldId);
                        return;
                    }
                }
                throw new IllegalStateException("Reinstate Link did not find ID: " + linkPair);
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
                for (int i = 0; i < lines.size(); i++) {
                    if (pattern.matcher(lines.get(i)).matches()) {
                        lines.remove(i);
                        insertIndex = i;
                        return;
                    }
                }
                throw new IllegalStateException("Remove Link failed to find 'Link " + linkPair + "'");
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

            // strip trailing structured comments
            private String stripTrailingComment(String line) {
                var index = line.indexOf("#=");
                return index < 0 ? line : line.substring(0, index).stripTrailing();
            }
        }

    }
}
