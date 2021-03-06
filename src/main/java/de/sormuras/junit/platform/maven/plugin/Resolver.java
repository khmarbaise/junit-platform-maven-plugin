/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.sormuras.junit.platform.maven.plugin;

import static de.sormuras.junit.platform.maven.plugin.Dependencies.GroupArtifact.JUNIT_JUPITER_API;
import static de.sormuras.junit.platform.maven.plugin.Dependencies.GroupArtifact.JUNIT_JUPITER_ENGINE;
import static de.sormuras.junit.platform.maven.plugin.Dependencies.GroupArtifact.JUNIT_PLATFORM_CONSOLE;
import static de.sormuras.junit.platform.maven.plugin.Dependencies.GroupArtifact.JUNIT_VINTAGE_ENGINE;

import de.sormuras.junit.platform.maven.plugin.Dependencies.GroupArtifact;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;

/** Runtime artifact resolver helper. */
class Resolver {

  private class PathsBuilder {

    final List<Path> paths = new ArrayList<>();

    void append(Path path) {
      if (Files.notExists(path)) {
        verbose("  X %s // does not exist", path);
        return;
      }
      if (paths.contains(path)) {
        verbose("  X %s // already added", path);
        return;
      }
      verbose(" -> %s", path);
      paths.add(path);
    }

    void append(String first, String... more) {
      append(Paths.get(first, more));
    }

    void append(GroupArtifact ga) {
      try {
        resolve(ga, this::append);
      } catch (DependencyResolutionException e) {
        mojo.getLog().warn("Resolving " + ga + " failed", e);
      }
    }

    List<Path> build() {
      return List.copyOf(paths);
    }
  }

  private final JUnitPlatformMojo mojo;
  private final List<Path> paths;
  private final MavenProject project;

  Resolver(JUnitPlatformMojo mojo) {
    this.mojo = mojo;
    this.project = mojo.getMavenProject();
    this.paths = buildPaths();
  }

  private List<Path> buildPaths() {
    var builder = new PathsBuilder();

    // Append test and main output directories
    builder.append(project.getBuild().getTestOutputDirectory());
    builder.append(project.getBuild().getOutputDirectory());

    // Append all user-defined dependencies
    for (var artifact : project.getArtifacts()) {
      if (!artifact.getArtifactHandler().isAddedToClasspath()) {
        continue;
      }
      var file = artifact.getFile();
      if (file != null) {
        builder.append(file.toPath());
      }
    }

    // Now append required artifacts by resolving "missing" artifacts...
    if (contains(JUNIT_JUPITER_API)) {
      builder.append(JUNIT_JUPITER_ENGINE);
    }
    var junit = project.getArtifactMap().get("junit:junit");
    if (junit != null && "4.12".equals(junit.getVersion())) {
      builder.append(JUNIT_VINTAGE_ENGINE);
    }
    builder.append(JUNIT_PLATFORM_CONSOLE);

    return builder.build();
  }

  private boolean contains(GroupArtifact ga) {
    return contains(ga.toIdentifier());
  }

  private boolean contains(String ga) {
    return project.getArtifactMap().containsKey(ga);
  }

  private void resolve(GroupArtifact ga, Consumer<Path> list) throws DependencyResolutionException {
    if (contains(ga)) {
      verbose("Skip resolving '%s', because it is already mapped.", ga);
      return;
    }
    var gav = ga.toIdentifier() + ":" + mojo.version(ga.getVersion());
    verbose("");
    verbose("Resolving '%s' and its transitive dependencies...", gav);
    for (var resolved : resolve(gav)) {
      var key = resolved.getGroupId() + ':' + resolved.getArtifactId();
      if (contains(key)) {
        verbose("  X %s // mapped by project", resolved);
        continue;
      }
      list.accept(resolved.getFile().toPath().toAbsolutePath().normalize());
    }
  }

  private List<Artifact> resolve(String coordinates) throws DependencyResolutionException {
    var repositories = new ArrayList<RemoteRepository>();
    repositories.addAll(project.getRemotePluginRepositories());
    repositories.addAll(project.getRemoteProjectRepositories());
    var artifact = new DefaultArtifact(coordinates);
    verbose("Resolving artifact %s from %s...", artifact, repositories);
    var artifactRequest = new ArtifactRequest();
    artifactRequest.setArtifact(artifact);
    artifactRequest.setRepositories(repositories);
    // var resolved = mojo.getMavenResolver().resolveArtifact(session, artifactRequest);
    // verbose("Resolved %s from %s", artifact, resolved.getRepository());
    // verbose("Stored %s to %s", artifact, resolved.getArtifact().getFile());
    var collectRequest = new CollectRequest();
    collectRequest.setRoot(new Dependency(artifact, ""));
    collectRequest.setRepositories(repositories);
    var dependencyRequest = new DependencyRequest(collectRequest, (all, ways) -> true);
    var session = mojo.getMavenRepositorySession();
    verbose("Resolving dependencies %s...", dependencyRequest);
    return mojo.getMavenResolver()
        .resolveDependencies(session, dependencyRequest)
        .getArtifactResults()
        .stream()
        .map(ArtifactResult::getArtifact)
        .peek(a -> verbose("Artifact %s resolved to %s", a, a.getFile()))
        .collect(Collectors.toList());
  }

  List<Path> getPaths() {
    return paths;
  }

  private void verbose(String format, Object... args) {
    if (mojo.isVerbose()) {
      mojo.getLog().debug(String.format(format, args));
    }
  }
}
