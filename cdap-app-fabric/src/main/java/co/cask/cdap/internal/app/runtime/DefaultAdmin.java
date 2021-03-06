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

package co.cask.cdap.internal.app.runtime;

import co.cask.cdap.api.Admin;
import co.cask.cdap.api.dataset.DatasetManagementException;
import co.cask.cdap.api.dataset.DatasetProperties;
import co.cask.cdap.api.dataset.DatasetSpecification;
import co.cask.cdap.api.dataset.InstanceNotFoundException;
import co.cask.cdap.data2.dataset2.DatasetFramework;
import co.cask.cdap.proto.Id;
import co.cask.cdap.proto.id.NamespaceId;

import java.io.IOException;

/**
 * Implementation of Admin that delegates dataset operations to a dataset framework.
 */
public class DefaultAdmin implements Admin {

  private final DatasetFramework dsFramework;
  private final NamespaceId namespace;

  public DefaultAdmin(DatasetFramework dsFramework, NamespaceId namespace) {
    this.dsFramework = dsFramework;
    this.namespace = namespace;
  }

  private Id.DatasetInstance createInstanceId(String name) {
    return Id.DatasetInstance.from(namespace.getNamespace(), name);
  }

  @Override
  public boolean datasetExists(String name) throws DatasetManagementException {
    return dsFramework.getDatasetSpec(createInstanceId(name)) != null;
  }

  @Override
  public String getDatasetType(String name) throws DatasetManagementException {
    DatasetSpecification spec = dsFramework.getDatasetSpec(createInstanceId(name));
    if (spec == null) {
      throw new InstanceNotFoundException(name);
    }
    return spec.getType();
  }

  @Override
  public DatasetProperties getDatasetProperties(String name) throws DatasetManagementException {
    DatasetSpecification spec = dsFramework.getDatasetSpec(createInstanceId(name));
    if (spec == null) {
      throw new InstanceNotFoundException(name);
    }
    return DatasetProperties.builder().addAll(spec.getOriginalProperties()).build();
  }

  @Override
  public void createDataset(String name, String type, DatasetProperties properties) throws DatasetManagementException {
    try {
      dsFramework.addInstance(type, createInstanceId(name), properties);
    } catch (IOException ioe) {
      // not the prettiest message, but this replicates exactly what RemoteDatasetFramework throws
      throw new DatasetManagementException(String.format("Failed to add instance %s, details: %s",
                                                         name, ioe.getMessage()), ioe);
    }
  }

  @Override
  public void updateDataset(String name, DatasetProperties properties) throws DatasetManagementException {
    try {
      dsFramework.updateInstance(createInstanceId(name), properties);
    } catch (IOException ioe) {
      // not the prettiest message, but this replicates exactly what RemoteDatasetFramework throws
      throw new DatasetManagementException(String.format("Failed to update instance %s, details: %s",
                                                         name, ioe.getMessage()), ioe);
    }
  }

  @Override
  public void dropDataset(String name) throws DatasetManagementException {
    try {
      dsFramework.deleteInstance(createInstanceId(name));
    } catch (IOException ioe) {
      // not the prettiest message, but this replicates exactly what RemoteDatasetFramework throws
      throw new DatasetManagementException(String.format("Failed to delete instance %s, details: %s",
                                                         name, ioe.getMessage()), ioe);
    }
  }

  @Override
  public void truncateDataset(String name) throws DatasetManagementException {
    try {
      dsFramework.truncateInstance(createInstanceId(name));
    } catch (IOException ioe) {
      // not the prettiest message, but this replicates exactly what RemoteDatasetFramework throws
      throw new DatasetManagementException(String.format("Failed to truncate instance %s, details: %s",
                                                         name, ioe.getMessage()), ioe);
    }
  }
}
