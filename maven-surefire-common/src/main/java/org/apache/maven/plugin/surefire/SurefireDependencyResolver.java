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

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.maven.api.Artifact;
import org.apache.maven.api.DependencyCoordinates;
import org.apache.maven.api.DownloadedArtifact;
import org.apache.maven.api.PathScope;
import org.apache.maven.api.Plugin;
import org.apache.maven.api.RemoteRepository;
import org.apache.maven.api.Session;
import org.apache.maven.api.VersionRange;
import org.apache.maven.api.di.Named;
import org.apache.maven.api.di.Singleton;
import org.apache.maven.api.model.Dependency;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.services.DependencyCoordinatesFactory;
import org.apache.maven.api.services.DependencyResolver;
import org.apache.maven.api.services.DependencyResolverException;
import org.apache.maven.api.services.DependencyResolverRequest;
import org.apache.maven.api.services.DependencyResolverResult;
import org.apache.maven.api.services.VersionParserException;

import static java.util.Collections.singletonList;

/**
 * Internal component used by the surefire plugin. Not a public API.
 * not guaranteed to remain unchanged across surefire plugin versions.
 * Does dependency resolution and artifact handling for the surefire plugin.
 *
 * @author Stephen Connolly
 * @author Kristian Rosenvold
 */
@Named
@Singleton
public class SurefireDependencyResolver {

    static final String PROVIDER_GROUP_ID = "org.apache.maven.surefire";

    /**
     * The oldest JUnit 4 we can run. Anything older is rejected: JUnit 3 and 4 tests run through the
     * JUnit Platform vintage engine, which needs 4.12 or newer.
     * <p>
     * Newer JUnit 5 lines ask for more again, so a project may still need a later JUnit 4 than this:
     * 4.12+ for JUnit 5.3+, 4.13+ for 5.8+, 4.13.2 for 5.9+, 4.13.3 for 5.10+, 4.13.4 for 5.12+. See
     * <a href="https://junit.org/junit5/docs/current/user-guide/#dependency-metadata">the JUnit 5
     * dependency metadata</a>.
     */
    public static final String MINIMUM_JUNIT4_VERSION_SPEC = "[4.12,)";

    private static final String[] PROVIDER_CLASSPATH_ORDER = {
        "surefire-junit-platform", "surefire-api", "surefire-logger-api", "surefire-shared-utils", "common-java5"
    };

    /**
     * The version range needs a {@link Session}: the Maven 4 API has no static version parser.
     */
    public static boolean isWithinVersionSpec(
            @Nonnull Session session, @Nullable Artifact artifact, @Nonnull String versionSpec) {
        if (artifact == null) {
            return false;
        }
        try {
            VersionRange range = session.parseVersionRange(versionSpec);
            return range.contains(artifact.getVersion());
        } catch (VersionParserException e) {
            throw new RuntimeException("Bug in plugin. Please report with stacktrace");
        }
    }

    /**
     * Resolves the dependencies declared on the plugin in the POM, keeping those which are also part of the resolved
     * classpath of the plugin.
     */
    Map<String, DownloadedArtifact> resolvePluginDependencies(
            Session session,
            List<RemoteRepository> repositories,
            Plugin plugin,
            Map<String, ? extends Artifact> pluginResolvedDependencies)
            throws MojoException {
        Map<String, DownloadedArtifact> resolved = new LinkedHashMap<>();
        DependencyCoordinatesFactory factory = session.getService(DependencyCoordinatesFactory.class);

        for (Dependency dependency : plugin.getModel().getDependencies()) {
            Set<DownloadedArtifact> artifacts =
                    resolveDependencies(session, repositories, factory.create(session, dependency));
            for (DownloadedArtifact artifact : artifacts) {
                String key = artifact.getGroupId() + ":" + artifact.getArtifactId();
                Artifact resolvedPluginDependency = pluginResolvedDependencies.get(key);
                if (resolvedPluginDependency != null) {
                    resolved.put(key, artifact);
                }
            }
        }
        return resolved;
    }

    public Set<DownloadedArtifact> resolveArtifacts(
            Session session, List<RemoteRepository> repositories, Artifact artifact) throws MojoException {
        return resolveDependencies(
                session,
                repositories,
                session.getService(DependencyCoordinatesFactory.class)
                        .create(session, session.createArtifactCoordinates(artifact)));
    }

    public Set<DownloadedArtifact> resolveDependencies(
            Session session, List<RemoteRepository> repositories, Dependency dependency) throws MojoException {
        return resolveDependencies(
                session,
                repositories,
                session.getService(DependencyCoordinatesFactory.class).create(session, dependency));
    }

