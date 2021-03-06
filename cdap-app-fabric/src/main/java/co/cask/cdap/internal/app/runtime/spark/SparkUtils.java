/*
 * Copyright © 2015 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.cdap.internal.app.runtime.spark;

import co.cask.cdap.common.conf.Constants;
import co.cask.cdap.common.utils.DirUtils;
import co.cask.cdap.internal.app.runtime.distributed.LocalizeResource;
import com.google.common.base.Preconditions;
import org.apache.hadoop.yarn.api.ApplicationConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * A utility class to help determine Spark supports and locating Spark jar.
 * TODO: CDAP-5506. Ideally this class shouldn't be in app-fabric, but should be in spark-core.
 *
 */
public final class SparkUtils {

  private static final Logger LOG = LoggerFactory.getLogger(SparkUtils.class);

  // Environment variable name for locating spark assembly jar file
  private static final String SPARK_ASSEMBLY_JAR = "SPARK_ASSEMBLY_JAR";
  // Environment variable name for locating spark home directory
  private static final String SPARK_HOME = Constants.SPARK_HOME;

  // File name of the Spark conf directory as defined by the Spark framework
  // This is for the Hack to workaround CDAP-5019 (SPARK-13441)
  public static final String LOCALIZED_CONF_DIR = "__spark_conf__";

  private static File sparkAssemblyJar;

  /**
   * Locates the spark-assembly jar from the local file system.
   *
   * @return the spark-assembly jar location
   * @throws IllegalStateException if cannot locate the spark assembly jar
   */
  public static synchronized File locateSparkAssemblyJar() {
    if (sparkAssemblyJar != null) {
      return sparkAssemblyJar;
    }

    // If someone explicitly set the location, use it.
    // It's useful for overridding what being set for SPARK_HOME
    String jarEnv = System.getenv(SPARK_ASSEMBLY_JAR);
    if (jarEnv != null) {
      File file = new File(jarEnv);
      if (file.isFile()) {
        LOG.info("Located Spark Assembly JAR in {}", file);
        sparkAssemblyJar = file;
        return file;
      }
      LOG.warn("Env $" + SPARK_ASSEMBLY_JAR + "=" + jarEnv + " is not a file. " +
                 "Will locate Spark Assembly JAR with $" + SPARK_HOME);
    }

    String sparkHome = System.getenv(SPARK_HOME);
    if (sparkHome == null) {
      throw new IllegalStateException("Spark library not found. " +
                                        "Please set environment variable " + SPARK_HOME + " or " + SPARK_ASSEMBLY_JAR);
    }

    // Look for spark-assembly.jar symlink
    Path assemblyJar = Paths.get(sparkHome, "lib", "spark-assembly.jar");
    if (Files.isSymbolicLink(assemblyJar)) {
      sparkAssemblyJar = assemblyJar.toFile();
      return sparkAssemblyJar;
    }

    // No symbolic link exists. Search for spark-assembly*.jar in the lib directory
    Path sparkLib = Paths.get(sparkHome, "lib");
    final PathMatcher pathMatcher = sparkLib.getFileSystem().getPathMatcher("glob:spark-assembly*.jar");
    try {
      Files.walkFileTree(sparkLib, new SimpleFileVisitor<Path>() {
        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
          // Take the first file match
          if (attrs.isRegularFile() && pathMatcher.matches(file.getFileName())) {
            sparkAssemblyJar = file.toFile();
            return FileVisitResult.TERMINATE;
          }
          return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
          // Ignore error
          return FileVisitResult.CONTINUE;
        }

      });

    } catch (IOException e) {
      // Just log, don't throw.
      // If we already located the Spark Assembly jar during visiting, we can still use the jar.
      LOG.warn("Exception raised while inspecting {}", sparkLib, e);
    }

    Preconditions.checkState(sparkAssemblyJar != null, "Failed to locate Spark library from %s", sparkHome);

    LOG.info("Located Spark Assembly JAR in {}", sparkAssemblyJar);
    return sparkAssemblyJar;
  }

  /**
   * Prepares the resources that need to be localized to the Spark client container.
   *
   * @param tempDir a temporary directory for file creation
   * @param localizeResources A map from localized name to {@link LocalizeResource} for this method to update
   * @return localized name of the Spark assembly jar file
   */
  public static String prepareSparkResources(File tempDir, Map<String, LocalizeResource> localizeResources) {
    File sparkAssemblyJar = locateSparkAssemblyJar();
    localizeResources.put(sparkAssemblyJar.getName(), new LocalizeResource(sparkAssemblyJar));

    // Shallow copy all files under directory defined by $HADOOP_CONF_DIR
    // If $HADOOP_CONF_DIR is not defined, use the location of "yarn-site.xml" to determine the directory
    // This is part of workaround for CDAP-5019 (SPARK-13441).
    File hadoopConfDir = null;
    if (System.getenv().containsKey(ApplicationConstants.Environment.HADOOP_CONF_DIR.key())) {
      hadoopConfDir = new File(System.getenv(ApplicationConstants.Environment.HADOOP_CONF_DIR.key()));
    } else {
      URL yarnSiteLocation = SparkUtils.class.getClassLoader().getResource("yarn-site.xml");
      if (yarnSiteLocation != null) {
        try {
          hadoopConfDir = new File(yarnSiteLocation.toURI()).getParentFile();
        } catch (URISyntaxException e) {
          // Shouldn't happen
          LOG.warn("Failed to derive HADOOP_CONF_DIR from yarn-site.xml");
        }
      }
    }
    if (hadoopConfDir != null && hadoopConfDir.isDirectory()) {
      try {
        final File targetFile = File.createTempFile(LOCALIZED_CONF_DIR, ".zip", tempDir);
        try (
          ZipOutputStream zipOutput = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(targetFile)))
        ) {
          for (File file : DirUtils.listFiles(hadoopConfDir)) {
            // Shallow copy of files under the hadoop conf dir. Ignore files that cannot be read
            if (file.isFile() && file.canRead()) {
              zipOutput.putNextEntry(new ZipEntry(file.getName()));
              Files.copy(file.toPath(), zipOutput);
            }
          }
        }
        localizeResources.put(LOCALIZED_CONF_DIR, new LocalizeResource(targetFile, true));
      } catch (IOException e) {
        LOG.warn("Failed to create archive from {}", hadoopConfDir, e);
      }
    }

    return sparkAssemblyJar.getName();
  }

  private SparkUtils() {
  }
}
