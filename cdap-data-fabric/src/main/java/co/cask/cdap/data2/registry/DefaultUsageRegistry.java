/*
 * Copyright © 2016 Cask Data, Inc.
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

package co.cask.cdap.data2.registry;

import co.cask.cdap.api.dataset.DatasetDefinition;
import co.cask.cdap.api.dataset.DatasetManagementException;
import co.cask.cdap.api.dataset.DatasetProperties;
import co.cask.cdap.data.runtime.DataSetsModules;
import co.cask.cdap.data2.datafabric.dataset.DatasetsUtil;
import co.cask.cdap.data2.dataset2.DatasetFramework;
import co.cask.cdap.data2.transaction.Transactions;
import co.cask.cdap.proto.Id;
import co.cask.tephra.TransactionExecutor;
import co.cask.tephra.TransactionExecutorFactory;
import com.google.common.base.Objects;
import com.google.common.base.Throwables;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.inject.Inject;
import com.google.inject.name.Named;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Set;

/**
 * Store program -> dataset/stream usage information.
 *
 * TODO: Reduce duplication between this and {@link UsageDataset}.
 *
 * this is a singleton to make sure that DatasetService and AppFabric share the same instance of the registry.
 * that is required because otherwise DatasetService would be unaware of a unregister() call made by app fabric when
 * an app is deleted. As a consequence, it would not invalidate its cache for the usage registration. That in turn
 * would prevent the usage from being re-registered after the app is redeployed.
 */
public class DefaultUsageRegistry implements UsageRegistry {

  private static final Logger LOG = LoggerFactory.getLogger(DefaultUsageRegistry.class);

  private static final Id.DatasetInstance USAGE_INSTANCE_ID =
    Id.DatasetInstance.from(Id.Namespace.SYSTEM, "usage.registry");

  private final TransactionExecutorFactory executorFactory;
  private final DatasetFramework datasetFramework;

  @Inject
  public DefaultUsageRegistry(TransactionExecutorFactory executorFactory,
                              @Named(DataSetsModules.BASIC_DATASET_FRAMEWORK) DatasetFramework datasetFramework) {
    this.executorFactory = executorFactory;
    this.datasetFramework = datasetFramework;

    // using a max size of 1024: memory footprint is small, and still it is large enough to
    // avoid repeated registration of the same program when it starts many containers concurrently.
    // assuming that it is untypical that more than 1024 programs start at the sam time.
    this.usageCache = CacheBuilder.newBuilder().maximumSize(1024).build(
      new CacheLoader<DatasetUsageKey, Boolean>() {
        @Override
        public Boolean load(DatasetUsageKey key) throws Exception {
          doRegister(key.getOwner(), key.getDataset());
          return true;
        }
      }
    );
  }

  // this cache will avoid duplicate registration by the same owner if a program repeatedly gets the same dataset.
  // for streams, that does not seem necessary, because programs register stream usage once at startup.
  private LoadingCache<DatasetUsageKey, Boolean> usageCache;

  private <T> T execute(TransactionExecutor.Function<UsageDataset, T> func) {
    UsageDataset usageDataset = newUsageDataset();
    return Transactions.createTransactionExecutor(executorFactory, usageDataset)
      .executeUnchecked(func, usageDataset);
  }

  private void execute(TransactionExecutor.Procedure<UsageDataset> func) {
    UsageDataset usageDataset = newUsageDataset();
    Transactions.createTransactionExecutor(executorFactory, usageDataset)
      .executeUnchecked(func, usageDataset);
  }

  private UsageDataset newUsageDataset() {
    try {
      return DatasetsUtil.getOrCreateDataset(
        datasetFramework, USAGE_INSTANCE_ID, UsageDataset.class.getSimpleName(),
        DatasetProperties.EMPTY, DatasetDefinition.NO_ARGUMENTS, null);
    } catch (Exception e) {
      throw Throwables.propagate(e);
    }
  }

  /**
   * Registers usage of a stream by multiple ids.
   *
   * @param users    the users of the stream
   * @param streamId the stream
   */
  public void registerAll(final Iterable<? extends Id> users, final Id.Stream streamId) {
    for (Id user : users) {
      register(user, streamId);
    }
  }

  /**
   * Register usage of a stream by an id.
   *
   * @param user     the user of the stream
   * @param streamId the stream
   */
  public void register(Id user, Id.Stream streamId) {
    if (user instanceof Id.Program) {
      register((Id.Program) user, streamId);
    }
  }

  /**
   * Registers usage of a stream by multiple ids.
   *
   * @param users     the users of the stream
   * @param datasetId the stream
   */
  public void registerAll(final Iterable<? extends Id> users, final Id.DatasetInstance datasetId) {
    for (Id user : users) {
      register(user, datasetId);
    }
  }

  /**
   * Registers usage of a dataset by multiple ids.
   *
   * @param user      the user of the dataset
   * @param datasetId the dataset
   */
  public void register(Id user, Id.DatasetInstance datasetId) {
    if (user instanceof Id.Program) {
      register((Id.Program) user, datasetId);
    }
  }

