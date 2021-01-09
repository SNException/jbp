/* ---------------------------------------------------------------------

    jbp - Build tool for Java programs.

    Copyright (C) 2020-2021 Niklas Schultz
    All rights reserved.

    This software source file is licensed under the terms of MIT license.
    For details, please read the LICENSE file.

--------------------------------------------------------------------- */

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
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class jbp {

    private static long startNanoTime;

    // config
    private static String programName     = null;
    private static String entryPoint      = null;
    private static String mode            = null;
    private static String encoding        = null;
    private static String doc             = null;
    private static String byteCodeDetails = null;
    private static String runAfterBuild   = null;
    private static String simpleOutput    = null;
    private static String log             = null;
    private static String compiler        = null;
    private static String bytecodeViewer  = null;
    private static String jvm             = null;
    private static String jar             = null;
    private static String javadoc         = null;

    // We have a boolean here for performance reasons. Otherwise we would
    // have to check the string with 'equalsIgnoreCase()' all the time.
    private static boolean simpleOutputBool = false;

    private static void stdout(final String str) {
        if (str == null && !simpleOutputBool) {
            System.out.println();
        } else {
            if (!simpleOutputBool) {
                if (str.endsWith("\n"))
                    System.out.print(str);
                else
                    System.out.println(str);
            }
        }
    }

    private static File[] listAllFiles(final File dir) throws IOException {
        assert dir != null;
        assert dir.isDirectory();

        try (final Stream<Path> stream = Files.walk(dir.toPath(), Integer.MAX_VALUE)) {
            final StringBuilder sbuffer = new StringBuilder();
            final List<String> files = stream.map(String::valueOf).sorted().collect(Collectors.toList());
            final File[] result = new File[files.size()];

            assert files.size() == result.length;
            for (int i = 0, l = result.length; i < l; ++i)
                result[i] = new File(files.get(i));
            return result;
        }
    }

    private static void buildFail(final String reason) {
        assert reason != null;

        // TODO(nschultz): We might still have this file.
        // Maybe using file.deleteOnExit() would be more appropriate?
        new File("sources.txt").delete();

        System.out.println(reason);
        System.out.println();
        System.out.println("BUILD FAILED");
        System.exit(-1);
    }

    private static Object[] execShellCommand(final File out, final File cwd, final boolean print, final String...args) throws IOException {
        assert args != null;

        final Object[] result = new Object[2];

        final ProcessBuilder builder = new ProcessBuilder(args);
        if (cwd != null)
            builder.directory(cwd);

        builder.redirectErrorStream(true);
        if (out != null) {
	        builder.redirectOutput(out);
            try {
                result[1] = builder.start().waitFor();
            } catch(final InterruptedException ex) {
                assert false;
            }
            final StringBuilder buffer = new StringBuilder(4096);
            readFileIntoMemory(buffer, out);
            result[0] = buffer.toString();
            return result;
        } else {
            final Process process = builder.start();
            final BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            final StringBuilder sb = new StringBuilder();
            while (true) {
                line = reader.readLine();
                if (line == null)
                    break;

                assert line != null;
                if (print)
                    System.out.println(line);
                sb.append(line).append("\n");
            }
            result[0] = sb.toString();
            try {
                result[1] = process.waitFor();
            } catch (final InterruptedException ex) {
                assert false;
            }
            return result;
        }
    }

    private static void writeToFile(String file, final String data) {
        writeToFile(file, data, false);
    }

    private static void writeToFile(String file, final String data, final boolean append) {
        assert file != null;
        assert data != null;

        // Remove illegal characters, which can occur when having a file which uses generics
        file = file.replace("<", "").replace(">", "");

        try (final OutputStream out = new FileOutputStream(new File(file), append)) {
            out.write(data.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (final IOException ex) {
            stdout(String.format("\t-> Failed to write file '%s'\n", file));
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
            stdout(String.format("Failed to read file %s", file.getName()));
        }
    }

    private static void log(final double seconds) {
        final File logFile = new File("jbp.log");
        if (!logFile.exists()) {
            try {
                if (!logFile.createNewFile()) {
                    stdout("Failed to create jbp.log file.");
                    return;
                }
            } catch (final IOException ex) {
                stdout("Failed to create jbp.log file.");
                return;
            }
        }

        final SimpleDateFormat parser = new SimpleDateFormat("DD.MM.yyyy-hh:mm:ss");
        final String date = parser.format(new Date());
        if (seconds == -1) { // The build has failed if we get '-1'.
            writeToFile(logFile.getAbsolutePath(), date + "-> BUILD FAILED\n", true);
        } else {
            writeToFile(logFile.getAbsolutePath(), date + "-> BUILD SUCCESSFULL TOOK " + seconds + " SECONDS\n", true);
        }
    }

    private static void deleteSourcesFiles() {
        final File sources = new File("sources.txt");
        if (sources.exists()) {
            if (!sources.delete()) {
                System.out.println();
                stdout("Failed to delete sources.txt file.");
            }
        }
    }

    private static void packageRelease() {
        stdout("> Packaging release...");
        final File release = new File("build/release");
        if (!release.exists()) {
            if (!release.mkdir()) {
                buildFail("\t-> Failed to create release directory.");
                assert false;
            }
        }

        try {
            Files.copy(Paths.get("build/" + programName), new File("build/release/" + programName).toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (final IOException ex) {
            buildFail("\t-> Failed to copy executable to release directory.");
            assert false;
        }

        final File libs = new File("libs");
        if (!libs.exists()) {
            stdout("\t-> Your program does not use any libraries.");
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
                if (jars.length == 1)
                    stdout("\t-> Program uses " + jars.length + " library.");
                else
                    stdout("\t-> Program uses " + jars.length + " libraries.");
            }
        }

        new File("build/Manifest.txt").delete();
        new File("build/" + programName).delete();

        // TODO(nschultz): Directory structure will not get copied for 'res' directory.
        final File res = new File("res");

        if (res.exists()) {
            final File resTarget = new File("build/release/res");
            if (!resTarget.exists()) {
                if (!resTarget.mkdir()) {
                    buildFail("\t-> Failed to create resource directory for the release.");
                    assert false;
                }
            }
            try {
                final File[] resFiles = listAllFiles(new File("res"));
                for (int i = 0, l = resFiles.length; i < l; ++i) {
                    final File resFile = resFiles[i];
                    if (resFile.isDirectory())
                        continue;
                    Files.copy(resFile.toPath(), new File("build/release/res/" + resFile.getName()).toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                stdout("\t-> Copied all resources to the release.");
            } catch (final IOException ex) {
                buildFail("\t-> Failed to all copy resources.");
                assert false;
            }
        } else {
            stdout("\t-> Program does not use any resource files.");
        }

        try {
            final long sizeOfReleaseInBytes = Files.walk(release.toPath()).mapToLong(p -> p.toFile().length()).sum();
            stdout(String.format("\t-> The full size of your release is %.3f %s\n", sizeOfReleaseInBytes / 1024.0f, "kb."));
        } catch (final IOException ex) {
            System.out.println("\t -> Failed to calculate size of your release.");
            // lets not fail the entire build though, that seems dumb.
        }
    }

    private static void createExecutable() {
        stdout("> Building executable...");

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
            // class path must not end with the delimitter ' '.
            assert classpath.toString().charAt(classpath.toString().length() - 1) == ' ';
            classpath.deleteCharAt(classpath.toString().length() - 1);
            assert classpath.toString().charAt(classpath.toString().length() - 1) != ' ';

            mfData.append("Class-Path: " + classpath.toString()).append(System.lineSeparator());
        }

        String javacVersion = null;
        try {
            if (compiler.equalsIgnoreCase("---")) {
                javacVersion = (String) execShellCommand(null, null, false, "javac", "-version")[0];
            } else {
                javacVersion = (String) execShellCommand(null, null, false, "\"" + compiler + "\"", "-version")[0];
            }
        } catch (final IOException ex) {
            javacVersion = "java";
        }
        assert javacVersion != null;
        mfData.append("Created-By: ").append(javacVersion).append(System.lineSeparator());
        writeToFile("build/Manifest.txt", mfData.toString());

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
            if (jar.equalsIgnoreCase("---")) {
                args.add("jar");
            } else {
                args.add("\"" + jar + "\"");
            }
            args.add("cfme");
            args.add("../" + programName);  // gets moved to release later
            args.add("../Manifest.txt");    // gets deleted later

            // find out entry point
            if (entryPoint.equals("--NoMainFound--")) {
                args.add(entryPoint);
            } else {
                File entryPointFile = null;
                for (int i = 0, l = classes.length; i < l; ++i) {
                    final File file = classes[i];
                    if (!file.isDirectory()) {
                        if (file.getName().split("//.")[0].equals(entryPoint + ".class"))
                            entryPointFile = file;
                    }
                }
                if (entryPointFile == null) {
                    buildFail(String.format("\t-> Main class '%s' does not exist.", entryPoint));
                }
                args.add(entryPointFile.getPath().replace("build\\", "").replace("classes\\", "").replace(".class", "").replace("\\", "."));
            }

            // classpath
            for (int i = 0, l = classes.length; i < l; ++i) {
                final File file = classes[i];
                if (!file.isDirectory()) {
                    args.add(file.getPath().replace("build\\", "").replace("classes\\", ""));
                }
            }

            try {
                // TODO(nschultz): Check result in case of error
                stdout("\t-> Java packages are used.");
                execShellCommand(null, new File("build/classes"), false, (String[]) args.toArray(String[]::new));
            } catch (final IOException ex) {
               buildFail("->\t Failed to create executable.");
               assert false;
            }
        } else {
            try {
                // TODO(nschultz): Check result in case of error
                stdout("\t-> No java packages are used.");
                if (jar.equalsIgnoreCase("---")) {
                    execShellCommand(null, new File("build/classes"), false, "jar", "cfme", "../" + programName, "../Manifest.txt", entryPoint, "*.class");
                } else {
                    execShellCommand(null, new File("build/classes"), false, "\"" + jar + "\"", "cfme", "../" + programName, "../Manifest.txt", entryPoint, "*.class");
                }
            } catch (final IOException ex) {
               buildFail("->\t Failed to create executable.");
               assert false;
            }
        }

        final File program = new File("build/" + programName);
        if (program.length() == 0) {
            buildFail("\t-> Failed to create executable.");
            assert false;
        }

        stdout(String.format("\t-> Size of executable is %.3f %s\n", program.length() / 1024.0f, "kb."));
    }

    private static void createByteCodeFiles() {
        stdout("> Generating readable bytecode files for easier debugging...");

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
            if (bytecodeViewer.equalsIgnoreCase("---")) {
                sclasses.add("javap");
            } else {
                if (new File(bytecodeViewer).exists()) {
                    sclasses.add("\"" + bytecodeViewer + "\"");
                } else {
                    buildFail("Specified bytecode viewer executable does not exist.");
                    assert false;
                }
            }
            sclasses.add("-c");
            sclasses.add("-p");
            for (final File file : classes) {
                if (!file.isDirectory())
                    sclasses.add(file.getAbsolutePath());
            }

            final Object[] result = execShellCommand(out, null, false, (String[]) sclasses.toArray(String[]::new));

            {
                final String[] lines = ((String) result[0]).split(System.lineSeparator());
                for (int i = 0, l = lines.length; i < l; ++i) {
                    final String line = lines[i];
                    if (line.contains(":") && !line.equalsIgnoreCase("Code") && !line.equalsIgnoreCase("table")) {
                        numberOfByteCodeInstructions += 1;
                        final String stripedLine = line.strip();
                        if (!stripedLine.contains("Code:")) {
                            final String[] arr = line.strip().split(" ");
                            if (arr.length > 1) {
                                final String instruction = arr[1];
                                if (instruction.equals("new"))
                                    numberOfNewCalls += 1;
                                else if (instruction.contains("invoke"))
                                    numberOfMethods += 1;
                                else if (instruction.equals("putfield"))
                                    numberOfFields += 1;
                            }
                        }

                    }
                }
            }

            final String[] files = ((String) result[0]).split("Compiled from");
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
                for (int j = 1; j < lines.length; ++j)
                    content.append(lines[j]).append("\n");
                writeToFile("build/bytecode/" + fileName, content.toString());
            }
            stdout(String.format("\t-> Total of %d bytecode instructions.\n", numberOfByteCodeInstructions));
            stdout(String.format("\t-> Total of %d function calls.\n", numberOfMethods));
            stdout(String.format("\t-> Total of %d fields.\n", numberOfFields));
            stdout(String.format("\t-> Total of %d 'new' calls (likely resulting in heap allocations).\n", numberOfNewCalls));
        } catch (final IOException ex) {
            stdout("\t-> Failed to generate readable bytecode files.");
        } finally {
            if (!out.delete())
                stdout("\t-> Failed to delete bytecode_tmp.txt file.");
        }
    }

    private static void createClassFiles() {
        try {
            stdout(String.format("> Parsing and emitting bytecode instructions (%s)...\n", mode));

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
                assert classpath.toString().charAt(classpath.toString().length() - 1) != classpathSeparator;
            }
            final File classes = new File("build/classes");
            if (!classes.exists()) {
                if (!classes.mkdir()) {
                    buildFail("\t-> Failed to create classes directory.");
                    assert false;
                }
            }
            Object[] result = null;

            // For now we print a maximum number of 5 errors (-Xmaxerrs 5)
            // We also disable warning (-nowarn) because they are hardly every useful (execpt deprecated warnings)

            // TODO(nschultz): We only get warnings about deprecation displayed iff also at the same time
            // encounter an (or multiple) compilation errors. Otherwise warnings will not get shown
            // to the user. This is not what we want, I think.
            {
                // TODO(nschultz): There seems to be javax.tool.JavaCompiler class which can do the compile while giving me more control (we can format nice error message more easily)
                // Check whether we can use that instead of relying on javac in the path.
                String debugFlag = null;
                if (mode.equalsIgnoreCase("debug")) {
                    debugFlag = "-g";
                } else if (mode.equalsIgnoreCase("release")) {
                    debugFlag = "-g:none";
                } else {
                    assert false;
                }
                if (classpath.toString().isEmpty()) { // we have NO libraries
                    if (compiler.equalsIgnoreCase("---")) {
                        result = execShellCommand(null, null, false, "javac", "@sources.txt", "-Xdiags:verbose", "-Xlint:deprecation", "-Xmaxerrs", "5", "-nowarn", debugFlag, "-d", "build/classes", "-encoding", encoding);
                    } else {
                        final File compilerExecutable = new File(compiler);
                        if (!compilerExecutable.exists()) {
                            buildFail("\t-> Specified compiler executable does not exist.");
                            assert false;
                        } else {
                            result = execShellCommand(null, null, false, compiler, "@sources.txt", "-Xdiags:verbose", "-Xlint:deprecation", "-Xmaxerrs", "5", "-nowarn", debugFlag, "-d", "build/classes", "-encoding", encoding);
                        }
                    }
                } else { // we have libraries; need to specify classpath now
                    if (compiler.equalsIgnoreCase("---")) {
                        final File compilerExecutable = new File(compiler);
                        if (!compilerExecutable.exists()) {
                            buildFail("\t-> Specified compiler executable does not exist.");
                            assert false;
                        }
                        result = execShellCommand(null, null, false, "javac", "-classpath", classpath.toString(), "@sources.txt", "-Xdiags:verbose", "-Xlint:deprecation", "-Xmaxerrs", "5", "-nowarn", debugFlag, "-d", "build/classes", "-encoding", encoding);
                    } else {
                        result = execShellCommand(null, null, false, compiler, "-classpath", classpath.toString(), "@sources.txt", "-Xdiags:verbose", "-Xlint:deprecation", "-Xmaxerrs", "5", "-nowarn", debugFlag, "-d", "build/classes", "-encoding", encoding);
                    }
                }
            }
            assert result != null;

            if (((int) result[1]) != 0) {
                stdout("\t-> COMPILATION ERROR");
                stdout(null);
                System.out.println("############################");
                System.out.println("ERRORS");
                System.out.println();
                System.out.println(result[0]);
                System.out.println("############################");
                System.out.println("BUILD FAILED");

                // TODO(nschultz): We might still have these files. However this soulution is rather hacky.
                new File("sources.txt").delete();

                if (log.equalsIgnoreCase("yes"))
                    log(-1);

                System.exit(-1);
            } else {
                try {
                    final File[] classFiles = listAllFiles(new File("build/classes"));
                    for (final File file : classFiles) {
                        if (!file.getName().endsWith(".class"))
                            continue;

                        if (file.getName().contains("$"))
                            numberOfAnonymousClassFiles += 1;
                        else
                            numberOfClassFiles += 1;
                    }
                } catch (final IOException ex) {
                    buildFail("\t-> Failed to count class files.");
                    assert false;
                }

                stdout(String.format("\t-> Created %d class files.\n", numberOfClassFiles));
                stdout(String.format("\t-> Created %d anonymous class files.\n", numberOfAnonymousClassFiles));
            }
        } catch (final IOException ex) {
            buildFail("\t-> Failed to emit bytecode.");
            assert false;
        }
    }

    private static void generateDocumentation() {
        stdout("> Generating JavaDoc for your project...");
        final File javadocDir = new File("build/documentation");
        if (!javadocDir.exists()) {
            if (!javadocDir.mkdir()) {
                buildFail("\t-> Failed to create javadoc directory.");
                assert false;
            }
        }
        try {
            Object[] result = null;
            if (javadoc.equalsIgnoreCase("---")) {
                result = execShellCommand(null, null, false, "javadoc", "@sources.txt", "-d", "build/documentation");
            } else {
                result = execShellCommand(null, null, false, "\"" + javadoc + "\"", "@sources.txt", "-d", "build/documentation");
            }
            assert result != null;
            if (((int) result[1]) != 0) {
                stdout(result[0].toString());
                buildFail("\t-> Failed to generate documentation.");
                assert false;
            } else {
                stdout("\t-> Documentation generated.");
            }
        } catch (final IOException ex) {
            buildFail("\t-> Failed to generate documentation.");
            assert false;
        }
    }

    private static void analyzeSourceTree() {
        stdout("> Analyzing your source tree...");
        final File src = new File("src");
        if (!src.exists()) {
            buildFail("\t-> No src directory found. Please create one.");
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
            if (entryPoint == null) {
                if (locBuffer.toString().contains("public static void main(")) {
                    entryPoint = file.getName().split("\\.")[0];
                    numberOfEntryPoints += 1;
                }
            }
            if (!simpleOutputBool)
                loc += locBuffer.toString().split("\n").length;
        }

        writeToFile("sources.txt", sbuffer.toString());
        if (!simpleOutputBool) {
            stdout(String.format("\t-> Total of %d source files found.\n", sourceFileCounter));
            stdout(String.format("\t-> Total lines of code are %d (including whitespaces and comments).\n", loc));
        }
        if (entryPoint != null) {
            assert numberOfEntryPoints >= 1;
            stdout(String.format("\t-> Entry point is '%s'.\n", entryPoint));
            if (numberOfEntryPoints > 1) {
                stdout("\t-> Note that there are more than one entry point in your program.\n");
            }
        } else {
            // this is fine because we could have a library which normally does not have an entry point
            stdout("\t-> No entry point found.\n");
            entryPoint = "--NoMainFound--";
        }
    }

    private static void cleanBuildDirectory() {
        final File cwd = new File("build");
        if (!cwd.exists()) {
            stdout("> Creating build directory...");
            if (cwd.mkdir()) {
                stdout("\t-> Creation successfully.");
            } else {
                buildFail("\t-> Failed to create build directory.");
                assert false;
            }
        } else {
            stdout("> Cleaning build directory...");

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
                if (file.delete())
                    deletionCounter += 1;
            }

            // TODO(nschultz): Lets delete every directory (except build itself) aswell.

            if (deletionCounter == 0)
                stdout("\t-> Nothing to delete.");
            else
                stdout(String.format("\t-> Deleted %d files.\n", deletionCounter));
        }
    }

    private static void loadConfiguration() {
        final File configFile = new File("jbp.config");
        if (configFile.exists()) {
            final StringBuilder config = new StringBuilder();
            readFileIntoMemory(config, configFile);
            final String[] configLines = config.toString().split("\n");
            final Map<String, String> configMap = new LinkedHashMap<>();
            for (final String configLine : configLines) {
                if (configLine.strip().isEmpty())
                    continue;

                final String[] entry = configLine.strip().split("=");
                if (entry.length != 2) {
                    buildFail("Invalid config file entry."); // TODO(nschultz): Improve error message
                    assert false;
                }

                final String lhs = entry[0].strip();
                final String rhs = entry[1].strip();
                configMap.put(lhs, rhs);
            }

            programName = configMap.get("ProgramName");
            entryPoint = configMap.get("EntryPoint");
            mode = configMap.get("Mode");
            if (mode != null) { // null would have been fine
                if (!mode.equalsIgnoreCase("debug") && !mode.equalsIgnoreCase("release")) {
                    buildFail("Mode can only be set to 'debug' or 'release'.");
                    assert false;
                }
            }
            encoding = configMap.get("Encoding");

            doc = configMap.get("Documentation");
            if (doc != null) { // null would have been fine
                if (!doc.equalsIgnoreCase("yes") && !doc.equalsIgnoreCase("no")) {
                    buildFail("Documentation can only be set to 'yes' or 'no'.");
                    assert false;
                }
            }
            byteCodeDetails = configMap.get("ByteCodeDetails");
            if (byteCodeDetails != null) { // null would have been fine
                if (!byteCodeDetails.equalsIgnoreCase("yes") && !byteCodeDetails.equalsIgnoreCase("no")) {
                    buildFail("ByteCodeDetails can only be set to 'yes' or 'no'.");
                    assert false;
                }
            }
            runAfterBuild = configMap.get("RunAfterBuild");
            if (runAfterBuild != null) { // null would have been fine
                if (!runAfterBuild.equalsIgnoreCase("yes") && !runAfterBuild.equalsIgnoreCase("no")) {
                    buildFail("RunAfterBuild can only be set to 'yes' or 'no'.");
                    assert false;
                }
            }
            simpleOutput = configMap.get("SimpleOutput");
            if (simpleOutput != null) { // null would have been fine
                if (!simpleOutput.equalsIgnoreCase("yes") && !simpleOutput.equalsIgnoreCase("no")) {
                    buildFail("SimpleOutput can only be set to 'yes' or 'no'.");
                    assert false;
                }
            }
            log = configMap.get("Log");
            if (log != null) { // null would have been fine
                if (!log.equalsIgnoreCase("yes") && !log.equalsIgnoreCase("no")) {
                    buildFail("Log can only be set to 'yes' or 'no'.");
                    assert false;
                }
            }
            compiler = configMap.get("Compiler");
            bytecodeViewer = configMap.get("Bytecodeviewer");
            jvm = configMap.get("JVM");
            jar = configMap.get("Jar");
            javadoc = configMap.get("Javadoc");
        }

        // handle values which have not been set yet
        programName = programName == null ? "Program.jar" : programName;
        entryPoint = entryPoint == null || entryPoint.equalsIgnoreCase("---") ? null : entryPoint; // if null will get set when analyzing the source tree
        mode = mode == null ? "debug" : mode;
        encoding = encoding == null ? "UTF-8" : encoding;
        doc = doc == null ? "no" : doc;
        byteCodeDetails = byteCodeDetails == null ? "yes" : byteCodeDetails;
        runAfterBuild = runAfterBuild == null ? "no" : runAfterBuild;
        simpleOutput = simpleOutput == null ? "no" : simpleOutput;
        log = log == null ? "no" : log;
        compiler = compiler == null || compiler.equalsIgnoreCase("---") ? null : compiler;
        if (compiler == null)
            compiler = "---";
        bytecodeViewer = bytecodeViewer == null || bytecodeViewer.equalsIgnoreCase("---") ? null : bytecodeViewer;
        if (bytecodeViewer == null)
            bytecodeViewer = "---";
        jvm = jvm == null || jvm.equalsIgnoreCase("---") ? null : jvm;
        if (jvm == null)
            jvm = "---";
        jar = jar == null || jar.equalsIgnoreCase("---") ? null : jar;
        if (jar == null)
            jar = "---";
        javadoc = javadoc == null || javadoc.equalsIgnoreCase("---") ? null : javadoc;
        if (javadoc == null)
            javadoc = "---";

        simpleOutputBool = simpleOutput.equalsIgnoreCase("Yes");
    }

    public static void main(final String[] args) {
        if (args.length == 0) {
            startNanoTime = System.nanoTime();
            {
                loadConfiguration();
                if (!simpleOutputBool) {
                    if (compiler.equalsIgnoreCase("---")) {
                        System.out.println("Using your global compiler executable.");
                    } else {
                        System.out.println("Using following javac executable: " + compiler);
                    }
                    if (bytecodeViewer.equalsIgnoreCase("---")) {
                        System.out.println("Using your global bytecode viewer executable.");
                    } else {
                        System.out.println("Using following javap executable: " + bytecodeViewer);
                    }
                    if (jar.equalsIgnoreCase("---")) {
                        System.out.println("Using your global jar executable.");
                    } else {
                        System.out.println("Using following jar executable: " + jar);
                    }
                    if (jvm.equalsIgnoreCase("---")) {
                        System.out.println("Using your global JVM executable.");
                    } else {
                        System.out.println("Using the following JVM executable: " + jvm);
                    }
                    if (javadoc.equalsIgnoreCase("---")) {
                        System.out.println("Using your global javadoc executable.");
                    } else {
                        System.out.println("Using following javadoc executable: " + javadoc);
                    }
                }
                if (simpleOutputBool) {
                    System.out.println("Building project...");
                    System.out.println();
                } else {
                    stdout(null);
                }
                cleanBuildDirectory();
                stdout(null);
                analyzeSourceTree();
                stdout(null);
                if (doc.equalsIgnoreCase("yes")) {
                    generateDocumentation();
                    stdout(null);
                }
                createClassFiles();
                stdout(null);
                if (byteCodeDetails.equalsIgnoreCase("yes")) {
                    createByteCodeFiles();
                    stdout(null);
                }
                createExecutable();
                stdout(null);
                packageRelease();
                stdout(null);
                deleteSourcesFiles();
            }

            final long elapsedMillis = (System.nanoTime() - startNanoTime) / 1000000;
            stdout(null);
            System.out.println("BUILD SUCCESSFULL");
            stdout("-----------------");
            System.out.println("TOTAL BUILD TIME : " + elapsedMillis / 1000.0 + " SECONDS");

            if (log.equalsIgnoreCase("yes"))
                log(elapsedMillis / 1000.0);

            if (runAfterBuild.equalsIgnoreCase("yes")) {
                System.out.println();
                System.out.println();
                System.out.println("Running your program after the build...");
                System.out.println("----------");
                try {
                    // TODO(nschultz): We do not yet enable reacting to input requests via stdout from the started process (e.g java.util.Scanner)
                    Object[] result = null;
                    if (jvm.equalsIgnoreCase("---")) {
                        result = execShellCommand(null, new File("build/release"), true, "java", "-ea", "-jar", programName);
                    } else {
                        if (new File(jvm).exists()) {
                            result = execShellCommand(null, new File("build/release"), true, "\"" + jvm + "\"", "-ea", "-jar", programName);
                        } else {
                            buildFail("Specified jvm executable does not exist.");
                            assert false;
                        }
                    }
                    assert result != null;
                    if ((int) result[1] != 0)
                        System.out.println("Failed to run your program.");
                } catch (final IOException ex) {
                    System.out.printf("Failed to run your program because of '%s'\n", ex.getMessage());
                }
            }
        } else if (args.length == 1) {
            final String arg = args[0];
            if (arg.equalsIgnoreCase("--version")) {
                System.out.println("v0.17.0");
            } else if (arg.equalsIgnoreCase("--help")) {
                System.out.println("jbp (just build please) is a build tool for java projects. - Niklas Schultz");
                System.out.println();
                System.out.println("Simply execute this file in your root project directory to execute a full build.");
                System.out.println("In case you wish to change the build configuration, you only need to create a 'jbp.config' file and change them there.");
                System.out.println();
                System.out.println("Example config file:");
                System.out.println("--------------------");
                System.out.println("ProgramName = Program.jar");
                System.out.println("EntryPoint = ---");
                System.out.println("Mode = debug");
                System.out.println("Encoding = UTF-8");
                System.out.println("Documentation = No");
                System.out.println("ByteCodeDetails = Yes");
                System.out.println("RunAfterBuild = No");
                System.out.println("SimpleOutput = No");
                System.out.println("Log = No");
                System.out.println("Compiler = ---");
                System.out.println("Bytecodeviewer = ---");
                System.out.println("JVM = ---");
                System.out.println("Jar = ---");
                System.out.println("Javadoc = ---");
            } else {
                System.out.println("Invalid arguments.");
                System.out.println("Argument can either be '--version' or '--help'");
                System.exit(-1);
            }
        } else {
            System.out.println("Invalid amount of arguments.");
            System.out.println("Argument can either be '--version' or '--help'");
            System.exit(-1);
        }
    }
}
