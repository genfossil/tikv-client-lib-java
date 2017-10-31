package com.pingcap.tikv.operation;

import com.pingcap.tidb.tipb.Chunk;
import com.pingcap.tidb.tipb.SelectResponse;
import com.pingcap.tikv.TiSession;
import com.pingcap.tikv.codec.CodecDataInput;
import com.pingcap.tikv.exception.TiClientInternalException;
import com.pingcap.tikv.kvproto.Coprocessor;
import com.pingcap.tikv.kvproto.Metapb;
import com.pingcap.tikv.meta.TiDAGRequest;
import com.pingcap.tikv.region.RegionManager;
import com.pingcap.tikv.region.RegionStoreClient;
import com.pingcap.tikv.region.TiRegion;
import com.pingcap.tikv.row.Row;
import com.pingcap.tikv.row.RowReader;
import com.pingcap.tikv.row.RowReaderFactory;
import com.pingcap.tikv.types.DataType;
import com.pingcap.tikv.types.DataTypeFactory;
import com.pingcap.tikv.types.Types;
import com.pingcap.tikv.util.RangeSplitter;

import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

public class DAGIterator implements Iterator<Row> {
  protected final TiSession session;
  private final List<RangeSplitter.RegionTask> regionTasks;
  private final boolean indexScan;
  private TiDAGRequest dagRequest;
  private static final DataType[] handleTypes =
      new DataType[]{DataTypeFactory.of(Types.TYPE_LONG)};
  private RowReader rowReader;
  private CodecDataInput dataInput;
  private boolean eof = false;
  private int taskIndex;
  private int chunkIndex;
  private List<Chunk> chunkList;
  private SchemaInfer schemaInfer;
  private Iterator<SelectResponse> responseIterator;

  public DAGIterator(TiDAGRequest req,
                     List<RangeSplitter.RegionTask> regionTasks,
                     TiSession session,
                     boolean indexScan) {
    this.dagRequest = req;
    this.session = session;
    this.regionTasks = regionTasks;
    this.indexScan = indexScan;
    this.schemaInfer = SchemaInfer.create(req);
  }

  public DAGIterator(TiDAGRequest req, TiSession session, RegionManager rm,
                     boolean indexScan) {
    this(req, RangeSplitter.newSplitter(rm).splitRangeByRegion(req.getRanges()), session, indexScan);
  }

  @Override
  public boolean hasNext() {
    if (eof) {
      return false;
    }

    while (null == chunkList ||
        chunkIndex >= chunkList.size() ||
        dataInput.available() <= 0
        ) {
      if (advanceNextResponse()) {
        return true;
      }

      if (!readNextRegionChunks()) {
        return false;
      }
    }

    return true;
  }

  @Override
  public Row next() {
    if (hasNext()) {
      if (indexScan) {
        return rowReader.readRow(handleTypes);
      } else {
        return rowReader.readRow(this.schemaInfer.getTypes().toArray(new DataType[0]));
      }
    } else {
      throw new NoSuchElementException();
    }
  }

  private boolean readNextRegionChunks() {
    if (taskIndex >= regionTasks.size()) {
      return false;
    }

    RangeSplitter.RegionTask regionTask = regionTasks.get(taskIndex++);
    responseIterator = processByStreaming(regionTask, this.dagRequest);

    return advanceNextResponse();
//    List<Chunk> chunks = createClientAndSendReq(regionTask, this.dagRequest);
//    if (null == chunks || chunks.isEmpty()) {
//      return false;
//    }
//    chunkList = chunks;
//    chunkIndex = 0;
//    createDataInputReader();
//    return true;
  }

  private void createDataInputReader() {
    Objects.requireNonNull(chunkList, "Chunk list should not be null.");
    if (0 > chunkIndex ||
        chunkIndex >= chunkList.size()) {
      throw new IllegalArgumentException();
    }
    dataInput = new CodecDataInput(chunkList.get(chunkIndex).getRowsData());
    rowReader = RowReaderFactory.createRowReader(dataInput);
  }

  private boolean hasMoreResponse() {
    return null != responseIterator && responseIterator.hasNext();
  }

  private boolean advanceNextResponse() {
    if (!hasMoreResponse()) return false;

    chunkList = responseIterator.next().getChunksList();
    if (null == chunkList || chunkList.isEmpty()) {
      return false;
    }
    chunkIndex = 0;
    createDataInputReader();
    return true;
  }

  private Iterator<SelectResponse> processByStreaming(RangeSplitter.RegionTask regionTask,
                                                      TiDAGRequest req) {
    List<Coprocessor.KeyRange> ranges = regionTask.getRanges();
    TiRegion region = regionTask.getRegion();
    Metapb.Store store = regionTask.getStore();

    RegionStoreClient client;
    try {
      client = RegionStoreClient.create(region, store, session);
      Iterator<SelectResponse> responseIterator = client.coprocessStreaming(req.buildScan(indexScan), ranges);
      if (null == responseIterator) {
        eof = true;
        return null;
      }
      return responseIterator;
    } catch (Exception e) {
      throw new TiClientInternalException("Error Closing Store client.", e);
    }
  }

  private List<Chunk> createClientAndSendReq(RangeSplitter.RegionTask regionTask,
                                             TiDAGRequest req) {
    List<Coprocessor.KeyRange> ranges = regionTask.getRanges();
    TiRegion region = regionTask.getRegion();
    Metapb.Store store = regionTask.getStore();

    RegionStoreClient client;
    try {
      client = RegionStoreClient.create(region, store, session);
      SelectResponse resp = client.coprocess(req.buildScan(indexScan), ranges);
      // if resp is null, then indicates eof.
      if (null == resp) {
        eof = true;
        return null;
      }
      return resp.getChunksList();
    } catch (Exception e) {
      throw new TiClientInternalException("Error Closing Store client.", e);
    }
  }
}
