/*
 * Copyright © 2014-2016 Cask Data, Inc.
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

package co.cask.cdap.internal.app.deploy.pipeline;

import co.cask.cdap.api.ProgramSpecification;
import co.cask.cdap.api.app.ApplicationSpecification;
import co.cask.cdap.app.program.ProgramDescriptor;
import co.cask.cdap.common.conf.CConfiguration;
import co.cask.cdap.common.conf.Constants;
import co.cask.cdap.common.namespace.NamespacedLocationFactory;
import co.cask.cdap.pipeline.AbstractStage;
import co.cask.cdap.proto.Id;
import co.cask.cdap.proto.ProgramType;
import co.cask.cdap.proto.ProgramTypes;
import co.cask.cdap.proto.id.ProgramId;
import co.cask.cdap.proto.security.Action;
import co.cask.cdap.security.spi.authentication.SecurityRequestContext;
import co.cask.cdap.security.spi.authorization.Authorizer;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.reflect.TypeToken;
import org.apache.twill.filesystem.Location;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 *
 */
public class ProgramGenerationStage extends AbstractStage<ApplicationDeployable> {
  private final CConfiguration configuration;
  private final NamespacedLocationFactory namespacedLocationFactory;
  private final Authorizer authorizer;

  public ProgramGenerationStage(CConfiguration configuration, NamespacedLocationFactory namespacedLocationFactory,
                                Authorizer authorizer) {
    super(TypeToken.of(ApplicationDeployable.class));
    this.configuration = configuration;
    this.namespacedLocationFactory = namespacedLocationFactory;
    this.authorizer = authorizer;
  }

  @Override
  public void process(final ApplicationDeployable input) throws Exception {
    List<ProgramDescriptor> programDescriptors = new ArrayList<>();
    final ApplicationSpecification appSpec = input.getSpecification();

    // Make sure the namespace directory exists
    Id.Namespace namespaceId = input.getApplicationId().getParent().toId();
    Location namespacedLocation = namespacedLocationFactory.get(namespaceId);
    // Note: deployApplication/deployAdapters have already checked for namespaceDir existence, so not checking again
    // Make sure we have a directory to store the original artifact.
    final Location appFabricDir = namespacedLocation.append(configuration.get(Constants.AppFabric.OUTPUT_DIR));

    // Check exists, create, check exists again to avoid failure due to race condition.
    if (!appFabricDir.exists() && !appFabricDir.mkdirs() && !appFabricDir.exists()) {
      throw new IOException(String.format("Failed to create directory %s", appFabricDir));
    }

    // Now, we iterate through all ProgramSpecification and generate programs
    Iterable<ProgramSpecification> specifications = Iterables.concat(
      appSpec.getMapReduce().values(),
      appSpec.getFlows().values(),
      appSpec.getWorkflows().values(),
      appSpec.getServices().values(),
      appSpec.getSpark().values(),
      appSpec.getWorkers().values()
    );

    for (final ProgramSpecification spec: specifications) {
      ProgramType type = ProgramTypes.fromSpecification(spec);
      ProgramId programId = input.getApplicationId().program(type, spec.getName());
      authorizer.grant(programId, SecurityRequestContext.toPrincipal(), ImmutableSet.of(Action.ALL));
      programDescriptors.add(new ProgramDescriptor(programId, appSpec));
    }

    emit(new ApplicationWithPrograms(input, programDescriptors));
  }
}
