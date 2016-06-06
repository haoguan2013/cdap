/*
 * Copyright © 2015-2016 Cask Data, Inc.
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

package co.cask.cdap.internal.app.runtime.batch;

import co.cask.cdap.api.ProgramStatus;
import co.cask.cdap.api.app.AbstractApplication;
import co.cask.cdap.api.common.Bytes;
import co.cask.cdap.api.data.batch.Input;
import co.cask.cdap.api.data.batch.Output;
import co.cask.cdap.api.dataset.lib.FileSetArguments;
import co.cask.cdap.api.dataset.lib.FileSetProperties;
import co.cask.cdap.api.dataset.lib.TimePartitionedFileSet;
import co.cask.cdap.api.dataset.lib.TimePartitionedFileSetArguments;
import co.cask.cdap.api.dataset.table.Put;
import co.cask.cdap.api.dataset.table.Row;
import co.cask.cdap.api.mapreduce.AbstractMapReduce;
import co.cask.cdap.api.mapreduce.MapReduceContext;
import com.google.common.base.Preconditions;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.lib.input.TextInputFormat;
import org.apache.hadoop.mapreduce.lib.output.TextOutputFormat;

import java.io.IOException;

/**
 * App used to test whether M/R works well with time-partitioned file sets.
 * It uses M/R to read from a table and write partitions, and another M/R to read partitions and write to a table.
 */
public class AppWithTimePartitionedFileSet extends AbstractApplication {

  public static final String INPUT = "input";
  public static final String TIME_PARTITIONED = "time-part-d";
  public static final String OUTPUT = "output";
  public static final byte[] ONLY_COLUMN = { 'x' };
  public static final String ROW_TO_WRITE = "row.to.write";
  public static final String COMPAT_ADD_PARTITION = "compat.add.partition";
  private static final String SEPARATOR = ":";

  @Override
  public void configure() {
    setName("AppWithMapReduceUsingFile");
    setDescription("Application with MapReduce job using file as dataset");
    createDataset(INPUT, "table");
    createDataset(OUTPUT, "table");

    createDataset(TIME_PARTITIONED, "timePartitionedFileSet", FileSetProperties.builder()
      // properties for file set
      .setBasePath("partitioned")
      .setInputFormat(TextInputFormat.class)
      .setOutputFormat(TextOutputFormat.class)
      .setOutputProperty(TextOutputFormat.SEPERATOR, SEPARATOR)
      // don't configure properties for the Hive table - this is used in a context where explore is disabled
      .build());
    addMapReduce(new PartitionWriter());
    addMapReduce(new PartitionReader());
  }

  /**
   * Map/Reduce that reads the "input" table and writes to a partition.
   */
  public static final class PartitionWriter extends AbstractMapReduce {

    @Override
    public void initialize() throws Exception {
      MapReduceContext context = getContext();
      Job job = context.getHadoopJob();
      job.setMapperClass(SimpleMapper.class);
      job.setNumReduceTasks(0);
      context.addInput(Input.ofDataset(INPUT));
      context.addOutput(Output.ofDataset(TIME_PARTITIONED));
    }

    @Override
    public void destroy() {
      // here we also test backward compatibility for existing apps that add the partition in the onFinish
      // (this was necessary up to 2.8.0 and fixed in CDAP-1227).
      if (getContext().getState().getStatus() == ProgramStatus.COMPLETED
        && getContext().getRuntimeArguments().get(COMPAT_ADD_PARTITION) != null) {
        TimePartitionedFileSet ds = getContext().getDataset(TIME_PARTITIONED);
        String outputPath = FileSetArguments.getOutputPath(ds.getEmbeddedFileSet().getRuntimeArguments());
        Long time = TimePartitionedFileSetArguments.getOutputPartitionTime(ds.getRuntimeArguments());
        Preconditions.checkNotNull(time, "Output partition time is null.");
        ds.addPartition(time, outputPath);
      }
    }
  }

  public static class SimpleMapper extends Mapper<byte[], Row, Text, Text> {

    @Override
    public void map(byte[] rowKey, Row row, Context context)
      throws IOException, InterruptedException {
      context.write(new Text(Bytes.toString(rowKey)),
                    new Text(Bytes.toString(row.get(ONLY_COLUMN))));
    }
  }

  /**
   * Map/Reduce that reads the "input" table and writes to a partition.
   */
  public static final class PartitionReader extends AbstractMapReduce {

    @Override
    public void initialize() throws Exception {
      MapReduceContext context = getContext();
      Job job = context.getHadoopJob();
      job.setMapperClass(ReaderMapper.class);
      job.setNumReduceTasks(0);
      String row = context.getRuntimeArguments().get(ROW_TO_WRITE);
      job.getConfiguration().set(ROW_TO_WRITE, row);
      context.addInput(Input.ofDataset(TIME_PARTITIONED));
      context.addOutput(Output.ofDataset(OUTPUT));
    }
  }

  public static class ReaderMapper extends Mapper<LongWritable, Text, byte[], Put> {

    private static byte[] rowToWrite;

    @Override
    protected void setup(Context context) throws IOException, InterruptedException {
      rowToWrite = Bytes.toBytes(context.getConfiguration().get(ROW_TO_WRITE));
    }

    @Override
    public void map(LongWritable pos, Text text, Context context)
      throws IOException, InterruptedException {
      String line = text.toString();
      String[] fields = line.split(SEPARATOR);
      context.write(rowToWrite, new Put(rowToWrite, Bytes.toBytes(fields[0]), Bytes.toBytes(fields[1])));
    }
  }
}
