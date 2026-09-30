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

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.maven.api.Artifact;
import org.apache.maven.api.ArtifactCoordinates;
import org.apache.maven.api.Dependency;
import org.apache.maven.api.DependencyCoordinates;
import org.apache.maven.api.DownloadedArtifact;
import org.apache.maven.api.PathScope;
import org.apache.maven.api.Plugin;
import org.apache.maven.api.Session;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.services.DependencyCoordinatesFactory;
import org.apache.maven.api.services.DependencyResolver;
import org.apache.maven.api.services.DependencyResolverException;
import org.apache.maven.api.services.DependencyResolverRequest;
import org.apache.maven.api.services.DependencyResolverResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static java.util.Collections.singletonMap;
import static org.apache.maven.plugin.surefire.SurefireDependencyResolver.PROVIDER_GROUP_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The version ranges are parsed by a real {@link Session}, the resolution is done by a mocked
 * {@link DependencyResolver}.
 */
public class SurefireDependencyResolverTest {

    @Test
    public void shouldNotBeWithinRangeNullArtifact() {
        boolean result = SurefireDependencyResolver.isWithinVersionSpec(TestSessions.session(), null, "[4.7,)");
        assertThat(result).isFalse();
    }

    @Test
    public void shouldNotBeWithinRange() {
        Artifact api = TestSessions.artifact("junit", "junit", "4.6");
        boolean result = SurefireDependencyResolver.isWithinVersionSpec(TestSessions.session(), api, "[4.7,)");
        assertThat(result).isFalse();
    }

    @Test
    public void shouldBeWithinRange() {
        Artifact api = TestSessions.artifact("junit", "junit", "4.7");
        boolean result = SurefireDependencyResolver.isWithinVersionSpec(TestSessions.session(), api, "[4.7,)");
        assertThat(result).isTrue();
    }

    @Test
    public void shouldBeFarWithinRange() {
        Artifact api = TestSessions.artifact("junit", "junit", "4.13");
        boolean result = SurefireDependencyResolver.isWithinVersionSpec(TestSessions.session(), api, "[4.7,)");
        assertThat(result).isTrue();
    }

    @Test
    public void shouldNotBeWithinRangeOfEmptyVersion() {
        // a version which does not parse used to fail with "Bug in plugin": the Maven 4 versions always parse
        Artifact api = TestSessions.artifact("junit", "junit", "");
        boolean result = SurefireDependencyResolver.isWithinVersionSpec(TestSessions.session(), api, "[4.7,)");
        assertThat(result).isFalse();
    }

