/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.maven.plugin.surefire.util;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.maven.api.DependencyScope;
import org.apache.maven.api.DownloadedDependency;
import org.apache.maven.plugin.surefire.StubDependency;
import org.apache.maven.surefire.api.testset.TestListResolver;
import org.apache.maven.surefire.api.util.ScanResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * @author Aslak Knutsen
 */
public class DependenciesScannerTest {

    @TempDir
    Path tempFolder;

    @Test
    public void testLocateTestClasses() throws Exception {
        File testFile =
                writeTestFile("DependenciesScannerTest-tests.jar", "org/test/TestA.class", "org/test/TestB.class");

        // use target as people can configure ide to compile in an other place than maven
        StubDependency artifact = new StubDependency(
                "org.surefire.dependency", "test-jar", "1.0", "jar", DependencyScope.forId("test"), "tests");
        artifact.setPath(testFile.toPath());

        List<String> scanDependencies = new ArrayList<>();
        scanDependencies.add("org.surefire.dependency:test-jar");

        List<String> include = new ArrayList<>();
        include.add("**/*A.java");
        List<String> exclude = new ArrayList<>();

        List<File> dependenciesToScan = filterArtifactsAsFiles(scanDependencies, Collections.singletonList(artifact));

        DependencyScanner scanner = new DependencyScanner(dependenciesToScan, new TestListResolver(include, exclude));

        ScanResult classNames = scanner.scan();
        assertNotNull(classNames);
        assertEquals(1, classNames.size());

        Map<String, String> props = new HashMap<>();
        classNames.writeTo(props);
        assertEquals(1, props.size());
    }

    /**
     * Test for artifact with classifier
     */
    @Test
    public void testLocateTestClassesFromArtifactWithClassifier() throws Exception {
        File testJarFile = writeTestFile(
                "DependenciesScannerTest2-1.0-tests-jdk15.jar", "org/test/TestA.class",
                "org/test/other/TestAA.class", "org/test/TestB.class");
        StubDependency testArtifact = new StubDependency(
                "org.surefire.dependency",
                "dependent-artifact2",
                "1.0",
                "jar",
                DependencyScope.forId("test"),
                "tests-jdk15");
        testArtifact.setPath(testJarFile.toPath());

        List<String> scanDependencies = new ArrayList<>();
        scanDependencies.add("org.surefire.dependency:dependent-artifact2:*:tests-jdk15:*");

        List<String> include = new ArrayList<>();
        include.add("**/*A.java");
        List<String> exclude = new ArrayList<>();

        List<File> filesToScan = filterArtifactsAsFiles(scanDependencies, Collections.singletonList(testArtifact));

        DependencyScanner scanner = new DependencyScanner(filesToScan, new TestListResolver(include, exclude));

        ScanResult classNames = scanner.scan();
        assertNotNull(classNames);
        assertEquals(2, classNames.size());

        Map<String, String> props = new HashMap<>();
        classNames.writeTo(props);
        assertEquals(2, props.size());
    }

    /**
     * Test with type when two artifacts are present, should only find the class in jar with correct type
     */
    @Test
    public void testLocateTestClassesFromMultipleArtifactsWithType() throws Exception {
        File jarFile =
                writeTestFile("DependenciesScannerTest3-1.0.jar", "org/test/ClassA.class", "org/test/ClassB.class");
        StubDependency mainArtifact = new StubDependency(
                "org.surefire.dependency", "dependent-artifact3", "1.0", "jar", DependencyScope.forId("test"));
        mainArtifact.setPath(jarFile.toPath());

        File testJarFile =
                writeTestFile("DependenciesScannerTest3-1.0-tests.jar", "org/test/TestA.class", "org/test/TestB.class");
        StubDependency testArtifact = new StubDependency(
                "org.surefire.dependency", "dependent-artifact3", "1.0", "test-jar", DependencyScope.forId("test"));
        testArtifact.setPath(testJarFile.toPath());

        List<String> scanDependencies = new ArrayList<>();
        scanDependencies.add("org.surefire.dependency:dependent-artifact3:test-jar");

        List<String> include = new ArrayList<>();
        include.add("**/*A.java");
        List<String> exclude = new ArrayList<>();

        List<DownloadedDependency> artifacts = Arrays.asList(mainArtifact, testArtifact);

        List<File> filesToScan = filterArtifactsAsFiles(scanDependencies, artifacts);

        DependencyScanner scanner = new DependencyScanner(filesToScan, new TestListResolver(include, exclude));

        ScanResult classNames = scanner.scan();
        assertNotNull(classNames);
        assertEquals(1, classNames.size());
        assertEquals("org.test.TestA", classNames.getClassName(0));

        Map<String, String> props = new HashMap<>();
        classNames.writeTo(props);
        assertEquals(1, props.size());
    }

    /**
     * Test to pick the right version of an artifact to scan
     */
    @Test
    public void testLocateTestClassesFromMultipleVersionsOfArtifact() throws Exception {
        File jarFile10 =
                writeTestFile("DependenciesScannerTest4-1.0.jar", "org/test/ClassA.class", "org/test/ClassB.class");
        StubDependency artifact10 = new StubDependency(
                "org.surefire.dependency", "dependent-artifact4", "1.0", "jar", DependencyScope.forId("test"));
        artifact10.setPath(jarFile10.toPath());

        File jarFile20 =
                writeTestFile("DependenciesScannerTest4-2.0.jar", "org/test2/ClassA.class", "org/test2/ClassB.class");
        StubDependency artifact20 = new StubDependency(
                "org.surefire.dependency", "dependent-artifact4", "2.0", "jar", DependencyScope.forId("test"));
        artifact20.setPath(jarFile20.toPath());

        List<String> scanDependencies = new ArrayList<>();
        scanDependencies.add("org.surefire.dependency:dependent-artifact4:*:2.0");

        List<String> include = new ArrayList<>();
        include.add("**/*A.java");
        List<String> exclude = new ArrayList<>();

        List<DownloadedDependency> artifacts = Arrays.asList(artifact10, artifact20);

        List<File> filesToScan = filterArtifactsAsFiles(scanDependencies, artifacts);
        DependencyScanner scanner = new DependencyScanner(filesToScan, new TestListResolver(include, exclude));

        ScanResult classNames = scanner.scan();
        assertNotNull(classNames);
        assertEquals(1, classNames.size());
        assertEquals("org.test2.ClassA", classNames.getClassName(0));

        Map<String, String> props = new HashMap<>();
        classNames.writeTo(props);
        assertEquals(1, props.size());
        assertFalse(props.values().contains("org.test.ClassA"));
    }

    private static List<File> filterArtifactsAsFiles(
            List<String> scanDependencies, List<DownloadedDependency> artifacts) {
        List<File> filesToScan = new ArrayList<>();
        for (DownloadedDependency a : DependencyScanner.filter(artifacts, scanDependencies)) {
            filesToScan.add(a.getPath().toFile());
        }
        return filesToScan;
    }

    private File writeTestFile(String fileName, String... entries) throws Exception {
        File output = tempFolder.resolve(fileName).toFile();

        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(output))) {
            for (String entry : entries) {
                out.putNextEntry(new ZipEntry(entry));
                out.closeEntry();
            }
            return output;
        }
    }
}
