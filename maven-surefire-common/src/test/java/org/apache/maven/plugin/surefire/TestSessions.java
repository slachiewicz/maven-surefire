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

import java.lang.reflect.InvocationTargetException;
import java.util.Collections;

import org.apache.maven.api.Artifact;
import org.apache.maven.api.DownloadedArtifact;
import org.apache.maven.api.ProducedArtifact;
import org.apache.maven.api.Project;
import org.apache.maven.api.Session;
import org.apache.maven.api.services.ProjectManager;
import org.apache.maven.impl.standalone.ApiRunner;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A real {@link Session}, for the version parsing, and artifacts made by it.
 * The Maven 4 API has neither a static version parser nor an artifact implementation.
 */
public final class TestSessions {
    private static Session session;

    private TestSessions() {}

    public static synchronized Session session() {
        if (session == null) {
            session = ApiRunner.createSession();
        }
        return session;
    }

    static Artifact artifact(String groupId, String artifactId, String version) {
        return session().createArtifact(groupId, artifactId, version, "jar");
    }

    /**
     * A session for the mojo under test: parses versions like the real one, has no repositories, and "resolves" an
     * artifact which already is downloaded to itself.
     */
    static Session delegatingSession() {
        Session real = session();
        ProjectManager projectManager = mock(ProjectManager.class);
        when(projectManager.getRemoteProjectRepositories(org.mockito.ArgumentMatchers.any()))
                .thenReturn(Collections.emptyList());
        when(projectManager.getRemotePluginRepositories(org.mockito.ArgumentMatchers.any()))
                .thenReturn(Collections.emptyList());
        return mock(Session.class, invocation -> {
            Object[] arguments = invocation.getArguments();
            if ("getService".equals(invocation.getMethod().getName()) && arguments[0] == ProjectManager.class) {
                return projectManager;
            }
            if ("resolveArtifact".equals(invocation.getMethod().getName())
                    && arguments.length == 1
                    && arguments[0] instanceof DownloadedArtifact) {
                return arguments[0];
            }
            try {
                return invocation.getMethod().invoke(real, arguments);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        });
    }

    /**
     * A project which has the given coordinates.
     */
    static Project project(String groupId, String artifactId, String version) {
        ProducedArtifact pom = mock(ProducedArtifact.class);
        when(pom.getGroupId()).thenReturn(groupId);
        when(pom.getArtifactId()).thenReturn(artifactId);
        when(pom.getVersion()).thenReturn(session().parseVersion(version));
        Project project = mock(Project.class);
        when(project.getPomArtifact()).thenReturn(pom);
        return project;
    }
}
