/*
 * Copyright 2017 PingCAP, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.pingcap.tikv.key;


import static com.pingcap.tikv.codec.Codec.IntegerCodec.writeLong;

import com.pingcap.tikv.codec.Codec.IntegerCodec;
import com.pingcap.tikv.codec.CodecDataInput;
import com.pingcap.tikv.codec.CodecDataOutput;
import com.pingcap.tikv.exception.TiClientInternalException;
import com.pingcap.tikv.exception.TiExpressionException;
import com.pingcap.tikv.util.FastByteComparisons;
import com.pingcap.tikv.key.RowKey.DecodeResult.Status;
import java.util.Objects;

public class RowKey extends Key {
  private static final byte[] REC_PREFIX_SEP = new byte[] {'_', 'r'};

  private final long tableId;
  private final long handle;

  private RowKey(long tableId, long handle) {
    super(encode(tableId, handle));
    this.tableId = tableId;
    this.handle = handle;
  }

  public static RowKey create(long tableId, long handle) {
    return new RowKey(tableId, handle);
  }

  public static RowKey create(long tableId, TypedKey handle) {
    Object obj = handle.getValue();
    if (obj instanceof Long) {
      return new RowKey(tableId, (long)obj);
    }
    throw new TiExpressionException("Cannot encode row key with non-long type");
  }

  public static RowKey createMin(long tableId) {
    return create(tableId, Long.MIN_VALUE);
  }

  public static RowKey createMax(long tableId) {
    return create(tableId, Long.MAX_VALUE);
  }

  private static byte[] encode(long tableId, long handle) {
    CodecDataOutput cdo = new CodecDataOutput();
    encodePrefix(cdo, tableId);
    writeLong(cdo, handle);
    return cdo.toBytes();
  }

  @Override
  public RowKey next() {
    long handle = getHandle();
    if (handle == Long.MAX_VALUE) {
      throw new TiClientInternalException("Handle overflow for Long MAX");
    }
    return new RowKey(tableId, handle + 1);
  }

  public long getTableId() {
    return tableId;
  }

  public long getHandle() {
    return handle;
  }

  @Override
  public String toString() {
    return Long.toString(handle);
  }

  private static void encodePrefix(CodecDataOutput cdo, long tableId) {
    cdo.write(TBL_PREFIX);
    writeLong(cdo, tableId);
    cdo.write(REC_PREFIX_SEP);
  }

  public static class DecodeResult {
    public long handle;
    public enum Status {
      MIN,
      MAX,
      EQUAL,
      LESS,
      GREATER,
      UNKNOWN_INF
    }
    public Status status;
  }

  public static void tryDecodeRowKey(long tableId, byte[] rowKey, DecodeResult outResult) {
    Objects.requireNonNull(rowKey, "rowKey cannot be null");
    if (rowKey.length == 0) {
      outResult.status = Status.UNKNOWN_INF;
      return;
    }
    CodecDataOutput cdo = new CodecDataOutput();
    encodePrefix(cdo, tableId);
    byte [] tablePrefix = cdo.toBytes();

    int res = FastByteComparisons.compareTo(
        tablePrefix, 0, tablePrefix.length,
        rowKey, 0, Math.min(rowKey.length, tablePrefix.length));

    if (res > 0) {
      outResult.status = Status.MIN;
      return;
    }
    if (res < 0) {
      outResult.status = Status.MAX;
      return;
    }

    CodecDataInput cdi = new CodecDataInput(rowKey);
    cdi.skipBytes(tablePrefix.length);
    if (cdi.available() == 8) {
      outResult.status = Status.EQUAL;
    } else if (cdi.available() < 8) {
      outResult.status = Status.LESS;
    } else if (cdi.available() > 8) {
      outResult.status = Status.GREATER;
    }
    outResult.handle = IntegerCodec.readPartialLong(cdi);
  }

}
