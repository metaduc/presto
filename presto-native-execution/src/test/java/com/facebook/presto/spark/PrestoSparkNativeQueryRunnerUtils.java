/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.spark;

import com.facebook.presto.functionNamespace.FunctionNamespaceManagerPlugin;
import com.facebook.presto.functionNamespace.json.JsonFileBasedFunctionNamespaceManagerFactory;
import com.facebook.presto.hive.metastore.Database;
import com.facebook.presto.hive.metastore.ExtendedHiveMetastore;
import com.facebook.presto.nativeworker.PrestoNativeQueryRunnerUtils;
import com.facebook.presto.spark.classloader_interface.PrestoSparkNativeExecutionShuffleManager;
import com.facebook.presto.spark.execution.NativeExecutionModule;
import com.facebook.presto.spark.execution.TestNativeExecutionModule;
import com.facebook.presto.spi.security.PrincipalType;
import com.facebook.presto.testing.QueryRunner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.inject.Module;
import org.apache.spark.SparkEnv;
import org.apache.spark.shuffle.ShuffleHandle;
import org.apache.spark.shuffle.sort.BypassMergeSortShuffleHandle;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.facebook.presto.nativeworker.NativeQueryRunnerUtils.getNativeWorkerHiveProperties;
import static com.facebook.presto.nativeworker.NativeQueryRunnerUtils.getNativeWorkerSystemProperties;
import static com.facebook.presto.spark.PrestoSparkQueryRunner.METASTORE_CONTEXT;
import static java.nio.file.Files.createTempDirectory;
import static java.util.Objects.requireNonNull;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

public class PrestoSparkNativeQueryRunnerUtils
{
    private static final int AVAILABLE_CPU_COUNT = 4;
    private static final String SPARK_SHUFFLE_MANAGER = "spark.shuffle.manager";
    private static final String FALLBACK_SPARK_SHUFFLE_MANAGER = "spark.fallback.shuffle.manager";
    private static Optional<Path> dataDirectory = Optional.empty();

    private PrestoSparkNativeQueryRunnerUtils() {}

    public static PrestoSparkQueryRunner createPrestoSparkNativeQueryRunner()
    {
        ImmutableMap.Builder<String, String> builder = new ImmutableMap.Builder<String, String>()
                // Do not use default Prestissimo config files. Presto-Spark will generate the configs on-the-fly.
                .put("catalog.config-dir", "/")
                .put("native-execution-enabled", "true")
                .put("spark.initial-partition-count", "1")
                .put("register-test-functions", "true")
                .put("spark.partition-count-auto-tune-enabled", "false");

        if (System.getProperty("NATIVE_PORT") == null) {
            String path = requireNonNull(System.getProperty("PRESTO_SERVER"),
                    "Native worker binary path is missing. " +
                            "Add -DPRESTO_SERVER=/path/to/native/process/bin to your JVM arguments.");
            builder.put("native-execution-executable-path", path);
        }

        PrestoSparkQueryRunner queryRunner = createPrestoSparkNativeQueryRunner(
                Optional.of(getBaseDataPath()),
                builder.build(),
                getNativeExecutionShuffleConfigs(),
                getNativeExecutionModules());
        setupJsonFunctionNamespaceManager(queryRunner);
        return queryRunner;
    }

    public static PrestoSparkQueryRunner createPrestoSparkNativeQueryRunner(Optional<Path> baseDir, Map<String, String> additionalConfigProperties, Map<String, String> additionalSparkProperties, ImmutableList<Module> nativeModules)
    {
        String dataDirectory = System.getProperty("DATA_DIR");

        ImmutableMap.Builder<String, String> configBuilder = ImmutableMap.builder();
        configBuilder.putAll(getNativeWorkerSystemProperties()).putAll(additionalConfigProperties);

        PrestoSparkQueryRunner queryRunner = new PrestoSparkQueryRunner(
                "hive",
                configBuilder.build(),
                getNativeWorkerHiveProperties(),
                additionalSparkProperties,
                baseDir,
                nativeModules,
                AVAILABLE_CPU_COUNT);

        ExtendedHiveMetastore metastore = queryRunner.getMetastore();
        if (!metastore.getDatabase(METASTORE_CONTEXT, "tpch").isPresent()) {
            metastore.createDatabase(METASTORE_CONTEXT, createDatabaseMetastoreObject("tpch"));
        }
        return queryRunner;
    }

    public static QueryRunner createJavaQueryRunner()
            throws Exception
    {
        String dataDirectory = System.getProperty("DATA_DIR");
        return PrestoNativeQueryRunnerUtils.createJavaQueryRunner(Optional.of(getBaseDataPath()), "legacy");
    }

    public static void assertShuffleMetadata()
    {
        assertNotNull(SparkEnv.get());
        assertTrue(SparkEnv.get().shuffleManager() instanceof PrestoSparkNativeExecutionShuffleManager);
        PrestoSparkNativeExecutionShuffleManager shuffleManager = (PrestoSparkNativeExecutionShuffleManager) SparkEnv.get().shuffleManager();
        Set<Integer> partitions = shuffleManager.getAllPartitions();

        for (Integer partition : partitions) {
            Optional<ShuffleHandle> shuffleHandle = shuffleManager.getShuffleHandle(partition);
            assertTrue(shuffleHandle.isPresent());
            assertTrue(shuffleHandle.get() instanceof BypassMergeSortShuffleHandle);
        }
    }

    private static Database createDatabaseMetastoreObject(String name)
    {
        return Database.builder()
                .setDatabaseName(name)
                .setOwnerName("public")
                .setOwnerType(PrincipalType.ROLE)
                .build();
    }

    private static Map<String, String> getNativeExecutionShuffleConfigs()
    {
        ImmutableMap.Builder<String, String> sparkConfigs = ImmutableMap.builder();
        sparkConfigs.put(SPARK_SHUFFLE_MANAGER, "com.facebook.presto.spark.classloader_interface.PrestoSparkNativeExecutionShuffleManager");
        sparkConfigs.put(FALLBACK_SPARK_SHUFFLE_MANAGER, "org.apache.spark.shuffle.sort.SortShuffleManager");
        return sparkConfigs.build();
    }

    private static void setupJsonFunctionNamespaceManager(QueryRunner queryRunner)
    {
        queryRunner.installPlugin(new FunctionNamespaceManagerPlugin());
        queryRunner.loadFunctionNamespaceManager(
                JsonFileBasedFunctionNamespaceManagerFactory.NAME,
                "json",
                ImmutableMap.of(
                        "supported-function-languages", "CPP",
                        "function-implementation-type", "CPP",
                        "json-based-function-manager.path-to-function-definition", "src/test/resources/external_functions.json"));
    }

    private static ImmutableList<Module> getNativeExecutionModules()
    {
        ImmutableList.Builder<Module> moduleBuilder = ImmutableList.builder();
        if (System.getProperty("NATIVE_PORT") != null) {
            moduleBuilder.add(new TestNativeExecutionModule());
        }
        else {
            moduleBuilder.add(new NativeExecutionModule());
        }

        return moduleBuilder.build();
    }

    private static synchronized Path getBaseDataPath()
    {
        if (dataDirectory.isPresent()) {
            return dataDirectory.get();
        }
        String dataDirectoryStr = System.getProperty("DATA_DIR");
        if (dataDirectoryStr.isEmpty()) {
            try {
                dataDirectory = Optional.of(createTempDirectory("PrestoTest").toAbsolutePath());
            }
            catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        else {
            dataDirectory = Optional.of(Paths.get(dataDirectoryStr));
        }
        return dataDirectory.get();
    }
}
