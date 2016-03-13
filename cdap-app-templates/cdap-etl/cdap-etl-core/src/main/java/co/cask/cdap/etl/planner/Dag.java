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

package co.cask.cdap.etl.planner;

import co.cask.cdap.etl.proto.Connection;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Sets;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A DAG (directed acyclic graph).
 */
public class Dag {
  private final Set<String> nodes;
  private final Set<String> sources;
  private final Set<String> sinks;
  // stage -> outputs of that stage
  private final SetMultimap<String, String> outgoingConnections;
  // stage -> inputs for that stage
  private final SetMultimap<String, String> incomingConnections;

  public static Dag fromConnections(Collection<Connection> connections) {
    SetMultimap<String, String> outgoingConnections = HashMultimap.create();
    SetMultimap<String, String> incomingConnections = HashMultimap.create();
    for (Connection connection : connections) {
      outgoingConnections.put(connection.getFrom(), connection.getTo());
      incomingConnections.put(connection.getTo(), connection.getFrom());
    }
    Dag dag = new Dag(outgoingConnections, incomingConnections);
    dag.validate();
    return dag;
  }

  private Dag(SetMultimap<String, String> outgoingConnections,
              SetMultimap<String, String> incomingConnections) {
    this(calculateSources(outgoingConnections, incomingConnections),
         calculateSinks(outgoingConnections, incomingConnections),
         outgoingConnections, incomingConnections);
  }

  private Dag(Set<String> sources, Set<String> sinks,
              SetMultimap<String, String> outgoingConnections,
              SetMultimap<String, String> incomingConnections) {
    Preconditions.checkArgument(!outgoingConnections.isEmpty(), "Cannot create a DAG without any connections");
    Preconditions.checkArgument(!incomingConnections.isEmpty(), "Cannot create a DAG without any connections");
    this.sources = new HashSet<>(sources);
    this.sinks = new HashSet<>(sinks);
    this.outgoingConnections = HashMultimap.create(outgoingConnections);
    this.incomingConnections = HashMultimap.create(incomingConnections);
    this.nodes = new HashSet<>();
    nodes.addAll(outgoingConnections.keySet());
    nodes.addAll(outgoingConnections.values());
  }

  /**
   * Validate the DAG is a valid DAG without cycles, and no islands. This should only be called before any
   * mutating operations like {@link #removeSource()} are called.
   *
   * @throws IllegalStateException if there is a cycle in the graph, or an island in the graph
   */
  void validate() {
    // if there are no sources, we must have a cycle.
    if (sources.isEmpty()) {
      throw new IllegalStateException("DAG does not have any sources. Please remove cycles from the graph.");
    }
    // similarly, if there are no sinks, we must have a cycle
    if (sinks.isEmpty()) {
      throw new IllegalStateException("DAG does not have any sinks. Please remove cycles from the graph.");
    }

    // check for cycles
    linearize();

    // check for sections of the dag that are on an island by themselves

    // source -> [ nodes accessible by the source ]
    Map<String, Set<String>> nodesAccessibleBySources = new HashMap<>();
    for (String source : sources) {
      nodesAccessibleBySources.put(source, accessibleFrom(source));
    }

    // the algorithm is to keep an island and try to keep adding to it until we can't anymore.
    // the island starts off as the nodes accessible by the first source
    // we then loop through all other sources and add them to the island if they can access any node in the island.
    // we stop if we ever loop through all other sources and can't add them to the island,
    // or if the island has grown to include all sources.
    Set<String> islandNodes = new HashSet<>();
    // seed the island with the first source
    Set<String> potentialIslandSources = new HashSet<>(sources);
    String firstSource = potentialIslandSources.iterator().next();
    islandNodes.addAll(nodesAccessibleBySources.get(firstSource));
    potentialIslandSources.remove(firstSource);

    while (!potentialIslandSources.isEmpty()) {
      Set<String> sourcesAdded = new HashSet<>();
      // for each source not yet a part of the island
      for (String potentialIslandSource : potentialIslandSources) {
        Set<String> accessibleBySource = nodesAccessibleBySources.get(potentialIslandSource);
        // if that source can access the island in any way, add it to the island
        if (!Sets.intersection(islandNodes, accessibleBySource).isEmpty()) {
          islandNodes.addAll(accessibleBySource);
          sourcesAdded.add(potentialIslandSource);
        }
      }
      // if this is empty, no sources were added to the island. That means the island really is an island.
      if (sourcesAdded.isEmpty()) {
        throw new IllegalStateException(
          String.format("Invalid DAG. There is an island made up of stages %s (no other stages connect to them).",
                        Joiner.on(',').join(islandNodes)));
      }
      potentialIslandSources.removeAll(sourcesAdded);
    }
  }

