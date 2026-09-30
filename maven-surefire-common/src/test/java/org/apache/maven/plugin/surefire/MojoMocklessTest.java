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
package org.apache.maven.plugin.surefire;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.maven.api.Artifact;
import org.apache.maven.api.DependencyScope;
import org.apache.maven.api.DownloadedDependency;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.plugin.surefire.extensions.SurefireConsoleOutputReporter;
import org.apache.maven.plugin.surefire.extensions.SurefireStatelessReporter;
import org.apache.maven.plugin.surefire.extensions.SurefireStatelessTestsetInfoReporter;
import org.apache.maven.surefire.api.suite.RunResult;
import org.apache.maven.surefire.api.testset.RunOrderParameters;
import org.apache.maven.surefire.api.util.DefaultScanResult;
import org.apache.maven.surefire.api.util.RunOrder;
import org.apache.maven.surefire.extensions.ForkNodeFactory;
import org.apache.maven.surefire.providerapi.ProviderInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;

/**
 *
 */
public class MojoMocklessTest {
    @TempDir
    Path tempFolder;

    @SuppressWarnings("unchecked")
    private static <T> T invokeMethod(Object target, String methodName, Object... args) throws Exception {
        Class<?> clazz = target.getClass();
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.getName().equals(methodName) && method.getParameterCount() == args.length) {
                method.setAccessible(true);
                return (T) method.invoke(target, args);
            }
        }
        // search superclasses
        clazz = clazz.getSuperclass();
        while (clazz != null) {
            for (Method method : clazz.getDeclaredMethods()) {
                if (method.getName().equals(methodName) && method.getParameterCount() == args.length) {
                    method.setAccessible(true);
                    return (T) method.invoke(target, args);
                }
            }
            clazz = clazz.getSuperclass();
        }
        throw new NoSuchMethodException(methodName);
    }

    private static void setInternalState(Object target, String fieldName, Object value) {
        try {
            Class<?> clazz = target.getClass();
            while (clazz != null) {
                try {
                    Field field = clazz.getDeclaredField(fieldName);
                    field.setAccessible(true);
                    field.set(target, value);
                    return;
                } catch (NoSuchFieldException e) {
                    clazz = clazz.getSuperclass();
                }
            }
            throw new NoSuchFieldException(fieldName);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void testGetStartupReportConfigurationUsesProvidedRunStatisticsFile() throws Exception {
        AbstractSurefireMojo surefirePlugin = new Mojo(null, null);
        File runStatisticsFile =
                Files.createTempFile(tempFolder, ".surefire-", "checksum").toFile();
        RunOrderParameters runOrderParameters = new RunOrderParameters(RunOrder.DEFAULT, runStatisticsFile);
        StartupReportConfiguration config = invokeMethod(
                surefirePlugin, "getStartupReportConfiguration", false, mock(ProviderInfo.class), runOrderParameters);

        assertThat(config.getStatisticsFile()).isSameAs(runStatisticsFile);
    }

    @Test
    public void testGetStartupReportConfiguration() throws Exception {
        AbstractSurefireMojo surefirePlugin = new Mojo(null, null);
        RunOrderParameters runOrderParameters = new RunOrderParameters(
                RunOrder.DEFAULT,
                Files.createTempFile(tempFolder, ".surefire-", "checksum").toFile());
        StartupReportConfiguration config = invokeMethod(
                surefirePlugin, "getStartupReportConfiguration", false, mock(ProviderInfo.class), runOrderParameters);

        assertThat(config.getXmlReporter()).isNotNull().isInstanceOf(SurefireStatelessReporter.class);

        assertThat(config.getConsoleOutputReporter()).isNotNull().isInstanceOf(SurefireConsoleOutputReporter.class);

        assertThat(config.getTestsetReporter()).isNotNull().isInstanceOf(SurefireStatelessTestsetInfoReporter.class);
    }

    @Test
    public void testGetStartupReportConfiguration2() throws Exception {
        AbstractSurefireMojo surefirePlugin = new Mojo(null, null);
        RunOrderParameters runOrderParameters = new RunOrderParameters(
                RunOrder.DEFAULT,
                Files.createTempFile(tempFolder, ".surefire-", "checksum").toFile());
        SurefireStatelessReporter xmlReporter = new SurefireStatelessReporter();
        SurefireConsoleOutputReporter consoleReporter = new SurefireConsoleOutputReporter();
        SurefireStatelessTestsetInfoReporter testsetInfoReporter = new SurefireStatelessTestsetInfoReporter();
        setInternalState(surefirePlugin, "statelessTestsetReporter", xmlReporter);
        setInternalState(surefirePlugin, "consoleOutputReporter", consoleReporter);
        setInternalState(surefirePlugin, "statelessTestsetInfoReporter", testsetInfoReporter);

        StartupReportConfiguration config = invokeMethod(
                surefirePlugin, "getStartupReportConfiguration", false, mock(ProviderInfo.class), runOrderParameters);

        assertThat(config.getXmlReporter()).isNotNull().isSameAs(xmlReporter);

        assertThat(config.getConsoleOutputReporter()).isNotNull().isSameAs(consoleReporter);

        assertThat(config.getTestsetReporter()).isNotNull().isSameAs(testsetInfoReporter);
    }

    @Test
    @SuppressWarnings("checkstyle:magicnumber")
    public void testForkCountComputation() {
        AbstractSurefireMojo surefirePlugin = new Mojo(null, null);
        assertConversionFails(surefirePlugin, "nothing");

        assertConversionFails(surefirePlugin, "5,0");
        assertConversionFails(surefirePlugin, "5.0");
        assertConversionFails(surefirePlugin, "5,0C");
        assertConversionFails(surefirePlugin, "5.0CC");

        assertForkCount(surefirePlugin, 5, "5");

        int availableProcessors = Runtime.getRuntime().availableProcessors();
        assertForkCount(surefirePlugin, 3 * availableProcessors, "3C");
        assertForkCount(surefirePlugin, (int) (2.5 * availableProcessors), "2.5C");
        assertForkCount(surefirePlugin, availableProcessors, "1.0001 C");
        assertForkCount(surefirePlugin, 1, 1d / ((double) availableProcessors + 1) + "C");
        assertForkCount(surefirePlugin, 0, "0 C");
    }

    private static void assertForkCount(AbstractSurefireMojo surefirePlugin, int expected, String value) {
        assertThat(surefirePlugin.convertWithCoreCount(value)).isEqualTo(expected);
    }

    private static void assertConversionFails(AbstractSurefireMojo surefirePlugin, String value) {
        try {
            surefirePlugin.convertWithCoreCount(value);
        } catch (NumberFormatException e) {
            return;
        }
        fail("Expected NumberFormatException when converting " + value);
    }

    @Test
    public void scanDependenciesShouldReturnNull() throws MojoException {
        Mojo mojo = new Mojo(null, null);
        DefaultScanResult result = mojo.scanDependencies();
        assertThat(result).isNull();
    }

    @Test
    public void scanDependenciesShouldReturnNullAfterMissingBuildArtifact() throws MojoException {
        DependencyBuilder testDeps = dependency("a", "compile", "jar");
        List<DownloadedDependency> projectTestArtifacts = singletonList(testDeps.build());
        String[] dependenciesToScan = {"g:a"};
        Mojo mojo = new Mojo(projectTestArtifacts, dependenciesToScan);
        DefaultScanResult result = mojo.scanDependencies();
        assertThat(result).isNull();
    }

    @Test
    public void scanDependenciesShouldReturnNullWithWAR() throws MojoException {
        DependencyBuilder testDeps = dependency("a", "compile", "war");
        testDeps.setFile(new File(new File("target"), "a-1.0.war"));
        List<DownloadedDependency> projectTestArtifacts = singletonList(testDeps.build());
        String[] dependenciesToScan = {"g:a"};
        Mojo mojo = new Mojo(projectTestArtifacts, dependenciesToScan);
        DefaultScanResult result = mojo.scanDependencies();
        assertThat(result).isNull();
    }

    @Test
    public void scanDependenciesShouldReturnNullWithExistingWAR() throws Exception {
        DependencyBuilder testDeps = dependency("a", "compile", "war");
        File artifactFile = Files.createTempFile(tempFolder, "surefire", ".war").toFile();
        testDeps.setFile(artifactFile);
        List<DownloadedDependency> projectTestArtifacts = singletonList(testDeps.build());
        String[] dependenciesToScan = {"g:a"};
        Mojo mojo = new Mojo(projectTestArtifacts, dependenciesToScan);
        DefaultScanResult result = mojo.scanDependencies();
        assertThat(result).isNull();
    }

    @Test
    public void scanDependenciesShouldReturnClassWithExistingTestJAR() throws Exception {
        DependencyBuilder testDeps = dependency("a", "compile", "test-jar");

        File artifactFile = Files.createTempFile(tempFolder, "surefire", ".jar").toFile();
        testDeps.setFile(artifactFile);
        try (ZipOutputStream os = new ZipOutputStream(Files.newOutputStream(artifactFile.toPath()))) {
            os.putNextEntry(new ZipEntry("pkg/"));
            os.closeEntry();
            os.putNextEntry(new ZipEntry("pkg/MyTest.class"));
            os.closeEntry();
            os.finish();
        }

        List<DownloadedDependency> projectTestArtifacts = singletonList(testDeps.build());
        String[] dependenciesToScan = {"g:a"};
        Mojo mojo = new Mojo(projectTestArtifacts, dependenciesToScan);
        mojo.setLogger(mock(Logger.class));
        DefaultScanResult result = mojo.scanDependencies();

        assertThat(result).isNotNull();

        assertThat(result.isEmpty()).isFalse();

        assertThat(result.getClasses()).contains("pkg.MyTest");
    }

    @Test
    public void scanDependenciesShouldReturnNullWithEmptyTestJAR() throws Exception {
        DependencyBuilder testDeps = dependency("a", "compile", "jar");

        File artifactFile = Files.createTempFile(tempFolder, "surefire", ".jar").toFile();
        testDeps.setFile(artifactFile);
        try (ZipOutputStream os = new ZipOutputStream(Files.newOutputStream(artifactFile.toPath()))) {
            os.putNextEntry(new ZipEntry("pkg/"));
            os.closeEntry();
            os.finish();
        }

        List<DownloadedDependency> projectTestArtifacts = singletonList(testDeps.build());
        String[] dependenciesToScan = {"g:a"};
        Mojo mojo = new Mojo(projectTestArtifacts, dependenciesToScan);
        mojo.setLogger(mock(Logger.class));
        DefaultScanResult result = mojo.scanDependencies();

        assertThat(result).isNotNull();

        assertThat(result.isEmpty()).isTrue();
    }

    @Test
    public void scanDependenciesShouldReturnClassWithDirectory() throws Exception {
        DependencyBuilder testDeps = dependency("a", "compile", "test-jar");

        File classes = Files.createTempDirectory(tempFolder, "surefire-classes").toFile();

        testDeps.setFile(classes);

        assertThat(new File(classes, "AnotherTest.class").createNewFile()).isTrue();

        List<DownloadedDependency> projectTestArtifacts = singletonList(testDeps.build());
        String[] dependenciesToScan = {"g:a"};
        Mojo mojo = new Mojo(projectTestArtifacts, dependenciesToScan);
        mojo.setLogger(mock(Logger.class));
        DefaultScanResult result = mojo.scanDependencies();

        assertThat(result).isNotNull();

        assertThat(result.isEmpty()).isFalse();

        assertThat(result.getClasses()).contains("AnotherTest");
    }

    @Test
    public void scanMultipleDependencies() throws Exception {
        DependencyBuilder testDep1 = dependency("x", "compile", "jar");

        File classes = Files.createTempDirectory(tempFolder, "surefire-classes").toFile();

        testDep1.setFile(classes);

        assertThat(new File(classes, "AnotherTest.class").createNewFile()).isTrue();

        DependencyBuilder testDep2 = dependency("a", "test", "jar");
        File artifactFile2 =
                Files.createTempFile(tempFolder, "surefire", ".jar").toFile();
        testDep2.setFile(artifactFile2);
        try (ZipOutputStream os = new ZipOutputStream(Files.newOutputStream(artifactFile2.toPath()))) {
            os.putNextEntry(new ZipEntry("pkg/"));
            os.closeEntry();
            os.putNextEntry(new ZipEntry("pkg/MyTest.class"));
            os.closeEntry();
            os.finish();
        }

        List<DownloadedDependency> projectTestArtifacts = asList(testDep1.build(), testDep2.build());
        String[] dependenciesToScan = {"g:a"};
        Mojo mojo = new Mojo(projectTestArtifacts, dependenciesToScan);
        mojo.setLogger(mock(Logger.class));
        DefaultScanResult result = mojo.scanDependencies();

        assertThat(result).isNotNull();

        assertThat(result.isEmpty()).isFalse();

        assertThat(result.getClasses()).hasSize(1);

        assertThat(result.getClasses()).contains("pkg.MyTest");
    }

    private static DependencyBuilder dependency(String artifactId, String scope, String type) {
        return new DependencyBuilder(artifactId, scope, type);
    }

    /**
     * The file of a dependency is known after the dependency is made.
     */
    private static final class DependencyBuilder {
        private final String artifactId;
        private final String scope;
        private final String type;
        private File file;

        DependencyBuilder(String artifactId, String scope, String type) {
            this.artifactId = artifactId;
            this.scope = scope;
            this.type = type;
        }

        void setFile(File file) {
            this.file = file;
        }

        DownloadedDependency build() {
            return new ResolvedDependency(
                    new StubDependency("g", artifactId, "1.0", type, DependencyScope.forId(scope)),
                    file == null ? null : file.toPath());
        }
    }

    private static final class Mojo extends AbstractSurefireMojo {
        private final List<DownloadedDependency> projectTestArtifacts;
        private final String[] dependenciesToScan;

        Mojo(List<DownloadedDependency> projectTestArtifacts, String[] dependenciesToScan) {
            this.projectTestArtifacts = projectTestArtifacts;
            this.dependenciesToScan = dependenciesToScan;
        }

        @Override
        protected String getPluginName() {
            return null;
        }

        @Override
        protected int getRerunFailingTestsCount() {
            return 0;
        }

        @Override
        protected boolean isRunLastFailed() {
            return false;
        }

        @Override
        public boolean isSkipTests() {
            return false;
        }

        @Override
        public void setSkipTests(boolean skipTests) {}

        @Override
        public boolean isSkipExec() {
            return false;
        }

        @Override
        public void setSkipExec(boolean skipExec) {}

        @Override
        public boolean isSkip() {
            return false;
        }

        @Override
        public void setSkip(boolean skip) {}

        @Override
        public File getBasedir() {
            return null;
        }

        @Override
        public void setBasedir(File basedir) {}

        @Override
        public File getTestClassesDirectory() {
            return null;
        }

        @Override
        public void setTestClassesDirectory(File testClassesDirectory) {}

        @Override
        public File getMainBuildPath() {
            return null;
        }

        @Override
        public void setMainBuildPath(File mainBuildPath) {}

        @Override
        public File getReportsDirectory() {
            return null;
        }

        @Override
        public void setReportsDirectory(File reportsDirectory) {}

        @Override
        public String getTest() {
            return null;
        }

        @Override
        public void setTest(String test) {}

        @Override
        public List<String> getIncludes() {
            return null;
        }

        @Override
        public File getIncludesFile() {
            return null;
        }

        @Override
        public void setIncludes(List<String> includes) {}

        @Override
        public List<String> getExcludes() {
            return null;
        }

        @Override
        public void setExcludes(List<String> excludes) {}

        @Override
        public boolean isPrintSummary() {
            return false;
        }

        @Override
        public void setPrintSummary(boolean printSummary) {}

        @Override
        public String getReportFormat() {
            return null;
        }

        @Override
        public void setReportFormat(String reportFormat) {}

        @Override
        public boolean isUseFile() {
            return false;
        }

        @Override
        public void setUseFile(boolean useFile) {}

        @Override
        public String getDebugForkedProcess() {
            return null;
        }

        @Override
        public void setDebugForkedProcess(String debugForkedProcess) {}

        @Override
        public int getForkedProcessTimeoutInSeconds() {
            return 0;
        }

        @Override
        public void setForkedProcessTimeoutInSeconds(int forkedProcessTimeoutInSeconds) {}

        @Override
        public int getForkedProcessExitTimeoutInSeconds() {
            return 0;
        }

        @Override
        public void setForkedProcessExitTimeoutInSeconds(int forkedProcessTerminationTimeoutInSeconds) {}

        @Override
        public double getParallelTestsTimeoutInSeconds() {
            return 0;
        }

        @Override
        public void setParallelTestsTimeoutInSeconds(double parallelTestsTimeoutInSeconds) {}

        @Override
        public double getParallelTestsTimeoutForcedInSeconds() {
            return 0;
        }

        @Override
        public void setParallelTestsTimeoutForcedInSeconds(double parallelTestsTimeoutForcedInSeconds) {}

        @Override
        public boolean isUseSystemClassLoader() {
            return false;
        }

        @Override
        public void setUseSystemClassLoader(boolean useSystemClassLoader) {}

        @Override
        public boolean isUseManifestOnlyJar() {
            return false;
        }

        @Override
        public void setUseManifestOnlyJar(boolean useManifestOnlyJar) {}

        @Override
        public String getEncoding() {
            return null;
        }

        @Override
        public void setEncoding(String encoding) {}

        @Override
        public boolean getFailIfNoSpecifiedTests() {
            return false;
        }

        @Override
        public void setFailIfNoSpecifiedTests(boolean failIfNoSpecifiedTests) {}

        @Override
        public int getSkipAfterFailureCount() {
            return 0;
        }

        @Override
        public String getShutdown() {
            return null;
        }

        @Override
        public File getExcludesFile() {
            return null;
        }

        @Override
        protected String[] getExcludedEnvironmentVariables() {
            return new String[0];
        }

        @Override
        public String getRunOrder() {
            return null;
        }

        @Override
        public void setRunOrder(String runOrder) {}

        @Override
        public Long getRunOrderRandomSeed() {
            return null;
        }

        @Override
        public void setRunOrderRandomSeed(Long runOrderRandomSeed) {}

        @Override
        public String getRunOrderStatisticsFileChecksum() {
            return "dummyStatistics";
        }

        @Override
        public void setRunOrderStatisticsFileChecksum(String runOrderStatisticsFileChecksum) {}

        @Override
        public String[] getDependenciesToScan() {
            return dependenciesToScan;
        }

        @Override
        protected void handleSummary(RunResult summary, Exception firstForkException) {}

        @Override
        protected boolean isSkipExecution() {
            return false;
        }

        @Override
        protected String[] getDefaultIncludes() {
            return new String[0];
        }

        @Override
        protected String getReportSchemaLocation() {
            return null;
        }

        @Override
        protected boolean useModulePath() {
            return false;
        }

        @Override
        protected void setUseModulePath(boolean useModulePath) {}

        @Override
        protected ForkNodeFactory getForkNode() {
            return null;
        }

        @Override
        protected String getEnableProcessChecker() {
            return null;
        }

        @Override
        protected Artifact getMojoArtifact() {
            return null;
        }

        @Override
        List<DownloadedDependency> getProjectTestArtifacts() {
            return projectTestArtifacts;
        }

        @Override
        public File getSystemPropertiesFile() {
            return null;
        }

        @Override
        public void setSystemPropertiesFile(File systemPropertiesFile) {}

        @Override
        public String[] getIncludeJUnit5Engines() {
            return null;
        }

        @Override
        public void setIncludeJUnit5Engines(String[] includeJUnit5Engines) {}

        @Override
        public String[] getExcludeJUnit5Engines() {
            return null;
        }

        @Override
        public void setExcludeJUnit5Engines(String[] excludeJUnit5Engines) {}
    }
}
