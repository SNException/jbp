// ---------------------------------------------------------------------
//
// jbp - Build tool for Java programs.
//
// Copyright (C) 2020 Niklas Schultz
// All rights reserved.
//
// This software source file is licensed under the terms of MIT license.
// For details, please read the LICENSE file.
//
// ---------------------------------------------------------------------

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class jbp {

    private static long startNanoTime;
    private static String entryPoint = null;

    private static File[] listAllFiles(final File dir) throws IOException {
        assert dir != null;
        assert dir.isDirectory();

        try (final Stream<Path> stream = Files.walk(dir.toPath(), Integer.MAX_VALUE)) {
            final StringBuilder sbuffer = new StringBuilder();
            final List<String> files = stream.map(String::valueOf).sorted().collect(Collectors.toList());
            final File[] result = new File[files.size()];

            assert files.size() == result.length;
            for (int i = 0, l = result.length; i < l; ++i) {
                result[i] = new File(files.get(i));
            }
            return result;
        }
    }

    private static void buildFail(final String reason) {
        assert reason != null;

        System.out.println(reason);
        System.out.println();
        System.out.println("BUILD FAILED");
        System.exit(-1);
    }

    private static String execShellCommand(final File out, final File cwd, final String...args) throws IOException {
        assert args != null;

        final ProcessBuilder builder = new ProcessBuilder(args);
        if (cwd != null)
            builder.directory(cwd);

        builder.redirectErrorStream(true);
        if (out != null) {
	        builder.redirectOutput(out);
            try {
                builder.start().waitFor();
            } catch(final InterruptedException ex) {
                assert false;
            }
            final StringBuilder buffer = new StringBuilder(4096);
            readFileIntoMemory(buffer, out);
            return buffer.toString();
        } else {
            final Process process = builder.start();
            final BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            final StringBuilder sb = new StringBuilder();
            while (true) {
                line = reader.readLine();
                if (line == null)
                    break;
                sb.append(line).append("\n");
            }
            return sb.toString();
        }
    }

    private static void writeToFile(String file, final String data) {
        assert file != null;
        assert data != null;

        // remove illegal characters, which can occur when having a file which uses generics
        file = file.replace("<", "").replace(">", "");

        try (final OutputStream out = new FileOutputStream(new File(file))) {
            out.write(data.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (final IOException ex) {
            System.err.printf("\t-> Failed to write file '%s'\n", file);
        }
    }

    private static void readFileIntoMemory(final StringBuilder buffer, final File file) {
        assert buffer != null;
        assert file != null;

        try (final InputStream in = new FileInputStream(file)) {
            while (true) {
                final byte[] chunk = new byte[buffer.capacity()];
                final int readBytes = in.read(chunk);
                if (readBytes == -1)
                    break;
                buffer.append(new String(chunk, 0, readBytes, StandardCharsets.UTF_8));
            }
        } catch (final IOException ex) {
            System.err.printf("Failed to read file %s", file.getName());
        }
    }

    private static void deleteSourcesFiles() {
        if (Files.exists(Paths.get("sources.txt"))) {
            try {
                Files.delete(Paths.get("sources.txt")); // @Todo: Use file.delete()?
            } catch (final IOException ex) {
                System.err.println();
                System.err.println("Failed to delete sources.txt file.");
            }
        }
    }

    private static void packageRelease() {
        System.out.println("> Packaging release...");
        final File release = new File("build/release");
        if (!release.exists()) {
            if (!release.mkdir()) {
                buildFail("\t-> Failed to create release directory.");
                assert false;
            }
        }

        try {
            Files.copy(Paths.get("build/Program.jar"), new File("build/release/Program.jar").toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (final IOException ex) {
            buildFail("\t-> Failed to copy executable to release directory.");
            assert false;
        }

        final File libs = new File("libs");
        if (!libs.exists()) {
            // done...
        } else {
            final File dependencies = new File("build/release/libs");
            if (!dependencies.exists()) {
                if (!dependencies.mkdir()) {
                    buildFail("\t-> Failed to create libs directory.");
                    assert false;
                }
            }

            // copy dependencies
            {
                final File[] jars = libs.listFiles();
                for (int i = 0, l = jars.length; i < l; ++i) {
                    final File jar = jars[i];
                    try {
                        Files.copy(jar.toPath(), new File("build/release/libs/" + jar.getName()).toPath(), StandardCopyOption.REPLACE_EXISTING);
                    } catch (final IOException ex) {
                        buildFail("Failed to copy dependency " + jar.getName() + " to release/libs directory.");
                        assert false;
                    }
                }
                if (jars.length == 1) {
                    System.out.println("\t-> Program uses " + jars.length + " library.");
                } else {
                    System.out.println("\t-> Program uses " + jars.length + " libraries.");
                }
            }
        }

        // @Robustness:
        new File("build/Manifest.txt").delete();
        new File("build/Program.jar").delete();

        System.out.println("\t-> Package created successfully.");
    }

    private static void createExecutable() {
        System.out.println("> Building executable...");

        // building manifest
        final StringBuilder mfData = new StringBuilder(64);
        mfData.append("Manifest-Version: 1.0").append(System.lineSeparator());

        final File libs = new File("libs");
        if (libs.exists()) {
            final File[] jars = libs.listFiles();
            final StringBuilder classpath = new StringBuilder(128);
            for (int i = 0, l = jars.length; i < l; ++i) {
                classpath.append("libs/");
                classpath.append(jars[i].getName()).append(" ");
            }
            // class path must not end with the delimitter ';'.
            assert classpath.toString().charAt(classpath.toString().length() - 1) == ' ';
            classpath.deleteCharAt(classpath.toString().length() - 1);

            mfData.append("Class-Path: " + classpath.toString()).append(System.lineSeparator());
        }

        mfData.append("Created-By: jbp").append(System.lineSeparator()); // @Incomplete: current java version
        writeToFile("build/Manifest.txt", mfData.toString()); // @Todo: Delete manifest again?

        boolean usesPackages = false;
        {
            final File classesDir = new File("build/classes");
            final File[] classes = classesDir.listFiles();
            for (int i = 0, l = classes.length; i < l; ++i) {
                if (classes[i].isDirectory()) {
                    usesPackages = true; // remember that just using directories are not packages, you have to use the package statement
                    break;
                }
            }
        }

        if (usesPackages) {
            File[] classes = null;
            try {
                classes = listAllFiles(new File("build/classes"));
            } catch (final IOException ex) {
                buildFail("\t-> Failed to create executable.");
                assert false;
            }
            assert classes != null;

            final List<String> args = new ArrayList<>(classes.length);
            args.add("jar");
            args.add("cfme");
            args.add("../Program.jar");  // gets moved to release later
            args.add("../Manifest.txt"); // gets deleted later

            // find out entry point
            if (entryPoint.equals("--NoMainFound--")) {
                args.add(entryPoint);
            } else {
                File entryPointFile = null;
                for (int i = 0, l = classes.length; i < l; ++i) {
                    final File file = classes[i];
                    if (!file.isDirectory()) {
                        if (file.getName().split("//.")[0].equals(entryPoint + ".class")) { // @Hack
                            entryPointFile = file;
                        }
                    }
                }
                assert entryPointFile != null;
                args.add(entryPointFile.getPath().replace("build\\", "").replace("classes\\", "").replace(".class", "").replace("\\", "."));
                // args.add("foo.Main");
            }

            // classpath
            // args.add("foo/Main.class");
            // args.add("foo/Foo.class");
            for (int i = 0, l = classes.length; i < l; ++i) {
                final File file = classes[i];
                if (!file.isDirectory()) {
                    args.add(file.getPath().replace("build\\", "").replace("classes\\", ""));
                    // System.out.println(file.getPath().replace("build\\", "").replace("classes\\", ""));
                }
            }

            try {
                // @Todo: Check result in case of error
                System.out.println("\t-> Java packages are used.");
                execShellCommand(null, new File("build/classes"), (String[]) args.toArray(String[]::new));
            } catch (final IOException ex) {
               buildFail("->\t Failed to create executable.");
               assert false;
            }
        } else {
            try {
                // @Todo: Check result in case of error
                System.out.println("\t-> No java packages are used.");
                execShellCommand(null, new File("build/classes"), "jar", "cfme", "../Program.jar", "../Manifest.txt", entryPoint, "*.class");
            } catch (final IOException ex) {
               buildFail("->\t Failed to create executable.");
               assert false;
            }
        }

        // @Todo: check if zero
        System.out.printf("\t-> Size of executable is %.3f %s\n", new File("build/Program.jar").length() / 1024.0f, "kb.");
    }

    // @Robustness
    private static void createByteCodeFiles() {
        System.out.println("> Generating readable bytecode files for easier debugging...");

        final File bytecode = new File("build/bytecode");
        if (!bytecode.exists()) {
            if (!bytecode.mkdir()) {
                buildFail("\t-> Failed to create bytecode directory.");
                assert false;
            }
        }

        int numberOfByteCodeInstructions = 0;
        int numberOfMethods = 0;
        int numberOfFields = 0;
        int numberOfNewCalls = 0;
        final File out = new File("bytecode_tmp.txt");
        try {
            if (!out.exists())
                out.createNewFile();

            File[] classes = null;
            try {
                classes = listAllFiles(new File("build/classes"));
            } catch (final IOException ex) {
                buildFail("\t-> Failed to generate readable bytecode files.");
                assert false;
            }
            assert classes != null;

            final List<String> sclasses = new ArrayList<>(classes.length);
            sclasses.add("javap");
            sclasses.add("-c");
            sclasses.add("-p");
            for (final File file : classes) {
                if (!file.isDirectory()) {
                    sclasses.add(file.getAbsolutePath());
                }
            }

            final String result = execShellCommand(out, null, (String[]) sclasses.toArray(String[]::new));

            {
                final String[] lines = result.split(System.lineSeparator());
                for (int i = 0, l = lines.length; i < l; ++i) {
                    final String line = lines[i];
                    if (line.contains(":") && !line.equalsIgnoreCase("Code") && !line.equalsIgnoreCase("table")) { // @Robustness
                        // @Bug: There are still some wrong values here like just number!
                        numberOfByteCodeInstructions += 1;
                        final String stripedLine = line.strip();
                        if (!stripedLine.contains("Code:")) { // @Ugh
                            final String[] arr = line.strip().split(" ");
                            if (arr.length > 1) {
                                final String instruction = arr[1];
                                if (instruction.equals("new")) {
                                    numberOfNewCalls += 1;
                                } else if (instruction.contains("invoke")) {
                                    numberOfMethods += 1;
                                } else if (instruction.equals("putfield")) {
                                    numberOfFields += 1;
                                }
                            }
                        }

                    }
                }
            }

            final String[] files = result.split("Compiled from");
            for (int i = 0, l = files.length; i < l; ++i) {
                final String file = files[i];
                if (file.strip().isEmpty())
                    continue;

                final String[] lines = file.split(System.lineSeparator());

                String fileName = null;
                for (int j = 0; j < lines.length; ++j) {
                    if (lines[j].contains("class") || lines[j].contains("interface")) {
                        final String[] words = lines[j].split(" ");
                        for (int k = 0; k < words.length; ++k) {
                            if (words[k].equals("class") || words[k].equals("interface")) {
                                fileName = words[k + 1] + ".bytecode";
                                break;
                            }
                        }
                        break;
                    }
                }

                if (fileName == null)
                    continue;

                final StringBuilder content = new StringBuilder();
                for (int j = 1; j < lines.length; ++j) {
                    content.append(lines[j]).append("\n");
                }
                writeToFile("build/bytecode/" + fileName, content.toString());
            }
            System.out.printf("\t-> Total of %d bytecode instructions.\n", numberOfByteCodeInstructions);
            System.out.printf("\t-> Total of %d function calls.\n", numberOfMethods);
            System.out.printf("\t-> Total of %d fields.\n", numberOfFields);
            System.out.printf("\t-> Total of %d 'new' calls (likely resulting in heap allocations).\n", numberOfNewCalls);
        } catch (final IOException ex) {
            System.err.println("\t-> Failed to generate readable bytecode files.");
        } finally {
            if (!out.delete()) {
                System.err.println("\t-> Failed to delete bytecode_tmp.txt file.");
            }
        }
    }

    private static void createClassFiles() {
        try {
            System.out.println("> Parsing and emitting bytecode instructions...");

            final char classpathSeparator = System.getProperty("os.name").toLowerCase().contains("win") ? ';' : ':';
            int numberOfClassFiles = 0;
            int numberOfAnonymousClassFiles = 0;

            final File libs = new File("libs");
            final StringBuilder classpath = new StringBuilder(128);
            if (libs.exists()) {
                final File[] jars = libs.listFiles();
                for (int i = 0, l = jars.length; i < l; ++i) {
                    classpath.append("libs/");
                    classpath.append(jars[i].getName()).append(classpathSeparator);
                }
                // class path must not end with the delimitter
                assert classpath.toString().charAt(classpath.toString().length() - 1) == classpathSeparator;
                classpath.deleteCharAt(classpath.toString().length() - 1);
            }
            final File classes = new File("build/classes");
            if (!classes.exists()) {
                if (!classes.mkdir()) {
                    buildFail("\t-> Failed to create classes directory.");
                    assert false;
                }
            }
            String result = null;
            if (classpath.toString().isEmpty()) {
                result = execShellCommand(null, null, "javac", "@sources.txt", "-g", "-d", "build/classes");
            } else {
                result = execShellCommand(null, null, "javac", "-classpath", classpath.toString(), "@sources.txt", "-g", "-d", "build/classes");
            }
            assert result != null;

            // @TODO: Perhaps it would be more readable if we only display N number of errors
            // and then exit? Or does that make some errors 'unsolvable' ?
            // @Speed @Robustness: Check for exit value instead of doing a string check!
            if (result.contains("error")) {
                System.out.println("\t-> COMPILATION ERROR");
                System.out.println();
                System.out.println("############################");
                System.out.println("ERRORS");
                System.out.println();
                System.out.println(result);
                System.out.println("############################");
                System.out.println("BUILD FAILED");
                System.exit(-1);
            } else {
                try {
                    final File[] classFiles = listAllFiles(new File("build/classes"));
                    for (final File file : classFiles) {
                        if (!file.getName().endsWith(".class"))
                            continue;
                        if (file.getName().contains("$")) {
                            numberOfAnonymousClassFiles += 1;
                        } else {
                            numberOfClassFiles += 1;
                        }
                    }
                } catch (final IOException ex) {
                    buildFail("\t-> Failed to count class files.");
                    assert false;
                }

                System.out.printf("\t-> Created %d class files.\n", numberOfClassFiles);
                System.out.printf("\t-> Created %d anonymous class files.\n", numberOfAnonymousClassFiles);
            }
        } catch (final IOException ex) {
            buildFail("\t-> Failed to emit bytecode.");
            assert false;
        }
    }

    private static void analyzeSourceTree() {
        System.out.println("> Analyzing your source tree...");
        final File src = new File("src");
        if (!src.exists()) {
            buildFail("\t-> No src directory found.");
            assert false;
        }

        int sourceFileCounter = 0;
        int loc = 0;
        int numberOfEntryPoints = 0;

        File[] files = null;
        try {
            files = listAllFiles(src);
        } catch (final IOException ex) {
            buildFail("\t-> Failed to analyze your source tree.");
            assert false;
        }
        assert files != null;

        final StringBuilder sbuffer = new StringBuilder();
        for (int i = 0, l = files.length; i < l; ++i) {
            final File file = files[i];
            if (!file.getAbsolutePath().endsWith(".java"))
                continue;
            sbuffer.append(file.getAbsolutePath()).append(System.lineSeparator());
            sourceFileCounter += 1;

            final StringBuilder locBuffer = new StringBuilder(1024);
            readFileIntoMemory(locBuffer, file);
            if (locBuffer.toString().contains("public static void main(")) { // @Robustness
                entryPoint = file.getName().split("\\.")[0];
                numberOfEntryPoints += 1;
            }
            loc += locBuffer.toString().split("\n").length;
        }

        writeToFile("sources.txt", sbuffer.toString());
        System.out.printf("\t-> Total of %d source files found.\n", sourceFileCounter);
        System.out.printf("\t-> Total lines of code are %d (including whitespaces and comments).\n", loc);
        if (entryPoint != null) {
            assert numberOfEntryPoints >= 1;
            System.out.printf("\t-> Entry point is '%s'.\n", entryPoint);
            if (numberOfEntryPoints > 1) {
                System.out.printf("\t-> Note that there are more than one entry point in your program.\n");
            }
        } else {
            // this is fine because we could have a library which normally does not have an entry point
            System.out.printf("\t-> No entry point found.\n");
            entryPoint = "--NoMainFound--";
        }
    }

    private static void cleanBuildDirectory() {
        final File cwd = new File("build");
        if (!cwd.exists()) {
            System.out.println("> Creating build directory...");
            if (cwd.mkdir()) {
                System.out.println("\t-> Creation successfully.");
            } else {
                buildFail("\t-> Failed to create build directory.");
                assert false;
            }
        } else {
            System.out.println("> Cleaning build directory...");

            int deletionCounter = 0;

            File[] files = null;
            try {
                files = listAllFiles(cwd);
            } catch (final IOException ex) {
                buildFail("\t-> Failed to clean the build directory.");
                assert false;
            }
            assert files != null;

            for (int i = 0, l = files.length; i < l; ++i) {
                final File file = files[i];
                if (file.delete()) {
                    deletionCounter += 1;
                }
            }

            if (deletionCounter == 0) {
                System.out.println("\t-> Nothing to delete.");
            } else {
                System.out.printf("\t-> Deleted %d files.\n", deletionCounter);
            }
        }
    }

    public static void main(final String[] args) {
        if (args.length != 0) {
            System.out.println("Program does not take any arguments.");
            return;
        }
        assert args.length == 0;

        System.out.println("===========");
        System.out.println("jbp v0.1.0");
        System.out.println("===========");
        System.out.println();
        startNanoTime = System.nanoTime();
        {
            cleanBuildDirectory();
            System.out.println();
            analyzeSourceTree();
            System.out.println();
            createClassFiles();
            System.out.println();
            createByteCodeFiles();
            System.out.println();
            createExecutable();
            System.out.println();
            packageRelease();
            System.out.println();
            deleteSourcesFiles();
        }

        final long elapsedMillis = (System.nanoTime() - startNanoTime) / 1000000;
        System.out.println();
        System.out.println("BUILD SUCCESSFULL");
        System.out.println("-----------------");
        System.out.println("TOTAL BUILD TIME : " + elapsedMillis / 1000.0 + " SECONDS");
    }
}