    @Test
    public void shouldFailOnInvalidRange() {
        Artifact api = TestSessions.artifact("junit", "junit", "4.7");
        assertThatThrownBy(() -> SurefireDependencyResolver.isWithinVersionSpec(TestSessions.session(), api, "[4.7"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Bug in plugin. Please report with stacktrace");
    }

    @Test
    public void testResolveArtifact() throws Exception {
        Artifact provider = TestSessions.artifact(PROVIDER_GROUP_ID, "surefire-junit-platform", "1");

        Session session = mock(Session.class);
        DependencyResolver resolver = mock(DependencyResolver.class);
        DependencyCoordinatesFactory factory = mock(DependencyCoordinatesFactory.class);
        DependencyCoordinates coordinates = mock(DependencyCoordinates.class);
        ArtifactCoordinates artifactCoordinates = mock(ArtifactCoordinates.class);
        when(session.getService(DependencyResolver.class)).thenReturn(resolver);
        when(session.getService(DependencyCoordinatesFactory.class)).thenReturn(factory);
        when(session.createArtifactCoordinates(provider)).thenReturn(artifactCoordinates);
        when(factory.create(session, artifactCoordinates)).thenReturn(coordinates);
        DependencyResolverResult result = mock(DependencyResolverResult.class);
        when(result.getDependencies()).thenReturn(Collections.emptyMap());
        ArgumentCaptor<DependencyResolverRequest> requestCaptor =
                ArgumentCaptor.forClass(DependencyResolverRequest.class);
        when(resolver.resolve(requestCaptor.capture())).thenReturn(result);

        new SurefireDependencyResolver().resolveArtifacts(session, Collections.emptyList(), provider);

        DependencyResolverRequest request = requestCaptor.getValue();
        assertThat(request).isNotNull();
        // no root, in order to not resolve optional dependencies
        assertThat(request.getRoot()).isEmpty();
        assertThat(request.getDependencies()).containsExactly(coordinates);
        assertThat(request.getPathScope()).isEqualTo(PathScope.MAIN_RUNTIME);
    }

    @Test
    public void testGetProviderClasspath() throws Exception {

        Dependency commonJunit4 = createDependency("common-junit4");
        Dependency api = createDependency("surefire-api");
        Dependency provider = createDependency("surefire-junit-platform");
        Dependency ext = createDependency("org.apiguardian", "apiguardian-api");
        Dependency logger = createDependency("surefire-logger-api");

        Map<Dependency, Path> resolved = new LinkedHashMap<>();
        for (Dependency dependency : List.of(commonJunit4, api, provider, ext, logger)) {
            resolved.put(dependency, Paths.get(dependency.getArtifactId() + ".jar"));
        }

        Session session = sessionResolving(resolved);

        Set<DownloadedArtifact> classpath = new SurefireDependencyResolver()
                .getProviderClasspath(session, Collections.emptyList(), "surefire-junit-platform", "1");

        assertThat(classpath).hasSize(5);

        Iterator<DownloadedArtifact> it = classpath.iterator();

        // result should be ordered
        assertThat(it.next()).isEqualTo(new ResolvedDependency(provider, resolved.get(provider)));
        assertThat(it.next()).isEqualTo(new ResolvedDependency(api, resolved.get(api)));
        assertThat(it.next()).isEqualTo(new ResolvedDependency(logger, resolved.get(logger)));
        assertThat(it.next()).isEqualTo(new ResolvedDependency(commonJunit4, resolved.get(commonJunit4)));
        assertThat(it.next()).isEqualTo(new ResolvedDependency(ext, resolved.get(ext)));
    }

    @Test
    public void testGetProviderClasspathShouldPropagateTheResolutionException() throws Exception {
        Session session = mock(Session.class);
        DependencyResolver resolver = mock(DependencyResolver.class);
        DependencyCoordinatesFactory factory = mock(DependencyCoordinatesFactory.class);
        when(session.getService(DependencyResolver.class)).thenReturn(resolver);
        when(session.getService(DependencyCoordinatesFactory.class)).thenReturn(factory);
        when(factory.create(any(Session.class), any(org.apache.maven.api.model.Dependency.class)))
                .thenReturn(mock(DependencyCoordinates.class));

        DependencyResolverException resolutionException = new DependencyResolverException("failed", new Exception());
        when(resolver.resolve(any(DependencyResolverRequest.class))).thenThrow(resolutionException);

        SurefireDependencyResolver surefireDependencyResolver = new SurefireDependencyResolver();

        assertThatThrownBy(() -> surefireDependencyResolver.getProviderClasspath(
                        session, Collections.emptyList(), "surefire-junit-platform", "1"))
                .isInstanceOf(MojoException.class)
                .hasCause(resolutionException);
    }

    @Test
    public void testResolvePluginDependencies() throws Exception {
        org.apache.maven.api.model.Dependency providerAsDependency = org.apache.maven.api.model.Dependency.newBuilder()
                .groupId(PROVIDER_GROUP_ID)
                .artifactId("surefire-shadefire")
                .version("1")
                .build();

        Dependency providerAsArtifact = createDependency("surefire-shadefire");
        Path providerFile = Paths.get("surefire-shadefire.jar");

        Plugin plugin = mock(Plugin.class);
        when(plugin.getModel())
                .thenReturn(org.apache.maven.api.model.Plugin.newBuilder()
                        .dependencies(List.of(providerAsDependency))
                        .build());

        Session session = sessionResolving(singletonMap(providerAsArtifact, providerFile));
        DependencyCoordinatesFactory factory = session.getService(DependencyCoordinatesFactory.class);

        Map<String, Artifact> pluginResolvedDependencies =
                singletonMap(PROVIDER_GROUP_ID + ":surefire-shadefire", providerAsArtifact);

        Map<String, DownloadedArtifact> providers = new SurefireDependencyResolver()
                .resolvePluginDependencies(session, Collections.emptyList(), plugin, pluginResolvedDependencies);

        assertThat(providers.values())
                .hasSize(1)
                .containsOnly(new ResolvedDependency(providerAsArtifact, providerFile));

        verify(factory).create(session, providerAsDependency);
        verify(session.getService(DependencyResolver.class)).resolve(any(DependencyResolverRequest.class));
    }

    private static Session sessionResolving(Map<Dependency, Path> dependencies) {
        Session session = mock(Session.class);
        DependencyResolver resolver = mock(DependencyResolver.class);
        DependencyCoordinatesFactory factory = mock(DependencyCoordinatesFactory.class);
        DependencyResolverResult result = mock(DependencyResolverResult.class);
        when(session.getService(DependencyResolver.class)).thenReturn(resolver);
        when(session.getService(DependencyCoordinatesFactory.class)).thenReturn(factory);
        when(factory.create(any(Session.class), any(org.apache.maven.api.model.Dependency.class)))
                .thenReturn(mock(DependencyCoordinates.class));
        when(result.getDependencies()).thenReturn(dependencies);
        when(resolver.resolve(any(DependencyResolverRequest.class))).thenReturn(result);
        return session;
    }

    private static Dependency createDependency(String artifactId) {
        return createDependency(PROVIDER_GROUP_ID, artifactId);
    }

    private static Dependency createDependency(String groupId, String artifactId) {
        // the Maven 4 API has no Dependency implementation outside the Session: a plain stub
        return new StubDependency(groupId, artifactId, "1");
    }
}
