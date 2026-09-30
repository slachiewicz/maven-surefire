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
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.apache.commons.io.FileUtils;
import org.apache.maven.api.Artifact;
import org.apache.maven.api.DependencyScope;
import org.apache.maven.api.DownloadedArtifact;
import org.apache.maven.api.DownloadedDependency;
import org.apache.maven.api.MojoExecution;
import org.apache.maven.api.Plugin;
import org.apache.maven.api.Project;
import org.apache.maven.api.Toolchain;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.plugin.surefire.booterclient.Platform;
import org.apache.maven.plugin.surefire.log.PluginConsoleLogger;
import org.apache.maven.surefire.api.suite.RunResult;
import org.apache.maven.surefire.api.testset.RunOrderParameters;
import org.apache.maven.surefire.api.util.DefaultScanResult;
import org.apache.maven.surefire.api.util.RunOrder;
import org.apache.maven.surefire.api.util.SureFireFileManager;
import org.apache.maven.surefire.booter.ClassLoaderConfiguration;
import org.apache.maven.surefire.booter.Classpath;
import org.apache.maven.surefire.booter.ModularClasspathConfiguration;
import org.apache.maven.surefire.booter.ProviderConfiguration;
import org.apache.maven.surefire.booter.StartupConfiguration;
import org.apache.maven.surefire.extensions.ForkNodeFactory;
import org.apache.maven.surefire.providerapi.ProviderInfo;
import org.assertj.core.api.Assertions;
import org.codehaus.plexus.languages.java.jpms.JavaModuleDescriptor;
import org.codehaus.plexus.languages.java.jpms.LocationManager;
import org.codehaus.plexus.languages.java.jpms.ResolvePathRequest;
import org.codehaus.plexus.languages.java.jpms.ResolvePathResult;
import org.codehaus.plexus.languages.java.jpms.ResolvePathsRequest;
import org.codehaus.plexus.languages.java.jpms.ResolvePathsResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.Logger;

