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

/**
 * A {@link Dependency} together with the file it was resolved to.
 * The Maven 4 API returns the two separately, as {@code Map<Dependency, Path>} from the dependency resolver,
 * and offers no public implementation of {@link DownloadedDependency}, the type which
 * {@code org.apache.maven.artifact.Artifact#getFile()} used to cover.
 * Internal to the Surefire plugin, not a public API.
 */
public final class ResolvedDependency implements DownloadedDependency {
    private final Dependency dependency;

    private final Path path;

    public ResolvedDependency(Dependency dependency, Path path) {
        this.dependency = Objects.requireNonNull(dependency, "dependency");
        this.path = path;
    }

    @Override
    public Path getPath() {
        return path;
    }

    @Override
    public Type getType() {
        return dependency.getType();
    }

    @Override
    public DependencyScope getScope() {
        return dependency.getScope();
    }

    @Override
    public boolean isOptional() {
        return dependency.isOptional();
    }

    @Override
    public DependencyCoordinates toCoordinates() {
        return dependency.toCoordinates();
    }

    @Override
    public String getGroupId() {
        return dependency.getGroupId();
    }

    @Override
    public String getArtifactId() {
        return dependency.getArtifactId();
    }

    @Override
    public Version getVersion() {
        return dependency.getVersion();
    }

    @Override
    public Version getBaseVersion() {
        return dependency.getBaseVersion();
    }

    @Override
    public String getClassifier() {
        return dependency.getClassifier();
    }

    @Override
    public String getExtension() {
        return dependency.getExtension();
    }

    @Override
    public boolean isSnapshot() {
        return dependency.isSnapshot();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ResolvedDependency
                && dependency.equals(((ResolvedDependency) o).dependency)
                && Objects.equals(path, ((ResolvedDependency) o).path);
    }

    @Override
    public int hashCode() {
        return Objects.hash(dependency, path);
    }

    @Override
    public String toString() {
        return dependency + " (" + path + ")";
    }
}
