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
import java.util.Objects;

import org.apache.maven.api.Dependency;
import org.apache.maven.api.DependencyCoordinates;
import org.apache.maven.api.DependencyScope;
import org.apache.maven.api.DownloadedDependency;
import org.apache.maven.api.Type;
import org.apache.maven.api.Version;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A {@link Dependency} for the tests. The Maven 4 API creates them only within a resolution.
 */
public final class StubDependency implements DownloadedDependency {
    private final String groupId;

    private final String artifactId;

    private final String version;

    private final String type;

    private final DependencyScope scope;

    private final String classifier;

    private Path path;

    public StubDependency(String groupId, String artifactId, String version) {
        this(groupId, artifactId, version, "jar", DependencyScope.COMPILE);
    }

    public StubDependency(String groupId, String artifactId, String version, String type) {
        this(groupId, artifactId, version, type, DependencyScope.COMPILE);
    }

    public StubDependency(String groupId, String artifactId, String version, String type, DependencyScope scope) {
        this(groupId, artifactId, version, type, scope, null);
    }

    public StubDependency(
            String groupId, String artifactId, String version, String type, DependencyScope scope, String classifier) {
        this.classifier = classifier == null ? "" : classifier;
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.version = version;
        this.type = type;
        this.scope = scope;
    }

    @Override
    public Path getPath() {
        return path;
    }

    public void setPath(Path path) {
        this.path = path;
    }

    @Override
    public Type getType() {
        try {
            return TestSessions.session().requireType(type);
        } catch (RuntimeException e) {
            // a type which no packaging registered, such as war
            Type unknown = mock(Type.class);
            when(unknown.id()).thenReturn(type);
            when(unknown.getExtension()).thenReturn(type);
            return unknown;
        }
    }

    @Override
    public DependencyScope getScope() {
        return scope;
    }

    @Override
    public boolean isOptional() {
        return false;
    }

    @Override
    public DependencyCoordinates toCoordinates() {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getGroupId() {
        return groupId;
    }

    @Override
    public String getArtifactId() {
        return artifactId;
    }

    @Override
    public Version getVersion() {
        return TestSessions.session().parseVersion(version);
    }

    @Override
    public Version getBaseVersion() {
        return getVersion();
    }

    @Override
    public String getClassifier() {
        return classifier;
    }

    @Override
    public String getExtension() {
        return getType().getExtension();
    }

    @Override
    public boolean isSnapshot() {
        return version.endsWith("-SNAPSHOT");
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof StubDependency
                && groupId.equals(((StubDependency) o).groupId)
                && artifactId.equals(((StubDependency) o).artifactId)
                && version.equals(((StubDependency) o).version)
                && type.equals(((StubDependency) o).type)
                && classifier.equals(((StubDependency) o).classifier);
    }

    @Override
    public int hashCode() {
        return Objects.hash(groupId, artifactId, version, type, classifier);
    }

    @Override
    public String toString() {
        return groupId + ":" + artifactId + ":" + type + (classifier.isEmpty() ? "" : ":" + classifier) + ":" + version
                + ":" + scope.id();
    }
}