  public Set<String> getNodes() {
    return nodes;
  }

  public Set<String> getSources() {
    return Collections.unmodifiableSet(sources);
  }

  public Set<String> getSinks() {
    return Collections.unmodifiableSet(sinks);
  }

  public Set<String> getNodeOutputs(String node) {
    return Collections.unmodifiableSet(outgoingConnections.get(node));
  }

  public Set<String> getNodeInputs(String node) {
    return Collections.unmodifiableSet(incomingConnections.get(node));
  }

  /**
   * Return all stages accessible from the specified stage.
   *
   * @param stage the stage to start at
   * @return all stages accessible from that stage
   */
  public Set<String> accessibleFrom(String stage) {
    return traverse(stage, new HashSet<String>(), new HashSet<String>());
  }

  /**
   * Return all stages accessible from the specified stage, without going past any node in stopNodes.
   *
   * @param stage the stage to start at
   * @param stopNodes set of nodes to stop traversal on
   * @return all stages accessible from that stage
   */
  public Set<String> accessibleFrom(String stage, Set<String> stopNodes) {
    return traverse(stage, new HashSet<String>(), stopNodes);
  }

  /**
   * Return a subset of this dag starting from the specified stage.
   * This is equivalent to calling {@link #subsetFrom(String, Set)} with an empty set for stop nodes
   *
   * @param stage the stage to start at
   * @return a dag created from the nodes accessible from the specified stage
   */
  public Dag subsetFrom(String stage) {
    return subsetFrom(stage, ImmutableSet.<String>of());
  }

  /**
   * Return a subset of this dag starting from the specified stage, without going past any node in stopNodes.
   * This is equivalent to taking the nodes from {@link #accessibleFrom(String, Set)} and building a dag from them.
   *
   * @param stage the stage to start at
   * @param stopNodes set of nodes to stop traversal on
   * @return a dag created from the nodes accessible from the specified stage
   */
  public Dag subsetFrom(String stage, Set<String> stopNodes) {
    Set<String> nodes = accessibleFrom(stage, stopNodes);
    Set<Connection> connections = new HashSet<>();
    for (String node : nodes) {
      for (String outputNode : outgoingConnections.get(node)) {
        if (nodes.contains(outputNode)) {
          connections.add(new Connection(node, outputNode));
        }
      }
    }
    return Dag.fromConnections(connections);
  }

  /**
   * Inserts a node in front of the specified node.
   *
   * @param name the name of the new node
   * @param inFrontOf the node to insert in front of
   */
  public void insertNode(String name, String inFrontOf) {
    if (!nodes.contains(inFrontOf)) {
      throw new IllegalArgumentException(
        String.format("Cannot insert in front node %s because it does not exist.", inFrontOf));
    }
    if (!nodes.add(name)) {
      throw new IllegalArgumentException(
        String.format("Cannot insert node %s because it already exists.", name));
    }

    Set<String> inputs = incomingConnections.get(inFrontOf);
    incomingConnections.putAll(name, inputs);
    for (String input : inputs) {
      outgoingConnections.get(input).remove(inFrontOf);
      outgoingConnections.put(input, name);
    }
    outgoingConnections.put(name, inFrontOf);
    incomingConnections.replaceValues(inFrontOf, ImmutableSet.of(name));
  }

  /**
   * Remove a source from the dag. New sources will be re-calculated after the source is removed.
   *
   * @return the removed source, or null if there were no sources to remove.
   */
  public String removeSource() {
    if (sources.isEmpty()) {
      return null;
    }
    String source = sources.iterator().next();
    removeNode(source);
    return source;
  }

