/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.flink.table.runtime.aggregate

import java.util.{List => JList, ArrayList => JArrayList}

import org.apache.flink.api.common.state._
import org.apache.flink.api.common.typeinfo.{BasicTypeInfo, TypeInformation}
import org.apache.flink.api.java.typeutils.{ListTypeInfo, RowTypeInfo}
import org.apache.flink.configuration.Configuration
import org.apache.flink.streaming.api.functions.ProcessFunction
import org.apache.flink.table.functions.{Accumulator, AggregateFunction}
import org.apache.flink.types.Row
import org.apache.flink.util.{Collector, Preconditions}

/**
 * Process Function for RANGE clause event-time bounded OVER window
 *
 * @param aggregates           the list of all [[AggregateFunction]] used for this aggregation
 * @param aggFields            the position (in the input Row) of the input value for each aggregate
 * @param forwardedFieldCount  the count of forwarded fields.
 * @param aggregationStateType the row type info of aggregation
 * @param inputRowType         the row type info of input row
 * @param precedingOffset      the preceding offset
 */
class RangeClauseBoundedOverProcessFunction(
    private val aggregates: Array[AggregateFunction[_]],
    private val aggFields: Array[Array[Int]],
    private val forwardedFieldCount: Int,
    private val aggregationStateType: RowTypeInfo,
    private val inputRowType: RowTypeInfo,
    private val precedingOffset: Long)
  extends ProcessFunction[Row, Row] {

  Preconditions.checkNotNull(aggregates)
  Preconditions.checkNotNull(aggFields)
  Preconditions.checkArgument(aggregates.length == aggFields.length)
  Preconditions.checkNotNull(forwardedFieldCount)
  Preconditions.checkNotNull(aggregationStateType)
  Preconditions.checkNotNull(precedingOffset)

  private var output: Row = _

  // the state which keeps the last triggering timestamp
  private var lastTriggeringTsState: ValueState[Long] = _

  // the state which used to materialize the accumulator for incremental calculation
  private var accumulatorState: ValueState[Row] = _

  // the state which keeps all the data that are not expired.
  // The first element (as the mapState key) of the tuple is the time stamp. Per each time stamp,
  // the second element of tuple is a list that contains the entire data of all the rows belonging
  // to this time stamp.
  private var dataState: MapState[Long, JList[Row]] = _

  override def open(config: Configuration) {

    output = new Row(forwardedFieldCount + aggregates.length)

    val lastTriggeringTsDescriptor: ValueStateDescriptor[Long] =
      new ValueStateDescriptor[Long]("lastTriggeringTsState", classOf[Long])
    lastTriggeringTsState = getRuntimeContext.getState(lastTriggeringTsDescriptor)

    val accumulatorStateDescriptor =
      new ValueStateDescriptor[Row]("accumulatorState", aggregationStateType)
    accumulatorState = getRuntimeContext.getState(accumulatorStateDescriptor)

    val keyTypeInformation: TypeInformation[Long] =
      BasicTypeInfo.LONG_TYPE_INFO.asInstanceOf[TypeInformation[Long]]
    val valueTypeInformation: TypeInformation[JList[Row]] = new ListTypeInfo[Row](inputRowType)

    val mapStateDescriptor: MapStateDescriptor[Long, JList[Row]] =
      new MapStateDescriptor[Long, JList[Row]](
        "dataState",
        keyTypeInformation,
        valueTypeInformation)

    dataState = getRuntimeContext.getMapState(mapStateDescriptor)

  }

  override def processElement(
    input: Row,
    ctx: ProcessFunction[Row, Row]#Context,
    out: Collector[Row]): Unit = {

    // triggering timestamp for trigger calculation
    val triggeringTs = ctx.timestamp

    val lastTriggeringTs = lastTriggeringTsState.value

    // check if the data is expired, if not, save the data and register event time timer
    if (triggeringTs > lastTriggeringTs) {
      val data = dataState.get(triggeringTs)
      if (null != data) {
        data.add(input)
        dataState.put(triggeringTs, data)
      } else {
        val data = new JArrayList[Row]
        data.add(input)
        dataState.put(triggeringTs, data)
        // register event time timer
        ctx.timerService.registerEventTimeTimer(triggeringTs)
      }
    }
  }

  override def onTimer(
    timestamp: Long,
    ctx: ProcessFunction[Row, Row]#OnTimerContext,
    out: Collector[Row]): Unit = {
    // gets all window data from state for the calculation
    val inputs: JList[Row] = dataState.get(timestamp)

    if (null != inputs) {

      var accumulators = accumulatorState.value
      var dataListIndex = 0
      var aggregatesIndex = 0

      // initialize when first run or failover recovery per key
      if (null == accumulators) {
        accumulators = new Row(aggregates.length)
        aggregatesIndex = 0
        while (aggregatesIndex < aggregates.length) {
          accumulators.setField(aggregatesIndex, aggregates(aggregatesIndex).createAccumulator())
          aggregatesIndex += 1
        }
      }

      // keep up timestamps of retract data
      val retractTsList: JList[Long] = new JArrayList[Long]

      // do retraction
      val dataTimestampIt = dataState.keys.iterator
      while (dataTimestampIt.hasNext) {
        val dataTs: Long = dataTimestampIt.next()
        val offset = timestamp - dataTs
        if (offset > precedingOffset) {
          val retractDataList = dataState.get(dataTs)
          dataListIndex = 0
          while (dataListIndex < retractDataList.size()) {
            aggregatesIndex = 0
            while (aggregatesIndex < aggregates.length) {
              val accumulator = accumulators.getField(aggregatesIndex).asInstanceOf[Accumulator]
              aggregates(aggregatesIndex)
                .retract(accumulator, retractDataList.get(dataListIndex)
                .getField(aggFields(aggregatesIndex)(0)))
              aggregatesIndex += 1
            }
            dataListIndex += 1
          }
          retractTsList.add(dataTs)
        }
      }

      // do accumulation
      dataListIndex = 0
      while (dataListIndex < inputs.size()) {
        // accumulate current row
        aggregatesIndex = 0
        while (aggregatesIndex < aggregates.length) {
          val accumulator = accumulators.getField(aggregatesIndex).asInstanceOf[Accumulator]
          aggregates(aggregatesIndex).accumulate(accumulator, inputs.get(dataListIndex)
            .getField(aggFields(aggregatesIndex)(0)))
          aggregatesIndex += 1
        }
        dataListIndex += 1
      }

      // set aggregate in output row
      aggregatesIndex = 0
      while (aggregatesIndex < aggregates.length) {
        val index = forwardedFieldCount + aggregatesIndex
        val accumulator = accumulators.getField(aggregatesIndex).asInstanceOf[Accumulator]
        output.setField(index, aggregates(aggregatesIndex).getValue(accumulator))
        aggregatesIndex += 1
      }

      // copy forwarded fields to output row and emit output row
      dataListIndex = 0
      while (dataListIndex < inputs.size()) {
        aggregatesIndex = 0
        while (aggregatesIndex < forwardedFieldCount) {
          output.setField(aggregatesIndex, inputs.get(dataListIndex).getField(aggregatesIndex))
          aggregatesIndex += 1
        }
        out.collect(output)
        dataListIndex += 1
      }

      // remove the data that has been retracted
      dataListIndex = 0
      while (dataListIndex < retractTsList.size) {
        dataState.remove(retractTsList.get(dataListIndex))
        dataListIndex += 1
      }

      // update state
      accumulatorState.update(accumulators)
      lastTriggeringTsState.update(timestamp)
    }
  }
}