    private Set<DownloadedArtifact> resolveDependencies(
            Session session, List<RemoteRepository> repositories, DependencyCoordinates dependency)
            throws MojoException {
        try {
            // a request without a root, in order to not resolve optional dependencies
            DependencyResolverRequest request = DependencyResolverRequest.builder()
                    .session(session)
                    .requestType(DependencyResolverRequest.RequestType.RESOLVE)
                    .dependencies(singletonList(dependency))
                    .pathScope(PathScope.MAIN_RUNTIME)
                    .repositories(repositories)
                    .build();
            DependencyResolverResult result =
                    session.getService(DependencyResolver.class).resolve(request);
            return toResolved(result.getDependencies());
        } catch (DependencyResolverException e) {
            throw new MojoException(e.getMessage(), e);
        }
    }

    /**
     * The resolved dependencies in resolution order.
     */
    static Set<DownloadedArtifact> toResolved(Map<org.apache.maven.api.Dependency, java.nio.file.Path> dependencies) {
        Set<DownloadedArtifact> result = new LinkedHashSet<>();
        for (Entry<org.apache.maven.api.Dependency, java.nio.file.Path> entry : dependencies.entrySet()) {
            result.add(new ResolvedDependency(entry.getKey(), entry.getValue()));
        }
        return result;
    }

    @Nonnull
    Set<DownloadedArtifact> getProviderClasspath(
            Session session, List<RemoteRepository> repositories, String providerArtifactId, String providerVersion)
            throws MojoException {

        Dependency provider = toProviderDependency(providerArtifactId, providerVersion);

        Set<DownloadedArtifact> result = resolveDependencies(session, repositories, provider);

        return orderProviderArtifacts(result);
    }

    @Nonnull
    Map<String, DownloadedArtifact> getProviderClasspathAsMap(
            Session session, List<RemoteRepository> repositories, String providerArtifactId, String providerVersion)
            throws MojoException {
        return artifactMapByVersionlessId(
                getProviderClasspath(session, repositories, providerArtifactId, providerVersion));
    }

    /**
     * Replaces {@code Artifact#getDependencyConflictId()}: {@code groupId:artifactId:type[:classifier]}.
     */
    static String dependencyConflictId(Artifact artifact) {
        String type = artifact instanceof org.apache.maven.api.Dependency
                ? ((org.apache.maven.api.Dependency) artifact).getType().id()
                : artifact.getExtension();
        String classifier = artifact.getClassifier();
        return artifact.getGroupId() + ":" + artifact.getArtifactId() + ":" + type
                + (classifier == null || classifier.isEmpty() ? "" : ":" + classifier);
    }

    /**
     * Replaces {@code org.apache.maven.artifact.ArtifactUtils#artifactMapByVersionlessId}, which has no
     * counterpart in the Maven 4 API. The key is {@code groupId:artifactId}.
     */
    static <T extends Artifact> Map<String, T> artifactMapByVersionlessId(Collection<T> artifacts) {
        Map<String, T> map = new LinkedHashMap<>();
        for (T artifact : artifacts) {
            map.put(artifact.getGroupId() + ":" + artifact.getArtifactId(), artifact);
        }
        return map;
    }

    // FIXME
    // method argument should be unchanged
    // what if providerArtifacts will be unmodifiable
    private static Set<DownloadedArtifact> orderProviderArtifacts(Set<DownloadedArtifact> providerArtifacts) {
        Set<DownloadedArtifact> orderedProviderArtifacts = new LinkedHashSet<>();
        for (String order : PROVIDER_CLASSPATH_ORDER) {
            java.util.Iterator<DownloadedArtifact> providerArtifactsIt = providerArtifacts.iterator();
            while (providerArtifactsIt.hasNext()) {
                DownloadedArtifact providerArtifact = providerArtifactsIt.next();
                if (providerArtifact.getArtifactId().equals(order)) {
                    orderedProviderArtifacts.add(providerArtifact);
                    providerArtifactsIt.remove();
                }
            }
        }
        orderedProviderArtifacts.addAll(providerArtifacts);
        return orderedProviderArtifacts;
    }

    private static Dependency toProviderDependency(String providerArtifactId, String providerVersion) {
        return Dependency.newBuilder()
                .groupId(PROVIDER_GROUP_ID)
                .artifactId(providerArtifactId)
                .version(providerVersion)
                .type("jar")
                .build();
    }
}
