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

package co.cask.cdap.internal.app;

import co.cask.cdap.api.artifact.ArtifactDescriptor;
import co.cask.cdap.api.artifact.Plugin;
import co.cask.cdap.api.artifact.PluginConfigurer;
import co.cask.cdap.api.artifact.PluginSelector;
import co.cask.cdap.api.templates.plugins.PluginClass;
import co.cask.cdap.api.templates.plugins.PluginProperties;
import co.cask.cdap.api.templates.plugins.PluginPropertyField;
import co.cask.cdap.internal.api.DefaultDatasetConfigurer;
import co.cask.cdap.internal.app.runtime.adapter.PluginInstantiator;
import co.cask.cdap.internal.app.runtime.artifact.ArtifactRepository;
import co.cask.cdap.internal.app.runtime.artifact.PluginNotExistsException;
import co.cask.cdap.proto.Id;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.Maps;

import java.io.IOException;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Contains implementation of methods in {@link PluginConfigurer} thus assisting Program configurers who can extend
 * this class.
 */
public class DefaultPluginConfigurer extends DefaultDatasetConfigurer implements PluginConfigurer {

  private final ArtifactRepository artifactRepository;
  private final PluginInstantiator pluginInstantiator;
  private final Id.Artifact artifactId;
  private final Map<String, Plugin> plugins;

  public DefaultPluginConfigurer(ArtifactRepository artifactRepository, PluginInstantiator pluginInstantiator,
                                 Id.Artifact artifactId) {
    this.artifactRepository = artifactRepository;
    this.pluginInstantiator = pluginInstantiator;
    this.artifactId = artifactId;
    this.plugins = Maps.newHashMap();
  }

  public Map<String, Plugin> getPlugins() {
    return plugins;
  }

  public void addPlugins(Map<String, Plugin> plugins) {
    this.plugins.putAll(plugins);
  }

  @Nullable
  @Override
  public <T> T usePlugin(String pluginType, String pluginName, String pluginId, PluginProperties properties) {
    return usePlugin(pluginType, pluginName, pluginId, properties, new PluginSelector());
  }

  @Nullable
  @Override
  public <T> T usePlugin(String pluginType, String pluginName, String pluginId, PluginProperties properties,
                         PluginSelector selector) {
    Map.Entry<ArtifactDescriptor, PluginClass> pluginEntry = findPlugin(pluginType, pluginName, pluginId, properties,
                                                                        selector);
    if (pluginEntry == null) {
      return null;
    }

    try {
      T instance = pluginInstantiator.newInstance(pluginEntry.getKey(), pluginEntry.getValue(), properties);
      registerPlugin(pluginId, pluginEntry.getKey(), pluginEntry.getValue(), properties);
      return instance;
    } catch (IOException e) {
      // If the plugin jar is deleted without notifying the adapter service.
      return null;
    } catch (ClassNotFoundException e) {
      // Shouldn't happen
      throw Throwables.propagate(e);
    }
  }

  @Nullable
  @Override
  public <T> Class<T> usePluginClass(String pluginType, String pluginName, String pluginId,
                                     PluginProperties properties) {
    return usePluginClass(pluginType, pluginName, pluginId, properties, new PluginSelector());
  }

  @Nullable
  @Override
  public <T> Class<T> usePluginClass(String pluginType, String pluginName, String pluginId, PluginProperties properties,
                                     PluginSelector selector) {
    Map.Entry<ArtifactDescriptor, PluginClass> pluginEntry = findPlugin(pluginType, pluginName, pluginId, properties,
                                                                        selector);
    if (pluginEntry == null) {
      return null;
    }

    try {
      Class<T> cls = pluginInstantiator.loadClass(pluginEntry.getKey(), pluginEntry.getValue());
      registerPlugin(pluginId, pluginEntry.getKey(), pluginEntry.getValue(), properties);
      return cls;
    } catch (IOException e) {
      // If the plugin jar is deleted without notifying the adapter service.
      return null;
    } catch (ClassNotFoundException e) {
      // Shouldn't happen
      throw Throwables.propagate(e);
    }
  }

  private Map.Entry<ArtifactDescriptor, PluginClass> findPlugin(String pluginType, String pluginName, String pluginId,
                                                                PluginProperties properties, PluginSelector selector) {
    Preconditions.checkArgument(!plugins.containsKey(pluginId),
                                "Plugin of type %s, name %s was already added.", pluginType, pluginName);
    Preconditions.checkArgument(properties != null, "Plugin properties cannot be null");

    Map.Entry<ArtifactDescriptor, PluginClass> pluginEntry;
    try {
      pluginEntry = artifactRepository.findPlugin(artifactId, pluginType, pluginName, selector);
    } catch (IOException e) {
      throw Throwables.propagate(e);
    } catch (PluginNotExistsException e) {
      throw new IllegalArgumentException(String.format("Plugin of type %s, name %s could not be found",
                                                       pluginType, pluginName), e);
    }

    if (pluginEntry != null) {
      // Just verify if all required properties are provided.
      // No type checking is done for now.
      for (PluginPropertyField field : pluginEntry.getValue().getProperties().values()) {
        Preconditions.checkArgument(!field.isRequired() || properties.getProperties().containsKey(field.getName()),
                                    "Required property '%s' missing for plugin of type %s, name %s.",
                                    field.getName(), pluginType, pluginName);
      }
    }
    return pluginEntry;
  }

  /**
   * Register the given plugin in this configurer.
   */
  private void registerPlugin(String pluginId, ArtifactDescriptor artifactDescriptor, PluginClass pluginClass,
                              PluginProperties properties) {
    plugins.put(pluginId, new Plugin(artifactDescriptor.getArtifactId(), artifactDescriptor.getLocation().toURI(),
                                     pluginClass, properties));
  }
}