/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2022, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.set.maven.release.extension;

import static java.util.Collections.emptySet;
import static java.util.Objects.requireNonNull;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.MavenExecutionException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.VersionRangeRequest;
import org.eclipse.aether.resolution.VersionRangeResolutionException;
import org.eclipse.aether.resolution.VersionRangeResult;
import org.eclipse.aether.version.Version;
import org.slf4j.Logger;
import org.wildfly.channel.Channel;
import org.wildfly.channel.ChannelMapper;
import org.wildfly.channel.ChannelSession;
import org.wildfly.channel.MavenArtifact;
import org.wildfly.channel.UnresolvedMavenArtifactException;
import org.wildfly.channel.spi.MavenVersionsResolver;

@Component(role = AbstractMavenLifecycleParticipant.class, hint = "mailman")
public class VersionBumpExtension extends AbstractMavenLifecycleParticipant {
    final String VBE_CHANNELS = "vbe.channels";
    final String VBE_EXCLUDES = "vbe.excludes";

    @Requirement
    private Logger logger;

    @Requirement
    private PlexusContainer container;

    @Requirement
    RepositorySystem repo;

    // yeah, not a best practice
    private MavenSession session;
    private Collection<RemoteRepository> repositories = new HashSet<>();
    private ChannelSession channelSession;

    @Override
    public void afterProjectsRead(final MavenSession session) throws MavenExecutionException {
        logger.info("\n\n========== Red Hat Channel Version Extension[VBE] Starting ==========\n");
        // NOTE: this will work only for project defined deps, if something is in parent, it ~cant be changed.
        if (session == null) {
            return;
        }

        this.session = session;
        long ts = System.currentTimeMillis();

        configure();

        // NOTE: to handle ALL modules. Those are different "projects"
        for (MavenProject mavenProject : session.getAllProjects()) {
            if (Boolean.valueOf(mavenProject.getProperties().getProperty(VBE_EXCLUDES, "false"))) {
                logger.info("[VBE][SKIPPING]   Project {}:{}", mavenProject.getGroupId(), mavenProject.getArtifactId());
                continue;
            }
            logger.info("[VBE][PROCESSING]   Project {}:{}", mavenProject.getGroupId(), mavenProject.getArtifactId());
            if (mavenProject.getDependencyManagement() != null) {
                processProject(mavenProject);
            }
        }

        logger.info("\n\n========== Red Hat Channel Version Extension Finished in " + (System.currentTimeMillis() - ts)
                + "ms ==========\n");
    }

    private void processProject(final MavenProject mavenProject) {
        if (mavenProject.getDependencyManagement() != null) {
            for (Dependency dependency : mavenProject.getDependencyManagement().getDependencies()) {
                if ("test".equals(dependency.getScope())
                        || "provided".equals(dependency.getScope())) {
                    logger.debug("[VBE] Ignore {} dependency {}", dependency.getScope(), dependency);
                    continue;
                }
                updateDependency(mavenProject, dependency, updatedDep -> dependency.setVersion(updatedDep.getVersion()));
            }
        }

        for (Dependency dependency : mavenProject.getDependencies()) {
            if ("test".equals(dependency.getScope())
                    || "provided".equals(dependency.getScope())) {
                logger.debug("[VBE] Ignore {} dependency {}", dependency.getScope(), dependency);
                continue;
            }
            updateDependency(mavenProject, dependency, updatedDep -> dependency.setVersion(updatedDep.getVersion()));
        }
    }

    /**
     * Update dependency if possible and/or desired.
     * 
     * @param mavenProject
     * @param dependency
     * @param mavenProjectVersionUpdater - artifact consumer which will perform maven model/pojo updates
     */
    private void updateDependency(final MavenProject mavenProject, final Dependency dependency, final Consumer<MavenArtifact> mavenProjectVersionUpdater) {
        MavenArtifact result;
        try {
            result = this.channelSession.resolveMavenArtifact(dependency.getGroupId(), dependency.getArtifactId(),dependency.getType(), dependency.getClassifier());
        } catch (UnresolvedMavenArtifactException e) {
            return;
        }
        String latestVersion = result.getVersion();
        if (!latestVersion.equals(dependency.getVersion())) {
            logger.info("[VBE] {}, updating dependency {} --> {}",
                    mavenProject, dependency, latestVersion);
            mavenProjectVersionUpdater.accept(result);
        }
    }

    private void configure() {
        this.configureRepositories();
        this.configureChannels();
    }

    private void configureRepositories() {
        this.repositories.addAll(session.getCurrentProject().getRemoteProjectRepositories());
        this.repositories.addAll(session.getCurrentProject().getRemotePluginRepositories());
    }

    private void configureChannels() {
        final String channelList = System.getProperty(VBE_CHANNELS);
        final List<Channel> channels = new LinkedList<>();
        if (channelList != null && channelList.length() > 0) {

            for (String urlString : Arrays.asList(channelList.split(","))) {
                try {
                    final URL url = new URL(urlString);
                    final Channel c = ChannelMapper.from(url);
                    // TODO: vet repositories?
                    channels.add(c);
                } catch (MalformedURLException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
            }
        }
        final MavenVersionsResolver.Factory factory = new MavenVersionsResolver.Factory() {
            // TODO: add acceptors?
            @Override
            public MavenVersionsResolver create() {
                return new MavenVersionsResolver() {

                    @Override
                    public Set<String> getAllVersions(String groupId, String artifactId, String extension, String classifier) {
                        requireNonNull(groupId);
                        requireNonNull(artifactId);
                        Artifact artifact = new DefaultArtifact(groupId, artifactId, classifier, extension, "[0,)");
                        VersionRangeRequest versionRangeRequest = new VersionRangeRequest();
                        versionRangeRequest.setArtifact(artifact);
                        versionRangeRequest.setRepositories(new ArrayList<>(repositories));

                        try {
                            VersionRangeResult versionRangeResult = repo.resolveVersionRange(session.getRepositorySession(),
                                    versionRangeRequest);
                            Set<String> versions = versionRangeResult.getVersions().stream().map(Version::toString)
                                    .collect(Collectors.toSet());
                            logger.trace("All versions in the repositories: %s", versions);
                            return versions;
                        } catch (VersionRangeResolutionException e) {
                            return emptySet();
                        }
                    }

                    @Override
                    public File resolveArtifact(String groupId, String artifactId, String extension_aka_type, String classifier,
                            String version) throws UnresolvedMavenArtifactException {
                        Artifact artifact = new DefaultArtifact(groupId, artifactId, classifier, extension_aka_type, version);
                        ArtifactRequest request = new ArtifactRequest();
                        request.setArtifact(artifact);
                        request.setRepositories(new ArrayList<>(repositories));
                        try {
                            ArtifactResult result = repo.resolveArtifact(session.getRepositorySession(), request);
                            return result.getArtifact().getFile();
                        } catch (ArtifactResolutionException e) {
                            throw new UnresolvedMavenArtifactException("Unable to resolve artifact " + artifact, e);
                        }
                    }

                };
            }

        };
        this.channelSession = new ChannelSession(channels, factory);
    }
}
