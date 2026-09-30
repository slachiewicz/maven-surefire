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

import java.util.ArrayList;
import java.util.List;

import org.apache.maven.api.model.Dependency;
import org.apache.maven.api.model.Exclusion;

/**
 * An item of the parameter {@code additionalClasspathDependencies}.
 * The parameter used to be a list of {@code org.apache.maven.model.Dependency}, which is mutable. The Maven 4 model
 * classes are immutable and cannot be populated from the plugin configuration, so this bean takes their place. It
 * accepts the same XML, {@code groupId}, {@code artifactId}, {@code version}, {@code type}, {@code classifier},
 * {@code scope} and {@code exclusions}.
 */
public class AdditionalClasspathDependency {
    private String groupId;

    private String artifactId;

    private String version;

    private String type = "jar";

    private String classifier;

    private String scope;

    private List<ExcludedDependency> exclusions = new ArrayList<>();

    public String getGroupId() {
        return groupId;
    }

    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    public String getArtifactId() {
        return artifactId;
    }

    public void setArtifactId(String artifactId) {
        this.artifactId = artifactId;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getClassifier() {
        return classifier;
    }

    public void setClassifier(String classifier) {
        this.classifier = classifier;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public List<ExcludedDependency> getExclusions() {
        return exclusions;
    }

    public void setExclusions(List<ExcludedDependency> exclusions) {
        this.exclusions = exclusions;
    }

    Dependency toModel() {
        Dependency.Builder builder = Dependency.newBuilder()
                .groupId(groupId)
                .artifactId(artifactId)
                .version(version)
                .type(type)
                .classifier(classifier)
                .scope(scope);
        List<Exclusion> excluded = new ArrayList<>();
        for (ExcludedDependency exclusion : exclusions) {
            excluded.add(Exclusion.newBuilder()
                    .groupId(exclusion.getGroupId())
                    .artifactId(exclusion.getArtifactId())
                    .build());
        }
        return builder.exclusions(excluded).build();
    }

    /**
     * The {@code exclusion} of an {@link AdditionalClasspathDependency}.
     */
    public static class ExcludedDependency {
        private String groupId;

        private String artifactId;

        public String getGroupId() {
            return groupId;
        }

        public void setGroupId(String groupId) {
            this.groupId = groupId;
        }

        public String getArtifactId() {
            return artifactId;
        }

        public void setArtifactId(String artifactId) {
            this.artifactId = artifactId;
        }
    }
}