import static java.io.File.separatorChar;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.Files.write;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singleton;
import static java.util.Collections.singletonList;
import static org.apache.maven.plugin.surefire.SurefireDependencyResolver.artifactMapByVersionlessId;
import static org.apache.maven.surefire.shared.lang3.JavaVersion.JAVA_9;
import static org.apache.maven.surefire.shared.lang3.JavaVersion.JAVA_RECENT;
import static org.apache.maven.surefire.shared.lang3.SystemUtils.IS_OS_WINDOWS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.codehaus.plexus.languages.java.jpms.ModuleNameSource.MODULEDESCRIPTOR;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Test for {@link AbstractSurefireMojo}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class AbstractSurefireMojoTest {

    @TempDir
    Path tempFolder;

    private final Mojo mojo = new Mojo();

    @BeforeEach
    public void setupMojo() {
        Artifact mojoArtifact = mojo.getMojoArtifact();

        mojo.setProperties(new Properties());

        mojo.setPluginArtifactMap(new LinkedHashMap<>());
        mojo.getPluginArtifactMap().put(mojoArtifact.getGroupId() + ":" + mojoArtifact.getArtifactId(), mojoArtifact);
        StubDependency forkedBooter = new StubDependency(
                "org.apache.maven.surefire",
                "surefire-booter",
                mojoArtifact.getVersion().toString(),
                "jar");
        mojo.getPluginArtifactMap().put("org.apache.maven.surefire:surefire-booter", forkedBooter);

        mojo.setProjectArtifactMap(new LinkedHashMap<>());

        mojo.setSession(TestSessions.delegatingSession());

        Plugin plugin = mock(Plugin.class);
        when(plugin.getArtifact()).thenReturn(mojoArtifact);
        MojoExecution mojoExecution = mock(MojoExecution.class);
        when(mojoExecution.getPlugin()).thenReturn(plugin);
        mojo.setMojoExecution(mojoExecution);
    }

    @Test
    public void shouldAddRunOrderParametersToProviderProperties() throws Exception {
        Artifact projectArtifact = mojo.getMojoArtifact();
        mojo.setProject(TestSessions.project(
                projectArtifact.getGroupId(),
                projectArtifact.getArtifactId(),
                projectArtifact.getVersion().toString()));
        RunOrderParameters runOrderParameters = new RunOrderParameters(new RunOrder[] {RunOrder.RANDOM}, null, 1234L);

        ProviderConfiguration providerConfiguration =
                invokeMethod(mojo, "createProviderConfiguration", runOrderParameters);

        assertThat(providerConfiguration.getProviderProperties())
                .containsEntry("runOrder", "random")
                .containsEntry("runOrderRandomSeed", "1234");
    }

    @Test
    public void noModuleDescriptorFile() throws Exception {
        AbstractSurefireMojo mojo = spy(new Mojo());
        mojo.setMainBuildPath(Files.createTempDirectory(tempFolder, "test").toFile());
        File testClassesDir = Files.createTempDirectory(tempFolder, "test").toFile();
        mojo.setTestClassesDirectory(testClassesDir);
        File jdkHome = new File(System.getProperty("java.home"));
        ResolvePathResultWrapper wrapper = invokeMethod(mojo, "findModuleDescriptor", jdkHome);

        assertThat(wrapper).isNotNull();

        assertThat(wrapper.getResolvePathResult()).isNull();

        assertThat((boolean) invokeMethod(mojo, "existsModuleDescriptor", wrapper))
                .isEqualTo(false);

        when(mojo.useModulePath()).thenReturn(true);

        File jvmExecutable = new File(jdkHome, IS_OS_WINDOWS ? "bin\\java.exe" : "bin/java");
        JdkAttributes jdkAttributes = new JdkAttributes(jvmExecutable, jdkHome, true);
        Platform platform = new Platform().withJdkExecAttributesForTests(jdkAttributes);
        assertThat((boolean) invokeMethod(mojo, "canExecuteProviderWithModularPath", platform, wrapper))
                .isEqualTo(false);
    }

    @Test
    public void correctModuleDescriptor() throws Exception {
        AbstractSurefireMojo mojo = spy(new Mojo());
        LocationManager locationManager = mock(LocationManager.class);
        ResolvePathResult result = mock(ResolvePathResult.class);
        when(result.getModuleNameSource()).thenReturn(MODULEDESCRIPTOR);
        JavaModuleDescriptor descriptor = mock(JavaModuleDescriptor.class);
        when(result.getModuleDescriptor()).thenReturn(descriptor);
        when(locationManager.resolvePath(any(ResolvePathRequest.class))).thenReturn(result);
        setInternalState(mojo, "locationManager", locationManager);
        File classesDir = Files.createTempDirectory(tempFolder, "test").toFile();
        mojo.setMainBuildPath(classesDir);
        File testClassesDir = Files.createTempDirectory(tempFolder, "test").toFile();
        mojo.setTestClassesDirectory(testClassesDir);
        File descriptorFile = new File(classesDir, "module-info.class");
        assertThat(descriptorFile.createNewFile()).isTrue();
        File jdkHome = new File(System.getProperty("java.home"));
        ResolvePathResultWrapper wrapper = invokeMethod(mojo, "findModuleDescriptor", jdkHome);

        assertThat(wrapper).isNotNull();

        assertThat(wrapper.getResolvePathResult()).isSameAs(result);

        assertThat(wrapper.getResolvePathResult().getModuleNameSource()).isSameAs(MODULEDESCRIPTOR);

        assertThat(wrapper.getResolvePathResult().getModuleDescriptor()).isSameAs(descriptor);

        assertThat((boolean) invokeMethod(mojo, "existsModuleDescriptor", wrapper))
                .isEqualTo(true);

        when(mojo.useModulePath()).thenReturn(true);

        File jvmExecutable = new File(jdkHome, IS_OS_WINDOWS ? "bin\\java.exe" : "bin/java");
        JdkAttributes jdkAttributes = new JdkAttributes(jvmExecutable, jdkHome, true);
        Platform platform = new Platform().withJdkExecAttributesForTests(jdkAttributes);
        assertThat((boolean) invokeMethod(mojo, "canExecuteProviderWithModularPath", platform, wrapper))
                .isEqualTo(true);

        jdkAttributes = new JdkAttributes(jvmExecutable, jdkHome, false);
        platform = new Platform().withJdkExecAttributesForTests(jdkAttributes);
        assertThat((boolean) invokeMethod(mojo, "canExecuteProviderWithModularPath", platform, wrapper))
                .isEqualTo(false);

        when(mojo.useModulePath()).thenReturn(false);

        jdkAttributes = new JdkAttributes(jvmExecutable, jdkHome, true);
        platform = new Platform().withJdkExecAttributesForTests(jdkAttributes);
        assertThat((boolean) invokeMethod(mojo, "canExecuteProviderWithModularPath", platform, wrapper))
                .isEqualTo(false);
    }

    @Test
    @SuppressWarnings("checkstyle:magicnumber")
    public void corruptedModuleDescriptor() throws Exception {
        if (!JAVA_RECENT.atLeast(JAVA_9)) {
            return;
        }

        AbstractSurefireMojo mojo = spy(new Mojo());
        setInternalState(mojo, "locationManager", new LocationManager());
        File classesDir = Files.createTempDirectory(tempFolder, "test").toFile();
        mojo.setMainBuildPath(classesDir);
        File testClassesDir = Files.createTempDirectory(tempFolder, "test").toFile();
        mojo.setTestClassesDirectory(testClassesDir);

        File descriptorFile = new File(classesDir, "module-info.class");
        assertThat(descriptorFile.createNewFile()).isTrue();
        write(descriptorFile.toPath(), new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE});

        File jdkHome = new File(System.getProperty("java.home"));
        ResolvePathResultWrapper wrapper = invokeMethod(mojo, "findModuleDescriptor", jdkHome);

        assertThat(wrapper).isNotNull();

        assertThat(wrapper.getResolvePathResult()).isNull();

        assertThat((boolean) invokeMethod(mojo, "existsModuleDescriptor", wrapper))
                .isEqualTo(false);
    }

    @Test
    public void shouldShowArray() throws Exception {
        Logger logger = mock(Logger.class);
        when(logger.isDebugEnabled()).thenReturn(true);
        doNothing().when(logger).debug(anyString());

        AbstractSurefireMojo mojo = spy(this.mojo);

        when(mojo.getConsoleLogger()).thenReturn(new PluginConsoleLogger(logger));

        Object[] array = {"ABC", "XYZ"};
        invokeMethod(mojo, "showArray", array, "prefix");

        ArgumentCaptor<String> argument = ArgumentCaptor.forClass(String.class);
        verify(logger, times(2)).debug(argument.capture());
        assertThat(argument.getAllValues()).containsExactly("Setting prefix [ABC]", "Setting prefix [XYZ]");
    }

    @Test
    public void shouldShowMap() throws Exception {
        Logger logger = mock(Logger.class);
        when(logger.isDebugEnabled()).thenReturn(true);
        doNothing().when(logger).debug(anyString());

        AbstractSurefireMojo mojo = spy(this.mojo);

        when(mojo.getConsoleLogger()).thenReturn(new PluginConsoleLogger(logger));

        Map<String, String> map = new LinkedHashMap<>();
        map.put("ABC", "123");
        map.put("XYZ", "987");
        invokeMethod(mojo, "showMap", map, "prefix");

        ArgumentCaptor<String> argument = ArgumentCaptor.forClass(String.class);
        verify(logger, times(2)).debug(argument.capture());
        assertThat(argument.getAllValues()).containsExactly("Setting prefix [ABC]=[123]", "Setting prefix [XYZ]=[987]");
    }

    @Test
    public void shouldRetainInPluginArtifacts() throws Exception {
        StubDependency provider = new StubDependency("g", "a", "1", "jar", DependencyScope.forId("compile"));
        StubDependency common = new StubDependency("g", "c", "1", "jar", DependencyScope.forId("compile"));
        StubDependency api = new StubDependency("g", "a", "1", "jar", DependencyScope.forId("compile"));

        Set<DownloadedArtifact> providerArtifacts = singleton(provider);
        Artifact[] inPluginArtifacts = {common, api};
        Set<Artifact> inPluginClasspath = invokeMethod(
                AbstractSurefireMojo.class, "retainInProcArtifactsUnique", providerArtifacts, inPluginArtifacts);

        assertThat(inPluginClasspath).containsOnly(common);
    }

    @Test
    public void shouldRetainInProcArtifactsUnique() throws Exception {
        StubDependency provider = new StubDependency("g", "p", "1", "jar", DependencyScope.forId("compile"));
        StubDependency common = new StubDependency("g", "c", "1", "jar", DependencyScope.forId("compile"));
        StubDependency api = new StubDependency("g", "a", "1", "jar", DependencyScope.forId("compile"));

        Set<DownloadedArtifact> providerArtifacts = singleton(provider);
        Artifact[] inPluginArtifacts = {common, api};
        Set<Artifact> inPluginClasspath = invokeMethod(
                AbstractSurefireMojo.class, "retainInProcArtifactsUnique", providerArtifacts, inPluginArtifacts);

        assertThat(inPluginClasspath).containsOnly(common, api);
    }

    @Test
    public void shouldCreateInProcClasspath() throws Exception {
        StubDependency provider = new StubDependency("g", "p", "1", "jar", DependencyScope.forId("compile"));
        provider.setPath(pathOf(mockFile("provider.jar")));

        StubDependency common = new StubDependency("g", "c", "1", "jar", DependencyScope.forId("compile"));
        common.setPath(pathOf(mockFile("maven-surefire-common.jar")));

        StubDependency api = new StubDependency("g", "a", "1", "jar", DependencyScope.forId("compile"));
        api.setPath(pathOf(mockFile("surefire-api.jar")));

        Set<Artifact> newArtifacts = new LinkedHashSet<>();
        newArtifacts.add(common);
        newArtifacts.add(api);

        Classpath providerClasspath =
                new Classpath(singleton(provider.getPath().toAbsolutePath().toString()));

        Classpath inPluginClasspath = invokeMethod(this.mojo, "createInProcClasspath", providerClasspath, newArtifacts);

        Classpath expectedClasspath = new Classpath(asList(
                provider.getPath().toAbsolutePath().toString(),
                common.getPath().toAbsolutePath().toString(),
                api.getPath().toAbsolutePath().toString()));

        assertThat((Object) inPluginClasspath).isEqualTo(expectedClasspath);
    }

    @Test
    public void shouldGenerateTestClasspath() throws Exception {
        AbstractSurefireMojo mojo = spy(this.mojo);

        when(mojo.getMainBuildPath()).thenReturn(new File("target" + separatorChar + "classes"));
        when(mojo.getTestClassesDirectory()).thenReturn(new File("target" + separatorChar + "test-classes"));
        when(mojo.getClasspathDependencyScopeExclude()).thenReturn("runtime");
        when(mojo.getClasspathDependencyExcludes()).thenReturn(new String[] {"g3:a3"});

        StubDependency a1 = new StubDependency("g1", "a1", "1", "jar", DependencyScope.RUNTIME);

        StubDependency a2 = new StubDependency("g2", "a2", "2", "jar", DependencyScope.TEST);
        a2.setPath(pathOf(new File("a2-2.jar")));

        StubDependency a3 = new StubDependency("g3", "a3", "3", "jar", DependencyScope.TEST);
        a3.setPath(pathOf(new File("a3-3.jar")));

        // the counterpart of MavenProject#getArtifacts(): resolved in the order of the classpath
        this.mojo.setProjectTestArtifacts(asList(a1, a2, a3));
        when(mojo.getProjectTestArtifacts()).thenReturn(asList(a1, a2, a3));

        TestClassPath cp = invokeMethod(mojo, "generateTestClasspath");

        verify(mojo, times(1)).getMainBuildPath();
        verify(mojo, times(1)).getTestClassesDirectory();
        verify(mojo, times(3)).getClasspathDependencyScopeExclude();
        verify(mojo, times(2)).getClasspathDependencyExcludes();
        verify(mojo, times(1)).getAdditionalClasspathElements();

        assertThat(cp.toClasspath().getClassPath()).hasSize(3);
        assertThat(cp.toClasspath().getClassPath().get(0)).endsWith("test-classes");
        assertThat(cp.toClasspath().getClassPath().get(1)).endsWith("classes");
        assertThat(cp.toClasspath().getClassPath().get(2)).endsWith("a2-2.jar");
    }

    @Test
    @SuppressWarnings("checkstyle:linelength")
    public void shouldHaveStartupConfigForNonModularClasspath() throws Exception {
        AbstractSurefireMojo mojo = spy(this.mojo);

        StubDependency common = new StubDependency(
                "org.apache.maven.surefire", "maven-surefire-common", "1", "jar", DependencyScope.forId("runtime"));
        common.setPath(pathOf(mockFile("maven-surefire-common.jar")));

        StubDependency ext = new StubDependency(
                "org.apache.maven.surefire", "surefire-extensions-api", "1", "jar", DependencyScope.forId("runtime"));
        ext.setPath(pathOf(mockFile("surefire-extensions-api.jar")));

        StubDependency api = new StubDependency(
                "org.apache.maven.surefire", "surefire-api", "1", "jar", DependencyScope.forId("runtime"));
        api.setPath(pathOf(mockFile("surefire-api.jar")));

        StubDependency loggerApi = new StubDependency(
                "org.apache.maven.surefire", "surefire-logger-api", "1", "jar", DependencyScope.forId("runtime"));
        loggerApi.setPath(pathOf(mockFile("surefire-logger-api.jar")));

        StubDependency spi = new StubDependency(
                "org.apache.maven.surefire", "surefire-extensions-spi", "1", "jar", DependencyScope.forId("runtime"));
        spi.setPath(pathOf(mockFile("surefire-extensions-spi.jar")));

        StubDependency booter = new StubDependency(
                "org.apache.maven.surefire", "surefire-booter", "1", "jar", DependencyScope.forId("runtime"));
        booter.setPath(pathOf(mockFile("surefire-booter.jar")));

        StubDependency utils = new StubDependency(
                "org.apache.maven.surefire", "surefire-shared-utils", "1", "jar", DependencyScope.forId("runtime"));
        utils.setPath(pathOf(mockFile("surefire-shared-utils.jar")));

        Map<String, Artifact> providerArtifactsMap = new HashMap<>();
        providerArtifactsMap.put("org.apache.maven.surefire:maven-surefire-common", common);
        providerArtifactsMap.put("org.apache.maven.surefire:surefire-extensions-api", ext);
        providerArtifactsMap.put("org.apache.maven.surefire:surefire-api", api);
        providerArtifactsMap.put("org.apache.maven.surefire:surefire-logger-api", loggerApi);
        providerArtifactsMap.put("org.apache.maven.surefire:surefire-extensions-spi", spi);
        providerArtifactsMap.put("org.apache.maven.surefire:surefire-booter", booter);
        providerArtifactsMap.put("org.apache.maven.surefire:surefire-shared-utils", utils);

        doReturn(providerArtifactsMap).when(mojo).getPluginArtifactMap();

        String v1 = "4.12";
        StubDependency junit = new StubDependency("junit", "junit", v1, "jar", DependencyScope.forId("test"));
        junit.setPath(pathOf(mockFile("junit.jar")));

        String v2 = "1.3.0";
        StubDependency hamcrest =
                new StubDependency("org.hamcrest", "hamcrest-core", v2, "jar", DependencyScope.forId("test"));
        hamcrest.setPath(pathOf(mockFile("hamcrest.jar")));

        File classesDir = mockFile("classes");
        File testClassesDir = mockFile("test-classes");
        TestClassPath testClasspath = new TestClassPath(asList(junit, hamcrest), classesDir, testClassesDir, null);

        doReturn(1).when(mojo).getEffectiveForkCount();
        doReturn(true).when(mojo).effectiveIsEnableAssertions();
        when(mojo.isChildDelegation()).thenReturn(false);

        ClassLoaderConfiguration classLoaderConfiguration = new ClassLoaderConfiguration(false, true);

        String v3 = "1";
        StubDependency provider =
                new StubDependency("x", "surefire-provider", v3, "jar", DependencyScope.forId("runtime"));
        provider.setPath(pathOf(mockFile("surefire-provider.jar")));
        Set<DownloadedArtifact> providerArtifacts = singleton(provider);

        Logger logger = mock(Logger.class);
        when(logger.isDebugEnabled()).thenReturn(true);
        doNothing().when(logger).debug(anyString());
        when(mojo.getConsoleLogger()).thenReturn(new PluginConsoleLogger(logger));

        ProviderInfo providerInfo = mock(ProviderInfo.class);
        when(providerInfo.getProviderName()).thenReturn("org.asf.Provider");
        when(providerInfo.getProviderClasspath()).thenReturn(providerArtifacts);
        when(providerInfo.decorateTestClassPath(any())).thenReturn(testClasspath);

        StartupConfiguration conf = invokeMethod(
                mojo, "newStartupConfigWithClasspath", classLoaderConfiguration, providerInfo, testClasspath);

        verify(mojo, times(1)).effectiveIsEnableAssertions();
        verify(mojo, times(1)).isChildDelegation();
        ArgumentCaptor<String> argument = ArgumentCaptor.forClass(String.class);
        verify(logger, times(6)).debug(argument.capture());
        assertThat(argument.getAllValues())
                .containsExactly(
                        "test classpath:  test-classes  classes  junit.jar  hamcrest.jar",
                        "provider classpath:  surefire-provider.jar",
                        "test(compact) classpath:  test-classes  classes  junit.jar  hamcrest.jar",
                        "provider(compact) classpath:  surefire-provider.jar",
                        "in-process classpath:  surefire-provider.jar  maven-surefire-common.jar  surefire-booter.jar  surefire-extensions-api.jar  surefire-api.jar  surefire-extensions-spi.jar  surefire-logger-api.jar  surefire-shared-utils.jar",
                        "in-process(compact) classpath:  surefire-provider.jar  maven-surefire-common.jar  surefire-booter.jar  surefire-extensions-api.jar  surefire-api.jar  surefire-extensions-spi.jar  surefire-logger-api.jar  surefire-shared-utils.jar");

        assertThat(conf.getClassLoaderConfiguration()).isSameAs(classLoaderConfiguration);

        assertThat((Object) conf.getClasspathConfiguration().getTestClasspath()).isEqualTo(testClasspath.toClasspath());

        Collection<String> files = new ArrayList<>();
        for (DownloadedArtifact providerArtifact : providerArtifacts) {
            files.add(providerArtifact.getPath().toAbsolutePath().toString());
        }
        assertThat((Object) conf.getClasspathConfiguration().getProviderClasspath())
                .isEqualTo(new Classpath(files));

        assertThat((Object) conf.getClasspathConfiguration().isClassPathConfig())
                .isEqualTo(true);

        assertThat((Object) conf.getClasspathConfiguration().isModularPathConfig())
                .isEqualTo(false);

        assertThat((Object) conf.getClasspathConfiguration().isEnableAssertions())
                .isEqualTo(true);

        assertThat(conf.getProviderClassName()).isEqualTo("org.asf.Provider");
    }

    @Test
    public void providerClasspathCachingIsNotSharedAcrossMojoInstances() throws Exception {
        ProviderInfo providerInfo = mock(ProviderInfo.class);
        when(providerInfo.getProviderName()).thenReturn("test-provider");
        StubDependency provider =
                new StubDependency("com.example", "provider", "1", "jar", DependencyScope.forId("runtime"));
        provider.setPath(pathOf(mockFile("original-test-provider.jar")));
        Set<DownloadedArtifact> providerClasspath = singleton(provider);
        when(providerInfo.getProviderClasspath()).thenReturn(providerClasspath);
        TestClassPath testClassPath = new TestClassPath(
                Collections.<DownloadedDependency>singleton(provider),
                new File("target"),
                new File("target"),
                Collections.emptyList());
        when(providerInfo.decorateTestClassPath(any())).thenReturn(testClassPath);

        StartupConfiguration startupConfiguration = startupConfigurationForProvider(providerInfo);
        assertThat(startupConfiguration
                        .getClasspathConfiguration()
                        .getProviderClasspath()
                        .getClassPath())
                .containsExactly("original-test-provider.jar");

        provider.setPath(pathOf(mockFile("modified-test-provider.jar")));
        startupConfiguration = startupConfigurationForProvider(providerInfo);
        assertThat(startupConfiguration
                        .getClasspathConfiguration()
                        .getProviderClasspath()
                        .getClassPath())
                .containsExactly("modified-test-provider.jar");
    }

    private StartupConfiguration startupConfigurationForProvider(ProviderInfo providerInfo) throws Exception {
        AbstractSurefireMojo mojo = spy(new Mojo());
        mojo.setSession(TestSessions.delegatingSession());

        Logger logger = mock(Logger.class);
        when(logger.isDebugEnabled()).thenReturn(true);
        doNothing().when(logger).debug(anyString());
        when(mojo.getConsoleLogger()).thenReturn(new PluginConsoleLogger(logger));

        File classesDir = mockFile("classes");
        File testClassesDir = mockFile("test-classes");
        TestClassPath testClassPath =
                new TestClassPath(new ArrayList<>(), classesDir, testClassesDir, Collections.emptyList());

        StubDependency common = new StubDependency(
                "org.apache.maven.surefire", "maven-surefire-common", "1", "jar", DependencyScope.forId("runtime"));
        common.setPath(pathOf(mockFile("maven-surefire-common.jar")));

        StubDependency ext = new StubDependency(
                "org.apache.maven.surefire", "surefire-extensions-api", "1", "jar", DependencyScope.forId("runtime"));
        ext.setPath(pathOf(mockFile("surefire-extensions-api.jar")));

        StubDependency api = new StubDependency(
                "org.apache.maven.surefire", "surefire-api", "1", "jar", DependencyScope.forId("runtime"));
        api.setPath(pathOf(mockFile("surefire-api.jar")));

        StubDependency loggerApi = new StubDependency(
                "org.apache.maven.surefire", "surefire-logger-api", "1", "jar", DependencyScope.forId("runtime"));
        loggerApi.setPath(pathOf(mockFile("surefire-logger-api.jar")));

        StubDependency spi = new StubDependency(
                "org.apache.maven.surefire", "surefire-extensions-spi", "1", "jar", DependencyScope.forId("runtime"));
        spi.setPath(pathOf(mockFile("surefire-extensions-spi.jar")));

        StubDependency booter = new StubDependency(
                "org.apache.maven.surefire", "surefire-booter", "1", "jar", DependencyScope.forId("runtime"));
        booter.setPath(pathOf(mockFile("surefire-booter.jar")));

        StubDependency utils = new StubDependency(
                "org.apache.maven.surefire", "surefire-shared-utils", "1", "jar", DependencyScope.forId("runtime"));
        utils.setPath(pathOf(mockFile("surefire-shared-utils.jar")));

        Map<String, Artifact> providerArtifactsMap = new HashMap<>();
        providerArtifactsMap.put("org.apache.maven.surefire:maven-surefire-common", common);
        providerArtifactsMap.put("org.apache.maven.surefire:surefire-extensions-api", ext);
        providerArtifactsMap.put("org.apache.maven.surefire:surefire-api", api);
        providerArtifactsMap.put("org.apache.maven.surefire:surefire-logger-api", loggerApi);
        providerArtifactsMap.put("org.apache.maven.surefire:surefire-extensions-spi", spi);
        providerArtifactsMap.put("org.apache.maven.surefire:surefire-booter", booter);
        providerArtifactsMap.put("org.apache.maven.surefire:surefire-shared-utils", utils);

        doReturn(providerArtifactsMap).when(mojo).getPluginArtifactMap();

        doReturn(1).when(mojo).getEffectiveForkCount();

        return invokeMethod(
                mojo, "createStartupConfiguration", providerInfo, false, null, null, testClassPath, null, null);
    }

    @Test
    public void shouldCreateStartupConfigWithModularPath() throws Exception {
        String baseDir = System.getProperty("user.dir");

        Mojo mojo = new Mojo();
        mojo.setSession(TestSessions.delegatingSession());

        // ### BEGIN
        // we cannot mock private method newStartupConfigWithModularPath() - mocking the data to prevent from errors
        LocationManager locationManager = mock(LocationManager.class);
        ResolvePathsResult resolvePathsResult = mock(ResolvePathsResult.class);
        when(locationManager.resolvePaths(any(ResolvePathsRequest.class))).thenReturn(resolvePathsResult);
        when(resolvePathsResult.getPathExceptions()).thenReturn(emptyMap());
        when(resolvePathsResult.getClasspathElements()).thenReturn(emptyList());
        when(resolvePathsResult.getModulepathElements()).thenReturn(emptyMap());

        mojo.setLogger(mock(Logger.class));
        mojo.setUseModulePath(true);
        setInternalState(mojo, "locationManager", locationManager);

        File jdkHome = new File(System.getProperty("java.home"));
        File jvmExecutable = new File(jdkHome, IS_OS_WINDOWS ? "bin\\java.exe" : "bin/java");
        JdkAttributes jdkAttributes = new JdkAttributes(jvmExecutable, jdkHome, true);
        Platform platform = new Platform().withJdkExecAttributesForTests(jdkAttributes);

        File classesDirectory = new File(baseDir, "mock-dir");
        File testClassesDirectory = new File(baseDir, "mock-dir");
        mojo.setTestClassesDirectory(testClassesDirectory);
        TestClassPath testClassPath = new TestClassPath(
                Collections.emptySet(), classesDirectory, testClassesDirectory, Collections.emptyList());

        ProviderInfo providerInfo = mock(ProviderInfo.class);
        when(providerInfo.getProviderName()).thenReturn("provider mock");
        when(providerInfo.getProviderClasspath()).thenReturn(Collections.emptySet());

        DefaultScanResult defaultScanResult = mock(DefaultScanResult.class);
        when(defaultScanResult.getClasses()).thenReturn(Collections.emptyList());

        Path pathToModularDescriptor =
                Paths.get(baseDir, "src", "test", "resources", "org", "apache", "maven", "plugin", "surefire");
        mojo.setMainBuildPath(pathToModularDescriptor.toFile());

        Map<String, Artifact> artifacts = new HashMap<>();
        StubDependency dummyArtifact = new StubDependency("dummy", "dummy", "1", "jar");
        dummyArtifact.setPath(pathOf(new File(baseDir, "mock-file")));
        artifacts.put("org.apache.maven.surefire:maven-surefire-common", dummyArtifact);
        artifacts.put("org.apache.maven.surefire:surefire-extensions-api", dummyArtifact);
        artifacts.put("org.apache.maven.surefire:surefire-api", dummyArtifact);
        artifacts.put("org.apache.maven.surefire:surefire-logger-api", dummyArtifact);
        artifacts.put("org.apache.maven.surefire:surefire-extensions-spi", dummyArtifact);
        artifacts.put("org.apache.maven.surefire:surefire-booter", dummyArtifact);
        artifacts.put("org.apache.maven.surefire:surefire-shared-utils", dummyArtifact);
        mojo.setPluginArtifactMap(artifacts);

        ResolvePathResult resolvePathResult = mock(ResolvePathResult.class);
        JavaModuleDescriptor desc = mock(JavaModuleDescriptor.class);
        when(desc.name()).thenReturn("");
        when(resolvePathResult.getModuleDescriptor()).thenReturn(desc);
        ResolvePathResultWrapper wrapper = new ResolvePathResultWrapper(resolvePathResult, true);
        // ### END

        StartupConfiguration actualConfig = invokeMethod(
                mojo,
                "createStartupConfiguration",
                new Class[] {
                    ProviderInfo.class,
                    boolean.class,
                    ClassLoaderConfiguration.class,
                    DefaultScanResult.class,
                    TestClassPath.class,
                    Platform.class,
                    ResolvePathResultWrapper.class
                },
                providerInfo,
                true,
                mock(ClassLoaderConfiguration.class),
                defaultScanResult,
                testClassPath,
                platform,
                wrapper);

        assertThat(actualConfig).isNotNull();

        assertThat(actualConfig.getClasspathConfiguration()).isInstanceOf(ModularClasspathConfiguration.class);
    }

    @Test
    public void shouldExistTmpDirectory() throws IOException {
        String systemTmpDir = System.getProperty("java.io.tmpdir");
        String usrDir = new File(System.getProperty("user.dir")).getCanonicalPath();

        String tmpDir = "surefireX" + System.currentTimeMillis();

        //noinspection ResultOfMethodCallIgnored
        new File(systemTmpDir, tmpDir).delete();

        File targetDir = new File(usrDir, "target");
        //noinspection ResultOfMethodCallIgnored
        new File(targetDir, tmpDir).delete();

        AbstractSurefireMojo mojo = mock(AbstractSurefireMojo.class);
        Logger logger = mock(Logger.class);
        when(logger.isDebugEnabled()).thenReturn(false);
        when(logger.isErrorEnabled()).thenReturn(false);
        doNothing().when(logger).debug(anyString());
        doNothing().when(logger).error(anyString(), any(Throwable.class));
        when(mojo.getConsoleLogger()).thenReturn(new PluginConsoleLogger(logger));
        when(mojo.getTempDir()).thenReturn(tmpDir);
        when(mojo.getProjectBuildDirectory()).thenReturn(targetDir);
        when(mojo.createSurefireBootDirectoryInTemp()).thenCallRealMethod();
        when(mojo.createSurefireBootDirectoryInBuild()).thenCallRealMethod();
        when(mojo.getSurefireTempDir()).thenCallRealMethod();

        File bootDir = mojo.createSurefireBootDirectoryInTemp();
        assertThat(bootDir).isNotNull();
        assertThat(bootDir).isDirectory();

        assertThat(new File(systemTmpDir, bootDir.getName())).isDirectory();
        assertThat(bootDir.getName()).startsWith(tmpDir);

        File buildTmp = mojo.createSurefireBootDirectoryInBuild();
        assertThat(buildTmp).isNotNull();
        assertThat(buildTmp).isDirectory();
        assertThat(buildTmp.getParentFile().getCanonicalFile().getParent()).isEqualTo(usrDir);
        assertThat(buildTmp.getName()).isEqualTo(tmpDir);

        File tmp = mojo.getSurefireTempDir();
        assertThat(tmp).isNotNull();
        assertThat(tmp).isDirectory();
        assertThat(IS_OS_WINDOWS ? new File(systemTmpDir, bootDir.getName()) : new File(targetDir, tmpDir))
                .isDirectory();
    }

    @Test
    public void shouldSmartlyResolveJUnit5ProviderWithJUnit4() throws Exception {
        mojo.setProject(TestSessions.project("dummy", "pom", "1.0.0"));

        String surefireVersion = "1";

        StubDependency testClasspathJUnit = new StubDependency("junit", "junit", "4.12", "jar");

        StubDependency testClasspathHamcrest = new StubDependency("org.hamcrest", "hamcrest-core", "1.3", "jar");

        setProjectDepedenciesToMojo(testClasspathJUnit, testClasspathHamcrest);

        Collection<DownloadedDependency> testArtifacts = new ArrayList<>();
        testArtifacts.add(testClasspathJUnit);
        testArtifacts.add(testClasspathHamcrest);

        File classesDirectory = new File("target/classes");

        File testClassesDirectory = new File("target/test-classes");

        TestClassPath testClasspathWrapper =
                new TestClassPath(testArtifacts, classesDirectory, testClassesDirectory, null);

        SurefireDependencyResolver dependencyResolver = mock(SurefireDependencyResolver.class);
        mojo.setSurefireDependencyResolver(dependencyResolver);

        when(dependencyResolver.getProviderClasspathAsMap(any(), any(), anyString(), anyString()))
                .thenReturn(artifactMapByVersionlessId(createSurefireProviderResolutionResult(surefireVersion)));

        when(dependencyResolver.resolveArtifacts(any(), any(), any(Artifact.class)))
                .thenReturn(createExpectedJUnitPlatformLauncherResolutionResult());

        StubDependency pluginDep1 = new StubDependency("org.junit.vintage", "junit-vintage-engine", "5.4.0", "jar");

        StubDependency pluginDep2 = new StubDependency("org.apiguardian", "apiguardian-api", "1.0.0", "jar");

        StubDependency pluginDep3 = new StubDependency("org.junit.platform", "junit-platform-engine", "1.4.0", "jar");

        StubDependency pluginDep4 = new StubDependency("junit", "junit", "4.12", "jar");

        StubDependency pluginDep5 = new StubDependency("org.hamcrest", "hamcrest-core", "1.3", "jar");

        StubDependency pluginDep6 = new StubDependency("org.opentest4j", "opentest4j", "1.1.1", "jar");

        StubDependency pluginDep7 = new StubDependency("org.junit.platform", "junit-platform-commons", "1.4.0", "jar");

        addPluginDependencies(pluginDep1, pluginDep2, pluginDep3, pluginDep4, pluginDep5, pluginDep6, pluginDep7);

        mojo.setLogger(mock(Logger.class));

        Set<DownloadedArtifact> pluginDependencyArtifacts = new HashSet<>();
        pluginDependencyArtifacts.add(pluginDep1);
        pluginDependencyArtifacts.add(pluginDep2);
        pluginDependencyArtifacts.add(pluginDep3);
        pluginDependencyArtifacts.add(pluginDep4);
        pluginDependencyArtifacts.add(pluginDep5);
        pluginDependencyArtifacts.add(pluginDep6);
        pluginDependencyArtifacts.add(pluginDep7);

        when(dependencyResolver.resolvePluginDependencies(any(), any(), any(), any()))
                .thenReturn(artifactMapByVersionlessId(pluginDependencyArtifacts));

        Artifact junitPlatformArtifact = invokeMethod(mojo, "getJUnit5Artifact");
        assertThat(junitPlatformArtifact.getGroupId()).isEqualTo("org.junit.platform");
        assertThat(junitPlatformArtifact.getArtifactId()).isEqualTo("junit-platform-engine");
        assertThat(junitPlatformArtifact.getVersion().toString()).isEqualTo("1.4.0");

        AbstractSurefireMojo.JUnitPlatformProviderInfo prov =
                mojo.createJUnitPlatformProviderInfo(junitPlatformArtifact, testClasspathWrapper);

        assertThat(prov.isApplicable()).isTrue();

        StubDependency expectedProvider =
                new StubDependency("org.apache.maven.surefire", "surefire-junit-platform", surefireVersion, "jar");
        StubDependency expectedCommonJava5 =
                new StubDependency("org.apache.maven.surefire", "common-java5", surefireVersion, "jar");
        StubDependency expectedLauncher =
                new StubDependency("org.junit.platform", "junit-platform-launcher", "1.4.0", "jar");
        StubDependency expectedApiguardian = new StubDependency("org.apiguardian", "apiguardian-api", "1.0.0", "jar");
        StubDependency expectedJUnit5Engine =
                new StubDependency("org.junit.platform", "junit-platform-engine", "1.4.0", "jar");
        StubDependency expectedOpentest4j = new StubDependency("org.opentest4j", "opentest4j", "1.1.1", "jar");
        StubDependency expectedPlatformCommons =
                new StubDependency("org.junit.platform", "junit-platform-commons", "1.4.0", "jar");
        StubDependency expectedEngine = new StubDependency("org.junit.vintage", "junit-vintage-engine", "5.4.0", "jar");

        assertThat(prov.getProviderClasspath())
                .hasSize(8)
                .containsOnly(
                        expectedProvider,
                        expectedCommonJava5,
                        expectedLauncher,
                        expectedApiguardian,
                        expectedJUnit5Engine,
                        expectedOpentest4j,
                        expectedPlatformCommons,
                        expectedEngine);

        assertThat(testClasspathWrapper.getTestDependencies())
                .hasSize(2)
                .containsEntry("junit:junit", testClasspathJUnit)
                .containsEntry("org.hamcrest:hamcrest-core", testClasspathHamcrest);
    }

    @Test
    public void shouldSmartlyResolveJUnit5ProviderWithVintage() throws Exception {
        mojo.setProject(TestSessions.project("dummy", "pom", "1.0.0"));

        String surefireVersion = "1";

        StubDependency testClasspathSomeTestArtifact = new StubDependency("third.party", "artifact", "1.0", "jar");

        StubDependency testClasspathVintage =
                new StubDependency("org.junit.vintage", "junit-vintage-engine", "5.4.0", "jar");

        StubDependency testClasspathApiguardian =
                new StubDependency("org.apiguardian", "apiguardian-api", "1.0.0", "jar");

        StubDependency testClasspathPlatformEng =
                new StubDependency("org.junit.platform", "junit-platform-engine", "1.4.0", "jar");

        StubDependency testClasspathJUnit4 = new StubDependency("junit", "junit", "4.12", "jar");

        StubDependency testClasspathHamcrest = new StubDependency("org.hamcrest", "hamcrest-core", "1.3", "jar");

        StubDependency testClasspathOpentest4j = new StubDependency("org.opentest4j", "opentest4j", "1.1.1", "jar");

        StubDependency testClasspathCommons =
                new StubDependency("org.junit.platform", "junit-platform-commons", "1.4.0", "jar");

        Collection<DownloadedDependency> testArtifacts = asList(
                testClasspathSomeTestArtifact,
                testClasspathVintage,
                testClasspathApiguardian,
                testClasspathPlatformEng,
                testClasspathJUnit4,
                testClasspathHamcrest,
                testClasspathOpentest4j,
                testClasspathCommons);

        setProjectDepedenciesToMojo(testArtifacts.toArray(new Artifact[0]));

        File classesDirectory = new File("target/classes");

        File testClassesDirectory = new File("target/test-classes");

        TestClassPath testClasspathWrapper =
                new TestClassPath(testArtifacts, classesDirectory, testClassesDirectory, null);

        SurefireDependencyResolver dependencyResolver = mock(SurefireDependencyResolver.class);
        mojo.setSurefireDependencyResolver(dependencyResolver);

        when(dependencyResolver.getProviderClasspathAsMap(any(), any(), anyString(), anyString()))
                .thenReturn(artifactMapByVersionlessId(createSurefireProviderResolutionResult(surefireVersion)));

        when(dependencyResolver.resolveArtifacts(any(), any(), any(Artifact.class)))
                .thenReturn(createExpectedJUnitPlatformLauncherResolutionResult());

        mojo.setLogger(mock(Logger.class));

        Artifact junitPlatformArtifact = invokeMethod(mojo, "getJUnit5Artifact");
        assertThat(junitPlatformArtifact.getGroupId()).isEqualTo("org.junit.platform");
        assertThat(junitPlatformArtifact.getArtifactId()).isEqualTo("junit-platform-commons");
        assertThat(junitPlatformArtifact.getVersion().toString()).isEqualTo("1.4.0");

        AbstractSurefireMojo.JUnitPlatformProviderInfo prov =
                mojo.createJUnitPlatformProviderInfo(junitPlatformArtifact, testClasspathWrapper);

        assertThat(prov.isApplicable()).isTrue();

        StubDependency expectedProvider =
                new StubDependency("org.apache.maven.surefire", "surefire-junit-platform", surefireVersion, "jar");
        StubDependency expectedCommonJava5 =
                new StubDependency("org.apache.maven.surefire", "common-java5", surefireVersion, "jar");
        StubDependency expectedLauncher =
                new StubDependency("org.junit.platform", "junit-platform-launcher", "1.4.0", "jar");
        assertThat(prov.getProviderClasspath())
                .hasSize(3)
                .containsOnly(expectedProvider, expectedCommonJava5, expectedLauncher);

        assertThat(testClasspathWrapper.getTestDependencies())
                .hasSize(8)
                .containsEntry("third.party:artifact", testClasspathSomeTestArtifact)
                .containsEntry("org.junit.vintage:junit-vintage-engine", testClasspathVintage)
                .containsEntry("org.apiguardian:apiguardian-api", testClasspathApiguardian)
                .containsEntry("org.junit.platform:junit-platform-engine", testClasspathPlatformEng)
                .containsEntry("junit:junit", testClasspathJUnit4)
                .containsEntry("org.hamcrest:hamcrest-core", testClasspathHamcrest)
                .containsEntry("org.opentest4j:opentest4j", testClasspathOpentest4j)
                .containsEntry("org.junit.platform:junit-platform-commons", testClasspathCommons);
    }

    @Test
    public void shouldSmartlyResolveJUnit5ProviderWithJUnit5Commons() throws Exception {
        mojo.setProject(TestSessions.project("dummy", "pom", "1.0.0"));

        String surefireVersion = "1";

        StubDependency testClasspathSomeTestArtifact = new StubDependency("third.party", "artifact", "1.0", "jar");

        StubDependency testClasspathCommons =
                new StubDependency("org.junit.platform", "junit-platform-commons", "1.4.0", "jar");

        StubDependency testClasspathApiguardian =
                new StubDependency("org.apiguardian", "apiguardian-api", "1.0.0", "jar");

        Collection<DownloadedDependency> testArtifacts =
                asList(testClasspathSomeTestArtifact, testClasspathApiguardian, testClasspathCommons);

        setProjectDepedenciesToMojo(testArtifacts.toArray(new Artifact[0]));

        File classesDirectory = new File("target/classes");

        File testClassesDirectory = new File("target/test-classes");

        TestClassPath testClasspathWrapper =
                new TestClassPath(testArtifacts, classesDirectory, testClassesDirectory, null);

        SurefireDependencyResolver dependencyResolver = mock(SurefireDependencyResolver.class);
        mojo.setSurefireDependencyResolver(dependencyResolver);

        when(dependencyResolver.getProviderClasspathAsMap(any(), any(), anyString(), anyString()))
                .thenReturn(artifactMapByVersionlessId(createSurefireProviderResolutionResult(surefireVersion)));

        when(dependencyResolver.resolveArtifacts(any(), any(), any(Artifact.class)))
                .thenReturn(createExpectedJUnitPlatformLauncherResolutionResult());

        mojo.setLogger(mock(Logger.class));

        invokeMethod(mojo, "setupStuff");

        Artifact junitPlatformArtifact = invokeMethod(mojo, "getJUnit5Artifact");
        assertThat(junitPlatformArtifact.getGroupId()).isEqualTo("org.junit.platform");
        assertThat(junitPlatformArtifact.getArtifactId()).isEqualTo("junit-platform-commons");
        assertThat(junitPlatformArtifact.getVersion().toString()).isEqualTo("1.4.0");

        AbstractSurefireMojo.JUnitPlatformProviderInfo prov =
                mojo.createJUnitPlatformProviderInfo(junitPlatformArtifact, testClasspathWrapper);

        assertThat(prov.isApplicable()).isTrue();

        Set<DownloadedArtifact> resolvedProviderArtifacts = prov.getProviderClasspath();

        StubDependency provider =
                new StubDependency("org.apache.maven.surefire", "surefire-junit-platform", surefireVersion, "jar");
        StubDependency java5 = new StubDependency("org.apache.maven.surefire", "common-java5", surefireVersion, "jar");
        StubDependency launcher = new StubDependency("org.junit.platform", "junit-platform-launcher", "1.4.0", "jar");
        StubDependency engine = new StubDependency("org.junit.platform", "junit-platform-engine", "1.4.0", "jar");
        StubDependency opentest4j = new StubDependency("org.opentest4j", "opentest4j", "1.1.1", "jar");
        assertThat(resolvedProviderArtifacts).hasSize(5).containsOnly(provider, java5, launcher, engine, opentest4j);

        assertThat(testClasspathWrapper.getTestDependencies())
                .hasSize(3)
                .containsEntry("third.party:artifact", testClasspathSomeTestArtifact)
                .containsEntry("org.junit.platform:junit-platform-commons", testClasspathCommons)
                .containsEntry("org.apiguardian:apiguardian-api", testClasspathApiguardian);
    }

    @Test
    public void shouldSmartlyResolveJUnit5ProviderWithJUnit5Engine() throws Exception {
        mojo.setProject(TestSessions.project("dummy", "pom", "1.0.0"));

        String surefireVersion = "1";

        StubDependency testClasspathSomeTestArtifact = new StubDependency("third.party", "artifact", "1.0", "jar");

        StubDependency testClasspathJUnit5 =
                new StubDependency("org.junit.platform", "junit-platform-engine", "1.4.0", "jar");

        StubDependency testClasspathApiguardian =
                new StubDependency("org.apiguardian", "apiguardian-api", "1.0.0", "jar");

        StubDependency testClasspathCommons =
                new StubDependency("org.junit.platform", "junit-platform-commons", "1.4.0", "jar");

        StubDependency testClasspathOpentest4j = new StubDependency("org.opentest4j", "opentest4j", "1.1.1", "jar");

        Collection<DownloadedDependency> testArtifacts = asList(
                testClasspathSomeTestArtifact,
                testClasspathJUnit5,
                testClasspathApiguardian,
                testClasspathCommons,
                testClasspathOpentest4j);

        setProjectDepedenciesToMojo(testArtifacts.toArray(new Artifact[0]));

        File classesDirectory = new File("target/classes");

        File testClassesDirectory = new File("target/test-classes");

        TestClassPath testClasspathWrapper =
                new TestClassPath(testArtifacts, classesDirectory, testClassesDirectory, null);

        SurefireDependencyResolver dependencyResolver = mock(SurefireDependencyResolver.class);
        mojo.setSurefireDependencyResolver(dependencyResolver);

        when(dependencyResolver.getProviderClasspathAsMap(any(), any(), anyString(), anyString()))
                .thenReturn(artifactMapByVersionlessId(createSurefireProviderResolutionResult(surefireVersion)));

        when(dependencyResolver.resolveArtifacts(any(), any(), any(Artifact.class)))
                .thenReturn(createExpectedJUnitPlatformLauncherResolutionResult());

        mojo.setLogger(mock(Logger.class));

        invokeMethod(mojo, "setupStuff");

        Artifact junitPlatformArtifact = invokeMethod(mojo, "getJUnit5Artifact");
        assertThat(junitPlatformArtifact.getGroupId()).isEqualTo("org.junit.platform");
        assertThat(junitPlatformArtifact.getArtifactId()).isEqualTo("junit-platform-commons");
        assertThat(junitPlatformArtifact.getVersion().toString()).isEqualTo("1.4.0");

        AbstractSurefireMojo.JUnitPlatformProviderInfo prov =
                mojo.createJUnitPlatformProviderInfo(junitPlatformArtifact, testClasspathWrapper);

        assertThat(prov.isApplicable()).isTrue();

        StubDependency surefireProvider =
                new StubDependency("org.apache.maven.surefire", "surefire-junit-platform", surefireVersion, "jar");

        StubDependency java5 = new StubDependency("org.apache.maven.surefire", "common-java5", surefireVersion, "jar");
        StubDependency launcher = new StubDependency("org.junit.platform", "junit-platform-launcher", "1.4.0", "jar");

        Set<DownloadedArtifact> resolvedProviderArtifacts = prov.getProviderClasspath();

        assertThat(resolvedProviderArtifacts).hasSize(3).containsOnly(surefireProvider, java5, launcher);

        assertThat(testClasspathWrapper.getTestDependencies())
                .hasSize(5)
                .containsEntry("third.party:artifact", testClasspathSomeTestArtifact)
                .containsEntry("org.junit.platform:junit-platform-engine", testClasspathJUnit5)
                .containsEntry("org.apiguardian:apiguardian-api", testClasspathApiguardian)
                .containsEntry("org.junit.platform:junit-platform-commons", testClasspathCommons)
                .containsEntry("org.opentest4j:opentest4j", testClasspathOpentest4j);
    }

    @Test
    public void shouldSmartlyResolveJUnit5ProviderWithJupiterApi() throws Exception {
        mojo.setProject(TestSessions.project("dummy", "pom", "1.0.0"));

        String surefireVersion = "1";

        StubDependency testClasspathSomeTestArtifact = new StubDependency("third.party", "artifact", "1.0", "jar");

        StubDependency testClasspathJupiterApi =
                new StubDependency("org.junit.jupiter", "junit-jupiter-api", "5.4.0", "jar");

        StubDependency testClasspathApiguardian =
                new StubDependency("org.apiguardian", "apiguardian-api", "1.0.0", "jar");

        StubDependency testClasspathCommons =
                new StubDependency("org.junit.platform", "junit-platform-commons", "1.4.0", "jar");

        StubDependency testClasspathOpentest4j = new StubDependency("org.opentest4j", "opentest4j", "1.1.1", "jar");

        Collection<DownloadedDependency> testArtifacts = asList(
                testClasspathSomeTestArtifact,
                testClasspathJupiterApi,
                testClasspathApiguardian,
                testClasspathCommons,
                testClasspathOpentest4j);

        setProjectDepedenciesToMojo(testArtifacts.toArray(new Artifact[0]));

        File classesDirectory = new File("target/classes");

        File testClassesDirectory = new File("target/test-classes");

        TestClassPath testClasspathWrapper =
                new TestClassPath(testArtifacts, classesDirectory, testClassesDirectory, null);

        SurefireDependencyResolver dependencyResolver = mock(SurefireDependencyResolver.class);
        mojo.setSurefireDependencyResolver(dependencyResolver);

        when(dependencyResolver.getProviderClasspathAsMap(any(), any(), anyString(), anyString()))
                .thenReturn(artifactMapByVersionlessId(createSurefireProviderResolutionResult(surefireVersion)));

        StubDependency jUnitPlatformLauncherArtifact =
                new StubDependency("org.junit.platform", "junit-platform-launcher", "1.4.0", "jar");

        when(dependencyResolver.resolveArtifacts(any(), any(), sameCoordinates(jUnitPlatformLauncherArtifact)))
                .thenReturn(createExpectedJUnitPlatformLauncherResolutionResult());

        StubDependency jupiterEngineArtifact =
                new StubDependency("org.junit.jupiter", "junit-jupiter-engine", "5.4.0", "jar");

        when(dependencyResolver.resolveArtifacts(any(), any(), sameCoordinates(jupiterEngineArtifact)))
                .thenReturn(createJupiterEngineResolutionResult());

        mojo.setLogger(mock(Logger.class));

        Artifact junitPlatformArtifact = invokeMethod(mojo, "getJUnit5Artifact");
        assertThat(junitPlatformArtifact.getGroupId()).isEqualTo("org.junit.platform");
        assertThat(junitPlatformArtifact.getArtifactId()).isEqualTo("junit-platform-commons");
        assertThat(junitPlatformArtifact.getVersion().toString()).isEqualTo("1.4.0");

        AbstractSurefireMojo.JUnitPlatformProviderInfo prov =
                mojo.createJUnitPlatformProviderInfo(junitPlatformArtifact, testClasspathWrapper);

        assertThat(prov.isApplicable()).isTrue();

        StubDependency surefireProvider =
                new StubDependency("org.apache.maven.surefire", "surefire-junit-platform", surefireVersion, "jar");

        StubDependency java5 = new StubDependency("org.apache.maven.surefire", "common-java5", surefireVersion, "jar");
        StubDependency launcher = new StubDependency("org.junit.platform", "junit-platform-launcher", "1.4.0", "jar");
        StubDependency jupiterEngine = new StubDependency(
                "org.junit.jupiter",
                "junit-jupiter-engine", ///// <------
                "5.4.0",
                "jar");
        StubDependency platformEngine =
                new StubDependency("org.junit.platform", "junit-platform-engine", "1.4.0", "jar");

        Set<DownloadedArtifact> resolvedProviderArtifacts = prov.getProviderClasspath();

        assertThat(resolvedProviderArtifacts)
                .hasSize(5)
                .containsOnly(surefireProvider, java5, launcher, jupiterEngine, platformEngine);

        assertThat(testClasspathWrapper.getTestDependencies())
                .hasSize(5)
                .containsEntry("third.party:artifact", testClasspathSomeTestArtifact)
                .containsEntry("org.junit.jupiter:junit-jupiter-api", testClasspathJupiterApi)
                .containsEntry("org.apiguardian:apiguardian-api", testClasspathApiguardian)
                .containsEntry("org.junit.platform:junit-platform-commons", testClasspathCommons)
                .containsEntry("org.opentest4j:opentest4j", testClasspathOpentest4j);
    }

    @Test
    public void shouldSmartlyResolveJUnit5ProviderWithJupiterEngine() throws Exception {
        mojo.setProject(TestSessions.project("dummy", "pom", "1.0.0"));

        String surefireVersion = "1";

        StubDependency testClasspathSomeTestArtifact = new StubDependency("third.party", "artifact", "1.0", "jar");

        StubDependency testClasspathJupiterEngine =
                new StubDependency("org.junit.jupiter", "junit-jupiter-engine", "5.4.0", "jar");

        StubDependency testClasspathPlatformEngine =
                new StubDependency("org.junit.platform", "junit-platform-engine", "1.4.0", "jar");

        StubDependency testClasspathJupiterApi =
                new StubDependency("org.junit.jupiter", "junit-jupiter-api", "5.4.0", "jar");

        StubDependency testClasspathApiguardian =
                new StubDependency("org.apiguardian", "apiguardian-api", "1.0.0", "jar");

        StubDependency testClasspathCommons =
                new StubDependency("org.junit.platform", "junit-platform-commons", "1.4.0", "jar");

        StubDependency testClasspathOpentest4j = new StubDependency("org.opentest4j", "opentest4j", "1.1.1", "jar");

        Collection<DownloadedDependency> testArtifacts = asList(
                testClasspathSomeTestArtifact,
                testClasspathJupiterEngine,
                testClasspathPlatformEngine,
                testClasspathJupiterApi,
                testClasspathApiguardian,
                testClasspathCommons,
                testClasspathOpentest4j);

        setProjectDepedenciesToMojo(testArtifacts.toArray(new Artifact[0]));

        File classesDirectory = new File("target/classes");

        File testClassesDirectory = new File("target/test-classes");

        TestClassPath testClasspathWrapper =
                new TestClassPath(testArtifacts, classesDirectory, testClassesDirectory, null);

        SurefireDependencyResolver dependencyResolver = mock(SurefireDependencyResolver.class);
        mojo.setSurefireDependencyResolver(dependencyResolver);

        when(dependencyResolver.getProviderClasspathAsMap(any(), any(), anyString(), anyString()))
                .thenReturn(artifactMapByVersionlessId(createSurefireProviderResolutionResult(surefireVersion)));

        when(dependencyResolver.resolveArtifacts(any(), any(), any(Artifact.class)))
                .thenReturn(createExpectedJUnitPlatformLauncherResolutionResult());

        mojo.setLogger(mock(Logger.class));

        Artifact junitPlatformArtifact = invokeMethod(mojo, "getJUnit5Artifact");
        assertThat(junitPlatformArtifact.getGroupId()).isEqualTo("org.junit.platform");
        assertThat(junitPlatformArtifact.getArtifactId()).isEqualTo("junit-platform-commons");
        assertThat(junitPlatformArtifact.getVersion().toString()).isEqualTo("1.4.0");

        AbstractSurefireMojo.JUnitPlatformProviderInfo prov =
                mojo.createJUnitPlatformProviderInfo(junitPlatformArtifact, testClasspathWrapper);

        assertThat(prov.isApplicable()).isTrue();

        StubDependency surefireProvider =
                new StubDependency("org.apache.maven.surefire", "surefire-junit-platform", surefireVersion, "jar");
        StubDependency java5 = new StubDependency("org.apache.maven.surefire", "common-java5", surefireVersion, "jar");
        StubDependency launcher = new StubDependency("org.junit.platform", "junit-platform-launcher", "1.4.0", "jar");

        Set<DownloadedArtifact> resolvedProviderArtifacts = prov.getProviderClasspath();

        assertThat(resolvedProviderArtifacts).hasSize(3).containsOnly(surefireProvider, java5, launcher);

        assertThat(testClasspathWrapper.getTestDependencies())
                .hasSize(7)
                .containsEntry("third.party:artifact", testClasspathSomeTestArtifact)
                .containsEntry("org.junit.jupiter:junit-jupiter-engine", testClasspathJupiterEngine)
                .containsEntry("org.junit.platform:junit-platform-engine", testClasspathPlatformEngine)
                .containsEntry("org.junit.jupiter:junit-jupiter-api", testClasspathJupiterApi)
                .containsEntry("org.apiguardian:apiguardian-api", testClasspathApiguardian)
                .containsEntry("org.junit.platform:junit-platform-commons", testClasspathCommons)
                .containsEntry("org.opentest4j:opentest4j", testClasspathOpentest4j);
    }

    @Test
    public void shouldSmartlyResolveJUnit5ProviderWithJupiterEngineInPluginDependencies() throws Exception {
        String surefireVersion = "1";

        StubDependency plugin =
                new StubDependency("org.apache.maven.surefire", "maven-surefire-plugin", surefireVersion, "jar");

        StubDependency forkedBooter =
                new StubDependency("org.apache.maven.surefire", "surefire-booter", surefireVersion, "jar");

        StubDependency pluginDepJupiterEngine =
                new StubDependency("org.junit.jupiter", "junit-jupiter-engine", "5.4.0", "jar");

        StubDependency pluginDepPlatformEngine =
                new StubDependency("org.junit.platform", "junit-platform-engine", "1.4.0", "jar");

        StubDependency pluginDepJupiterApi =
                new StubDependency("org.junit.jupiter", "junit-jupiter-api", "5.4.0", "jar");

        StubDependency pluginDepApiguardian = new StubDependency("org.apiguardian", "apiguardian-api", "1.0.0", "jar");

        StubDependency pluginDepCommons =
                new StubDependency("org.junit.platform", "junit-platform-commons", "1.4.0", "jar");

        StubDependency pluginDepOpentest4j = new StubDependency("org.opentest4j", "opentest4j", "1.1.1", "jar");

        addPluginDependencies(
                plugin,
                forkedBooter,
                pluginDepJupiterEngine,
                pluginDepPlatformEngine,
                pluginDepJupiterApi,
                pluginDepApiguardian,
                pluginDepCommons,
                pluginDepOpentest4j);

        mojo.setProject(TestSessions.project("dummy", "pom", "1.0.0"));

        StubDependency testClasspathSomeTestArtifact = new StubDependency("third.party", "artifact", "1.0", "jar");

        StubDependency testClasspathJupiterApi =
                new StubDependency("org.junit.jupiter", "junit-jupiter-api", "5.3.0", "jar");

        StubDependency testClasspathApiguardian =
                new StubDependency("org.apiguardian", "apiguardian-api", "1.0.0", "jar");

        StubDependency testClasspathCommons =
                new StubDependency("org.junit.platform", "junit-platform-commons", "1.4.0", "jar");

        StubDependency testClasspathOpentest4j = new StubDependency("org.opentest4j", "opentest4j", "1.1.1", "jar");

        Collection<DownloadedDependency> testArtifacts = asList(
                testClasspathSomeTestArtifact,
                testClasspathJupiterApi,
                testClasspathApiguardian,
                testClasspathCommons,
                testClasspathOpentest4j);

        setProjectDepedenciesToMojo(testArtifacts.toArray(new Artifact[0]));

        File classesDirectory = new File("target/classes");

        File testClassesDirectory = new File("target/test-classes");

        TestClassPath testClasspathWrapper =
                new TestClassPath(testArtifacts, classesDirectory, testClassesDirectory, null);

        SurefireDependencyResolver dependencyResolver = mock(SurefireDependencyResolver.class);
        mojo.setSurefireDependencyResolver(dependencyResolver);

        when(dependencyResolver.getProviderClasspathAsMap(any(), any(), anyString(), anyString()))
                .thenReturn(artifactMapByVersionlessId(createSurefireProviderResolutionResult(surefireVersion)));

        when(dependencyResolver.resolveArtifacts(any(), any(), any(Artifact.class)))
                .thenReturn(createExpectedJUnitPlatformLauncherResolutionResult());

        mojo.setLogger(mock(Logger.class));

        Set<DownloadedArtifact> pluginDepJupiterEngineArtifacts = new HashSet<>();
        pluginDepJupiterEngineArtifacts.add(pluginDepJupiterEngine);
        pluginDepJupiterEngineArtifacts.add(pluginDepPlatformEngine);
        pluginDepJupiterEngineArtifacts.add(pluginDepJupiterApi);
        pluginDepJupiterEngineArtifacts.add(pluginDepApiguardian);
        pluginDepJupiterEngineArtifacts.add(pluginDepCommons);
        pluginDepJupiterEngineArtifacts.add(pluginDepOpentest4j);

        when(dependencyResolver.resolvePluginDependencies(any(), any(), any(), any()))
                .thenReturn(artifactMapByVersionlessId(pluginDepJupiterEngineArtifacts));

        Artifact junitPlatformArtifact = invokeMethod(mojo, "getJUnit5Artifact");
        assertThat(junitPlatformArtifact.getGroupId()).isEqualTo("org.junit.platform");
        assertThat(junitPlatformArtifact.getArtifactId()).isEqualTo("junit-platform-engine");
        assertThat(junitPlatformArtifact.getVersion().toString()).isEqualTo("1.4.0");

        AbstractSurefireMojo.JUnitPlatformProviderInfo prov =
                mojo.createJUnitPlatformProviderInfo(junitPlatformArtifact, testClasspathWrapper);

        assertThat(prov.isApplicable()).isTrue();

        StubDependency surefireProvider =
                new StubDependency("org.apache.maven.surefire", "surefire-junit-platform", surefireVersion, "jar");
        StubDependency java5 = new StubDependency("org.apache.maven.surefire", "common-java5", surefireVersion, "jar");
        StubDependency launcher = new StubDependency("org.junit.platform", "junit-platform-launcher", "1.4.0", "jar");
        StubDependency jupiterEngine = new StubDependency("org.junit.jupiter", "junit-jupiter-engine", "5.4.0", "jar");
        StubDependency platformEngine =
                new StubDependency("org.junit.platform", "junit-platform-engine", "1.4.0", "jar");

        Set<DownloadedArtifact> resolvedProviderArtifacts = prov.getProviderClasspath();

        assertThat(resolvedProviderArtifacts)
                .hasSize(5)
                .containsOnly(surefireProvider, java5, launcher, jupiterEngine, platformEngine);

        assertThat(testClasspathWrapper.getTestDependencies())
                .hasSize(5)
                .containsEntry("third.party:artifact", testClasspathSomeTestArtifact)
                .containsEntry("org.junit.jupiter:junit-jupiter-api", testClasspathJupiterApi)
                .containsEntry("org.apiguardian:apiguardian-api", testClasspathApiguardian)
                .containsEntry("org.junit.platform:junit-platform-commons", testClasspathCommons)
                .containsEntry("org.opentest4j:opentest4j", testClasspathOpentest4j);
    }

    @Test
    public void shouldConvertJunitEngineParameters() throws Exception {
        Properties properties = new Properties();
        setInternalState(mojo, "properties", properties);

        invokeMethod(mojo, "convertJunitEngineParameters");
        assertThat(properties).isEmpty();

        mojo.setIncludeJUnit5Engines(new String[0]);
        mojo.setExcludeJUnit5Engines(new String[0]);
        invokeMethod(mojo, "convertJunitEngineParameters");
        assertThat(properties).isEmpty();

        mojo.setIncludeJUnit5Engines(new String[] {"e1", "e2"});
        invokeMethod(mojo, "convertJunitEngineParameters");
        assertThat(properties).containsEntry("includejunit5engines", "e1,e2");

        mojo.setExcludeJUnit5Engines(new String[] {"e1", "e2"});
        invokeMethod(mojo, "convertJunitEngineParameters");
        assertThat(properties).containsEntry("excludejunit5engines", "e1,e2");
    }

    private static Set<DownloadedArtifact> createJUnitPlatformLauncherResolutionResult(
            StubDependency junit5Engine,
            StubDependency apiguardian,
            StubDependency commons,
            StubDependency opentest4j) {
        Set<DownloadedArtifact> resolvedLauncherArtifacts = new HashSet<>();
        StubDependency launcher = new StubDependency(
                "org.junit.platform",
                "junit-platform-launcher",
                commons.getVersion().toString(),
                "jar");
        resolvedLauncherArtifacts.add(launcher);
        resolvedLauncherArtifacts.add(apiguardian);
        resolvedLauncherArtifacts.add(junit5Engine);
        resolvedLauncherArtifacts.add(commons);
        resolvedLauncherArtifacts.add(opentest4j);
        resolvedLauncherArtifacts.remove(null);
        return resolvedLauncherArtifacts;
    }

    private static Set<DownloadedArtifact> createJupiterEngineResolutionResult() {
        Set<DownloadedArtifact> resolvedLauncherArtifacts = new HashSet<>();
        resolvedLauncherArtifacts.add(new StubDependency("org.junit.jupiter", "junit-jupiter-engine", "5.4.0", "jar"));
        resolvedLauncherArtifacts.add(new StubDependency("org.junit.jupiter", "junit-jupiter-api", "5.4.0", "jar"));
        resolvedLauncherArtifacts.add(
                new StubDependency("org.junit.platform", "junit-platform-engine", "1.4.0", "jar"));
        resolvedLauncherArtifacts.add(new StubDependency("org.apiguardian", "apiguardian-api", "1.0.0", "jar"));
        resolvedLauncherArtifacts.add(new StubDependency("org.opentest4j", "opentest4j", "1.1.1", "jar"));
        resolvedLauncherArtifacts.add(
                new StubDependency("org.junit.platform", "junit-platform-commons", "1.4.0", "jar"));
        return resolvedLauncherArtifacts;
    }

    private static Set<DownloadedArtifact> createExpectedJUnitPlatformLauncherResolutionResult() {
        StubDependency engine = new StubDependency("org.junit.platform", "junit-platform-engine", "1.4.0", "jar");
        StubDependency commons = new StubDependency("org.junit.platform", "junit-platform-commons", "1.4.0", "jar");
        StubDependency apiguardian = new StubDependency("org.apiguardian", "apiguardian-api", "1.0.0", "jar");
        StubDependency opentest4j = new StubDependency("org.opentest4j", "opentest4j", "1.1.1", "jar");
        return createJUnitPlatformLauncherResolutionResult(engine, apiguardian, commons, opentest4j);
    }

    private static Set<DownloadedArtifact> createSurefireProviderResolutionResult(String surefireVersion) {

        StubDependency provider =
                new StubDependency("org.apache.maven.surefire", "surefire-junit-platform", surefireVersion, "jar");
        StubDependency java5 = new StubDependency("org.apache.maven.surefire", "common-java5", surefireVersion, "jar");
        StubDependency launcher = new StubDependency("org.junit.platform", "junit-platform-launcher", "1.3.2", "jar");
        StubDependency apiguardian = new StubDependency("org.apiguardian", "apiguardian-api", "1.0.0", "jar");
        StubDependency engine = new StubDependency("org.junit.platform", "junit-platform-engine", "1.3.2", "jar");
        StubDependency commons = new StubDependency("org.junit.platform", "junit-platform-commons", "1.3.2", "jar");
        StubDependency opentest4j = new StubDependency("org.opentest4j", "opentest4j", "1.1.1", "jar");

        Set<DownloadedArtifact> providerArtifacts = new HashSet<>();
        providerArtifacts.add(provider);
        providerArtifacts.add(java5);
        providerArtifacts.add(launcher);
        providerArtifacts.add(apiguardian);
        providerArtifacts.add(engine);
        providerArtifacts.add(commons);
        providerArtifacts.add(opentest4j);

        return providerArtifacts;
    }

    @Test
    public void shouldVerifyConfigParameters() throws Exception {

        Mojo mojo = new Mojo() {
            @Override
            public File getTestClassesDirectory() {
                return new File(System.getProperty("user.dir"), "target/test-classes");
            }

            @Override
            protected String getEnableProcessChecker() {
                return "fake";
            }
        };

        MojoException m = assertThrows(MojoException.class, mojo::verifyParameters);
        assertThat(m.getMessage())
                .isEqualTo("Unexpected value 'fake' in the configuration parameter 'enableProcessChecker'.");
    }

    private void setProjectDepedenciesToMojo(Artifact... deps) {
        for (Artifact dep : deps) {
            mojo.getProjectArtifactMap().put(dep.getGroupId() + ":" + dep.getArtifactId(), dep);
        }
    }

    private void addPluginDependencies(Artifact... deps) {
        for (Artifact dep : deps) {
            mojo.getPluginArtifactMap().put(dep.getGroupId() + ":" + dep.getArtifactId(), dep);
        }
    }

    /**
     *
     */
    public static class Mojo extends AbstractSurefireMojo implements SurefireReportParameters {
        private File mainBuildPath;

        private File testClassesDirectory;

        private boolean useModulePath;

        private int failOnFlakeCount;

        private String[] includeJUnit5Engines;

        private String[] excludeJUnit5Engines;

        private List<DownloadedDependency> projectTestArtifacts;

        private File includesFile;

        private File excludesFile;

        private List<String> includes;

        private List<String> excludes;

        private String test;

        private boolean testFailureIgnore;

        private JUnitPlatformProviderInfo createJUnitPlatformProviderInfo(
                Artifact junitPlatformArtifact, TestClassPath testClasspathWrapper) {
            return new JUnitPlatformProviderInfo(
                    null,
                    junitPlatformArtifact,
                    testClasspathWrapper,
                    null,
                    getBooterArtifact(),
                    getSurefireDependencyResolver(),
                    getSession(),
                    mock(Project.class),
                    getPlugin(),
                    getPluginArtifactMap(),
                    getConsoleLogger(),
                    null);
        }

        void setProjectTestArtifacts(List<DownloadedDependency> projectTestArtifacts) {
            this.projectTestArtifacts = projectTestArtifacts;
        }

        @Override
        List<DownloadedDependency> getProjectTestArtifacts() {
            return projectTestArtifacts;
        }

        @Override
        protected void logDebugOrCliShowErrors(String s) {
            // do nothing
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
        public boolean isTestFailureIgnore() {
            return testFailureIgnore;
        }

        @Override
        public void setTestFailureIgnore(boolean testFailureIgnore) {
            this.testFailureIgnore = testFailureIgnore;
        }

        @Override
        public File getBasedir() {
            return null;
        }

        @Override
        public void setBasedir(File basedir) {}

        @Override
        public File getTestClassesDirectory() {
            return testClassesDirectory;
        }

        @Override
        public void setTestClassesDirectory(File testClassesDirectory) {
            this.testClassesDirectory = testClassesDirectory;
        }

        @Override
        public File getMainBuildPath() {
            return mainBuildPath;
        }

        @Override
        public void setMainBuildPath(File mainBuildPath) {
            this.mainBuildPath = mainBuildPath;
        }

        @Override
        public File getReportsDirectory() {
            return null;
        }

        @Override
        public void setReportsDirectory(File reportsDirectory) {}

        @Override
        public String getTest() {
            return test;
        }

        @Override
        public void setTest(String test) {
            this.test = test;
        }

        @Override
        public List<String> getIncludes() {
            return includes;
        }

        @Override
        public void setIncludes(List<String> includes) {
            this.includes = includes;
        }

        void setIncludesFile(File includesFile) {
            this.includesFile = includesFile;
        }

        @Override
        public File getIncludesFile() {
            return includesFile;
        }

        @Override
        public List<String> getExcludes() {
            return excludes;
        }

        @Override
        public void setExcludes(List<String> excludes) {
            this.excludes = excludes;
        }

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

        void setExcludesFile(File excludesFile) {
            this.excludesFile = excludesFile;
        }

        @Override
        public File getExcludesFile() {
            return excludesFile;
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
            return null;
        }

        @Override
        public void setRunOrderStatisticsFileChecksum(String runOrderStatisticsFileChecksum) {}

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
            return useModulePath;
        }

        @Override
        protected void setUseModulePath(boolean useModulePath) {
            this.useModulePath = useModulePath;
        }

        @Override
        protected String getEnableProcessChecker() {
            return null;
        }

        @Override
        protected ForkNodeFactory getForkNode() {
            return null;
        }

        @Override
        protected Artifact getMojoArtifact() {
            return new StubDependency("org.apache.maven.surefire", "maven-surefire-plugin", "1", "jar");
        }

        @Override
        public File getSystemPropertiesFile() {
            return null;
        }

        @Override
        public void setSystemPropertiesFile(File systemPropertiesFile) {}

        public void setToolchain(Toolchain toolchain) {
            setInternalState(this, "toolchain", toolchain);
        }

        public void setJvm(String jvm) {
            setInternalState(this, "jvm", jvm);
        }

        @Override
        public int getFailOnFlakeCount() {
            return failOnFlakeCount;
        }

        @Override
        public void setFailOnFlakeCount(int failOnFlakeCount) {
            this.failOnFlakeCount = failOnFlakeCount;
        }

        @Override
        public String[] getIncludeJUnit5Engines() {
            return includeJUnit5Engines;
        }

        @Override
        public void setIncludeJUnit5Engines(String[] includeJUnit5Engines) {
            this.includeJUnit5Engines = includeJUnit5Engines;
        }

        @Override
        public String[] getExcludeJUnit5Engines() {
            return excludeJUnit5Engines;
        }

        @Override
        public void setExcludeJUnit5Engines(String[] excludeJUnit5Engines) {
            this.excludeJUnit5Engines = excludeJUnit5Engines;
        }
    }

    @Test
    public void shouldNotPerformMethodFilteringOnIncludes() throws Exception {
        Mojo plugin = new Mojo();

        File includesExcludes = SureFireFileManager.createTempFile("surefire", "includes");
        FileUtils.write(includesExcludes, "AnotherTest#method", UTF_8);
        plugin.setIncludesFile(includesExcludes);

        List<String> includes = new LinkedList<>();
        includes.add("AnotherTest#method ");
        plugin.setIncludes(includes);

        String version = "1.0";
        StubDependency testDeps = new StubDependency("g", "a", version, "jar", DependencyScope.forId("compile"));
        File artifactFile = SureFireFileManager.createTempFile("surefire", ".jar");
        artifactFile.deleteOnExit();
        testDeps.setPath(pathOf(artifactFile));
        plugin.setProjectTestArtifacts(singletonList(testDeps));
        plugin.setDependenciesToScan(new String[] {"g:a"});

        Exception e = assertThrows(Exception.class, plugin::scanDependencies);
        assertThat(e.getMessage())
                .isEqualTo("Method filter prohibited in includes|excludes parameter: AnotherTest#method ");
    }

    @Test
    public void shouldFilterTestsOnIncludesFile() throws Exception {
        Mojo plugin = new Mojo();

        plugin.setLogger(mock(Logger.class));

        File includes = SureFireFileManager.createTempFile("surefire", "includes");
        FileUtils.write(includes, "AnotherTest#method", UTF_8);
        plugin.setIncludesFile(includes);

        String version = "1.0";
        StubDependency testDeps = new StubDependency("g", "a", version, "test-jar", DependencyScope.forId("compile"));
        File artifactFile = SureFireFileManager.createTempFile("surefire", "classes");
        String classDir = artifactFile.getCanonicalPath();
        assertThat(artifactFile.delete()).isTrue();
        File classes = new File(classDir);
        assertThat(classes.mkdir()).isTrue();
        testDeps.setPath(pathOf(classes));
        assertThat(new File(classes, "AnotherTest.class").createNewFile()).isTrue();
        plugin.setProjectTestArtifacts(singletonList(testDeps));
        plugin.setDependenciesToScan(new String[] {"g:a"});

        DefaultScanResult result = plugin.scanDependencies();
        assertThat(result.getClasses()).hasSize(1);
        assertThat(result.getClasses().iterator().next()).isEqualTo("AnotherTest");
    }

    @Test
    public void shouldFilterTestsOnExcludesFile() throws Exception {
        Mojo plugin = new Mojo();

        plugin.setLogger(mock(Logger.class));

        File excludes = SureFireFileManager.createTempFile("surefire", "-excludes");
        FileUtils.write(excludes, "AnotherTest", UTF_8);
        plugin.setExcludesFile(excludes);

        String version = "1.0";
        StubDependency testDeps = new StubDependency("g", "a", version, "test-jar", DependencyScope.forId("compile"));
        File artifactFile = SureFireFileManager.createTempFile("surefire", "-classes");
        String classDir = artifactFile.getCanonicalPath();
        assertThat(artifactFile.delete()).isTrue();
        File classes = new File(classDir);
        assertThat(classes.mkdir()).isTrue();
        testDeps.setPath(pathOf(classes));
        assertThat(new File(classes, "AnotherTest.class").createNewFile()).isTrue();
        plugin.setProjectTestArtifacts(singletonList(testDeps));
        plugin.setDependenciesToScan(new String[] {"g:a"});

        DefaultScanResult result = plugin.scanDependencies();
        assertThat(result.getClasses()).isEmpty();
    }

    @Test
    public void shouldFilterTestsOnExcludes() throws Exception {
        Mojo plugin = new Mojo();

        plugin.setLogger(mock(Logger.class));

        plugin.setExcludes(singletonList("AnotherTest"));

        String version = "1.0";
        StubDependency testDeps = new StubDependency("g", "a", version, "jar", DependencyScope.forId("compile"));
        File artifactFile = SureFireFileManager.createTempFile("surefire", "-classes");
        String classDir = artifactFile.getCanonicalPath();
        assertThat(artifactFile.delete()).isTrue();
        File classes = new File(classDir);
        assertThat(classes.mkdir()).isTrue();
        testDeps.setPath(pathOf(classes));
        assertThat(new File(classes, "AnotherTest.class").createNewFile()).isTrue();
        plugin.setProjectTestArtifacts(singletonList(testDeps));
        plugin.setDependenciesToScan(new String[] {"g:a"});

        DefaultScanResult result = plugin.scanDependencies();
        assertThat(result.getClasses()).isEmpty();
    }

    @Test
    public void shouldUseOnlySpecificTests() throws Exception {
        Mojo plugin = new Mojo();

        plugin.setLogger(mock(Logger.class));

        File includes = SureFireFileManager.createTempFile("surefire", "-includes");
        FileUtils.write(includes, "AnotherTest", UTF_8);
        plugin.setIncludesFile(includes);
        plugin.setTest("DifferentTest");

        String version = "1.0";
        StubDependency testDeps = new StubDependency("g", "a", version, "test-jar", DependencyScope.forId("compile"));
        File artifactFile = SureFireFileManager.createTempFile("surefire", "-classes");
        String classDir = artifactFile.getCanonicalPath();
        assertThat(artifactFile.delete()).isTrue();
        File classes = new File(classDir);
        assertThat(classes.mkdir()).isTrue();
        testDeps.setPath(pathOf(classes));
        assertThat(new File(classes, "AnotherTest.class").createNewFile()).isTrue();
        plugin.setProjectTestArtifacts(singletonList(testDeps));
        plugin.setDependenciesToScan(new String[] {"g:a"});

        DefaultScanResult result = plugin.scanDependencies();
        assertThat(result.getClasses()).isEmpty();
    }

    @Test
    public void testSetupProperties() {
        Mojo plugin = new Mojo() {

            @Override
            Properties getUserProperties() {
                Properties properties = new Properties();
                properties.put("userProperties1", "source4");
                return properties;
            }

            @Override
            public File getBasedir() {
                return new File("target");
            }

            @Override
            public String getLocalRepositoryPath() {
                return "local/repository/path";
            }

            @Override
            public File getSystemPropertiesFile() {
                Properties systemProperties = new Properties();
                systemProperties.put("systemProperties2", "source2");
                systemProperties.put("systemProperties3", "source2");
                systemProperties.put("userProperties1", "source2");
                try {
                    File propertiesFile =
                            Files.createTempFile(tempFolder, "test", ".tmp").toFile();
                    try (OutputStream outputStream = Files.newOutputStream(propertiesFile.toPath())) {
                        systemProperties.store(outputStream, "comments");
                    }
                    return propertiesFile;
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        };

        plugin.setLogger(mock(Logger.class));
        /* expected order of precedence:
         * 1. systemProperties
         * 2. getSystemPropertiesFile()
         * 3. systemPropertyVariables
         * 4. getUserProperties()
         */
        plugin.forkCount = "1";
        plugin.promoteUserPropertiesToSystemProperties = true;
        Properties systemProperties = new Properties();
        systemProperties.put("systemProperties1", "source1");
        systemProperties.put("systemProperties2", "source1");
        systemProperties.put("systemProperties3", "source1");
        systemProperties.put("userProperties1", "source1");
        plugin.systemProperties = systemProperties;
        Map<String, String> systemPropertyVariables = new HashMap<>();
        systemPropertyVariables.put("systemProperties3", "source3");
        systemPropertyVariables.put("userProperties1", "source3");
        plugin.systemPropertyVariables = systemPropertyVariables;
        SurefireProperties properties = plugin.setupProperties();
        assertThat(properties)
                .containsOnly(
                        Assertions.entry("systemProperties1", "source1"),
                        Assertions.entry("systemProperties2", "source2"),
                        Assertions.entry("systemProperties3", "source3"),
                        Assertions.entry("userProperties1", "source4"),
                        Assertions.entry("localRepository", "local/repository/path"),
                        Assertions.entry("basedir", new File("target").getAbsolutePath()));

        // and without user properties
        plugin.promoteUserPropertiesToSystemProperties = false;
        properties = plugin.setupProperties();
        assertThat(properties)
                .containsOnly(
                        Assertions.entry("systemProperties1", "source1"),
                        Assertions.entry("systemProperties2", "source2"),
                        Assertions.entry("systemProperties3", "source3"),
                        Assertions.entry("userProperties1", "source3"),
                        Assertions.entry("localRepository", "local/repository/path"),
                        Assertions.entry("basedir", new File("target").getAbsolutePath()));
    }

    @SuppressWarnings("unchecked")
    private static <T> T invokeMethod(Object target, String methodName, Object... args) throws Exception {
        Class<?> clazz = target instanceof Class ? (Class<?>) target : target.getClass();
        while (clazz != null) {
            for (Method method : clazz.getDeclaredMethods()) {
                if (method.getName().equals(methodName)) {
                    int paramCount = method.getParameterCount();
                    if (paramCount == args.length || (method.isVarArgs() && args.length >= paramCount - 1)) {
                        method.setAccessible(true);
                        Object instance = target instanceof Class ? null : target;
                        if (method.isVarArgs() && args.length < paramCount) {
                            // Fill missing varargs with empty array
                            Object[] fullArgs = new Object[paramCount];
                            System.arraycopy(args, 0, fullArgs, 0, args.length);
                            Class<?> varArgType = method.getParameterTypes()[paramCount - 1].getComponentType();
                            fullArgs[paramCount - 1] = java.lang.reflect.Array.newInstance(varArgType, 0);
                            return (T) method.invoke(instance, fullArgs);
                        }
                        return (T) method.invoke(instance, args);
                    }
                }
            }
            clazz = clazz.getSuperclass();
        }
        throw new NoSuchMethodException(methodName);
    }

    @SuppressWarnings("unchecked")
    private static <T> T invokeMethod(Object target, String methodName, Class<?>[] paramTypes, Object... args)
            throws Exception {
        Class<?> clazz = target instanceof Class ? (Class<?>) target : target.getClass();
        while (clazz != null) {
            try {
                Method method = clazz.getDeclaredMethod(methodName, paramTypes);
                method.setAccessible(true);
                return (T) method.invoke(target instanceof Class ? null : target, args);
            } catch (NoSuchMethodException e) {
                clazz = clazz.getSuperclass();
            }
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

    private static File mockFile(String absolutePath) {
        File f = mock(File.class);
        when(f.getAbsolutePath()).thenReturn(absolutePath);
        return f;
    }

    /**
     * The resolver gets an artifact made by the session, which does not equal a stub.
     */
    private static Artifact sameCoordinates(Artifact expected) {
        return org.mockito.ArgumentMatchers.argThat(actual -> actual != null
                && actual.getGroupId().equals(expected.getGroupId())
                && actual.getArtifactId().equals(expected.getArtifactId())
                && actual.getVersion().equals(expected.getVersion()));
    }

    private static Path pathOf(File file) {
        // the tests pass mocked files, which only know their absolute path
        String absolutePath = file.getAbsolutePath();
        Path path = mock(Path.class);
        when(path.toAbsolutePath()).thenReturn(path);
        when(path.toString()).thenReturn(absolutePath);
        when(path.toFile()).thenReturn(file);
        return path;
    }
}
