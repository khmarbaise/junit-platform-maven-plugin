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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.apache.maven.project.MavenProject;

class Patcher {

  private final JUnitPlatformMojo mojo;
  private final MavenProject project;
  private final Modules modules;

  Patcher(JUnitPlatformMojo mojo) {
    this.mojo = mojo;
    this.project = mojo.getMavenProject();
    this.modules = mojo.getProjectModules();
  }

  void patch(List<String> cmd) {
    var testOutput = project.getBuild().getTestOutputDirectory();

    var descriptor = modules.getMainModuleReference().orElseThrow().descriptor();
    var name = descriptor.name();

    mojo.debug("");
    mojo.debug("Patching tests into main module %s <- '%s'", name, testOutput);
    cmd.add("--patch-module");
    cmd.add(name + '=' + testOutput);

    // Apply user-defined command line options
    var testSource = project.getBuild().getTestSourceDirectory();
    var roots = Set.of(Paths.get(testSource), Paths.get(testOutput));
    var moduleInfoTest = mojo.getFileNames().resolveModuleInfoTest(roots);
    if (moduleInfoTest.isPresent()) {
      var moduleInfoTestPath = moduleInfoTest.get();
      mojo.debug("Using lines of '%s' to patch module %s...", moduleInfoTestPath, name);
      appendModuleInfoTestArguments(moduleInfoTestPath, cmd::add);
      return;
    }

    // Apply best-effort options...
    mojo.debug("Adding best-effort command line options to patch module %s...", name);
    var addReads = createAddReadsModules();
    addReads.forEach(
        module -> {
          cmd.add("--add-reads");
          cmd.add(name + "=" + module);
        });
    for (var module : createAddOpensModules()) {
      // iterate all packages, "name/*" is not possible due to
      // http://mail.openjdk.java.net/pipermail/jigsaw-dev/2017-January/010749.html
      for (var pack : descriptor.packages()) {
        cmd.add("--add-opens");
        cmd.add(name + "/" + pack + "=" + module);
      }
    }
  }

  private void appendModuleInfoTestArguments(Path moduleInfoTestPath, Consumer<String> consume) {
    try (var lines = Files.lines(moduleInfoTestPath)) {
      lines
          .map(String::trim)
          .filter(line -> !line.isEmpty())
          .filter(line -> !line.startsWith("//"))
          .peek(line -> mojo.debug("  %s", line))
          .forEach(consume);
    } catch (IOException e) {
      throw new UncheckedIOException("Reading " + moduleInfoTestPath + " failed", e);
    }
  }

  private List<String> createAddOpensModules() {
    var value = mojo.getJavaOptions().getAddOpens();
    if (value != null) {
      return value;
    }
    var modules = new ArrayList<String>();
    var map = project.getArtifactMap();
    if (map.containsKey("org.junit.platform:junit-platform-commons")) {
      modules.add("org.junit.platform.commons");
    }
    return modules;
  }

  private List<String> createAddReadsModules() {
    var value = mojo.getJavaOptions().getAddReads();
    if (value != null) {
      return value;
    }
    var modules = new ArrayList<String>();
    var map = project.getArtifactMap();
    // Jupiter
    if (map.containsKey("org.junit.jupiter:junit-jupiter-api")) {
      modules.add("org.junit.jupiter.api");
    }
    if (map.containsKey("org.junit.jupiter:junit-jupiter-params")) {
      modules.add("org.junit.jupiter.params");
    }
    if (map.containsKey("org.junit.jupiter:junit-jupiter-migrationsupport")) {
      modules.add("org.junit.jupiter.migrationsupport");
    }
    // JUnit 3/4
    if (map.containsKey("junit:junit")) {
      modules.add("junit");
    }

    return modules;
  }
}