  /**
   * Registers usage of a dataset by a program.
   *
   * @param programId         program
   * @param datasetInstanceId dataset
   */
  public void register(final Id.Program programId, final Id.DatasetInstance datasetInstanceId) {
    usageCache.getUnchecked(new DatasetUsageKey(datasetInstanceId, programId));
  }

  /**
   * Internal method to register usage of a dataset by a program, called from the cache loader.
   *
   * @param programId program
   * @param datasetInstanceId  dataset
   */
  private void doRegister(final Id.Program programId, final Id.DatasetInstance datasetInstanceId) {
    execute(new TransactionExecutor.Procedure<UsageDataset>() {
      @Override
      public void apply(UsageDataset usageDataset) throws Exception {
        usageDataset.register(programId, datasetInstanceId);
      }
    });
  }

  /**
   * Registers usage of a stream by a program.
   *
   * @param programId program
   * @param streamId  stream
   */
  public void register(final Id.Program programId, final Id.Stream streamId) {
    execute(new TransactionExecutor.Procedure<UsageDataset>() {
      @Override
      public void apply(UsageDataset usageDataset) throws Exception {
        usageDataset.register(programId, streamId);
      }
    });
  }

  /**
   * Unregisters all usage information of an application.
   *
   * @param applicationId application
   */
  public void unregister(final Id.Application applicationId) {
    execute(new TransactionExecutor.Procedure<UsageDataset>() {
      @Override
      public void apply(UsageDataset usageDataset) throws Exception {
        usageDataset.unregister(applicationId);
      }
    });

    // we must invalidate the cache for all programs of this application. Because if, for example, an
    // application is deleted, its usage is removed from the registry. If it is redeployed later, we
    // must register its usage again. That would not happen if the cache still holds these entries.
    for (DatasetUsageKey key : usageCache.asMap().keySet()) {
      if (applicationId.equals(key.getOwner().getApplication())) {
        usageCache.invalidate(key);
      }
    }
  }

  public Set<Id.DatasetInstance> getDatasets(final Id.Application id) {
    return execute(new TransactionExecutor.Function<UsageDataset, Set<Id.DatasetInstance>>() {
      @Override
      public Set<Id.DatasetInstance> apply(UsageDataset usageDataset) throws Exception {
        return usageDataset.getDatasets(id);
      }
    });
  }

  public Set<Id.Stream> getStreams(final Id.Application id) {
    return execute(new TransactionExecutor.Function<UsageDataset, Set<Id.Stream>>() {
      @Override
      public Set<Id.Stream> apply(UsageDataset usageDataset) throws Exception {
        return usageDataset.getStreams(id);
      }
    });
  }

  public Set<Id.DatasetInstance> getDatasets(final Id.Program id) {
    return execute(new TransactionExecutor.Function<UsageDataset, Set<Id.DatasetInstance>>() {
      @Override
      public Set<Id.DatasetInstance> apply(UsageDataset usageDataset) throws Exception {
        return usageDataset.getDatasets(id);
      }
    });
  }

  public Set<Id.Stream> getStreams(final Id.Program id) {
    return execute(new TransactionExecutor.Function<UsageDataset, Set<Id.Stream>>() {
      @Override
      public Set<Id.Stream> apply(UsageDataset usageDataset) throws Exception {
        return usageDataset.getStreams(id);
      }
    });
  }

  public Set<Id.Program> getPrograms(final Id.Stream id) {
    return execute(new TransactionExecutor.Function<UsageDataset, Set<Id.Program>>() {
      @Override
      public Set<Id.Program> apply(UsageDataset usageDataset) throws Exception {
        return usageDataset.getPrograms(id);
      }
    });
  }

  public Set<Id.Program> getPrograms(final Id.DatasetInstance id) {
    return execute(new TransactionExecutor.Function<UsageDataset, Set<Id.Program>>() {
      @Override
      public Set<Id.Program> apply(UsageDataset usageDataset) throws Exception {
        return usageDataset.getPrograms(id);
      }
    });
  }

  /**
   * Adds datasets and types to the given {@link DatasetFramework} used by usage registry.
   *
   * @param datasetFramework framework to add types and datasets to
   */
  public static void setupDatasets(DatasetFramework datasetFramework) throws IOException, DatasetManagementException {
    datasetFramework.addInstance(UsageDataset.class.getName(), USAGE_INSTANCE_ID, DatasetProperties.EMPTY);
  }

  static class DatasetUsageKey {
    private final Id.DatasetInstance dataset;
    private final Id.Program owner;

    DatasetUsageKey(Id.DatasetInstance dataset, Id.Program owner) {
      this.dataset = dataset;
      this.owner = owner;
    }

    Id.DatasetInstance getDataset() {
      return dataset;
    }

    Id.Program getOwner() {
      return owner;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      DatasetUsageKey that = (DatasetUsageKey) o;
      return Objects.equal(dataset, that.dataset) &&
        Objects.equal(owner, that.owner);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(dataset, owner);
    }
  }
}