  /**
   * Linearize the dag. The returned list guarantees that for each item in the list, that item has no path to an
   * item that comes before it in the list. In the process, if a cycle is found, an exception will be thrown.
   * This is a destructive operation and will result in an empty dag.
   *
   * @return the linearized dag
   * @throws IllegalStateException if there is a cycle in the dag
   */
  public List<String> linearize() {
    List<String> linearized = new ArrayList<>();

    Dag copy = new Dag(sources, sinks, outgoingConnections, incomingConnections);
    String removed;
    while ((removed = copy.removeSource()) != null) {
      linearized.add(removed);
    }
    if (copy.outgoingConnections.isEmpty()) {
      return linearized;
    }
    // if we've run out of sources to remove, but there are still connections, that means there is a cycle.
    // remove all sinks so we can print out where the cycle is.
    do {
      removed = copy.removeSink();
    } while (removed != null);
    Set<String> cycle = accessibleFrom(copy.outgoingConnections.keySet().iterator().next());
    throw new IllegalStateException(
      String.format("Invalid DAG. Stages %s form a cycle.", Joiner.on(',').join(cycle)));
  }

  /**
   * Remove a specific node from the dag. Removing a node will remove all connections into the node and all
   * connection coming out of the node. Removing a node will also re-compute the sources and sinks of the dag.
   *
   * @param node the node to remove
   */
  private void removeNode(String node) {
    // for each node this output to: node -> outputNode
    for (String outputNode : outgoingConnections.removeAll(node)) {
      // remove the connection from this node to its output
      incomingConnections.remove(outputNode, node);
      // if the removal of that connection caused the output to become a source, add it as a source
      if (incomingConnections.get(outputNode).isEmpty()) {
        sources.add(outputNode);
      }
    }
    // for each node that output to this node: inputNode -> node
    for (String inputNode : incomingConnections.removeAll(node)) {
      // remove the connection from the input node to this node
      outgoingConnections.remove(inputNode, node);
      // if the removal of that connection caused the input node to become a sink, add it as a sink
      if (outgoingConnections.get(inputNode).isEmpty()) {
        sinks.add(inputNode);
      }
    }
    // in case this node we removed a source or a sink (or both).
    sinks.remove(node);
    sources.remove(node);
  }

  private Set<String> traverse(String stage, Set<String> alreadySeen, Set<String> stopNodes) {
    if (!alreadySeen.add(stage)) {
      return alreadySeen;
    }
    Collection<String> outputs = outgoingConnections.get(stage);
    if (outputs.isEmpty()) {
      return alreadySeen;
    }
    for (String output : outputs) {
      if (stopNodes.contains(output)) {
        alreadySeen.add(output);
        continue;
      }
      alreadySeen.addAll(traverse(output, alreadySeen, stopNodes));
    }
    return alreadySeen;
  }

  private String removeSink() {
    if (sinks.isEmpty()) {
      return null;
    }
    String sink = sinks.iterator().next();
    removeNode(sink);
    return sink;
  }

  private static Set<String> calculateSources(Multimap<String, String> outgoingConnections,
                                              Multimap<String, String> incomingConnections) {
    Set<String> sources = new HashSet<>();
    // a source is any stage that doesn't have any inputs but has at least one output
    for (String stageWithOutput : outgoingConnections.keySet()) {
      if (incomingConnections.get(stageWithOutput).isEmpty()) {
        sources.add(stageWithOutput);
      }
    }
    return sources;
  }

  private static Set<String> calculateSinks(Multimap<String, String> outgoingConnections,
                                            Multimap<String, String> incomingConnections) {
    Set<String> sinks = new HashSet<>();
    // a sink is any stage that doesn't have any outputs but has at least one input
    for (String stageWithInput : incomingConnections.keySet()) {
      if (outgoingConnections.get(stageWithInput).isEmpty()) {
        sinks.add(stageWithInput);
      }
    }
    return sinks;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    Dag that = (Dag) o;

    return Objects.equals(nodes, that.nodes) &&
      Objects.equals(sources, that.sources) &&
      Objects.equals(sinks, that.sinks) &&
      Objects.equals(outgoingConnections, that.outgoingConnections) &&
      Objects.equals(incomingConnections, that.incomingConnections);
  }

  @Override
  public int hashCode() {
    return Objects.hash(nodes, sources, sinks, outgoingConnections, incomingConnections);
  }

  @Override
  public String toString() {
    return "Dag{" +
      "nodes=" + nodes +
      ", sources=" + sources +
      ", sinks=" + sinks +
      ", outgoingConnections=" + outgoingConnections +
      ", incomingConnections=" + incomingConnections +
      '}';
  }
}