/*
 * Copyright (C) 2014 Christopher Batey and Dogan Narinc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.scassandra.server.priming.routes

import java.util.concurrent.TimeUnit

import com.typesafe.scalalogging.LazyLogging
import org.scassandra.server.cqlmessages.Consistency
import org.scassandra.server.cqlmessages.CqlProtocolHelper.hex2Bytes
import org.scassandra.server.cqlmessages.types.ColumnType
import org.scassandra.server.priming.json._
import org.scassandra.server.priming.prepared.{AnyMatch, ExactMatch, VariableMatch}
import org.scassandra.server.priming.query.{Prime, PrimeCriteria, PrimeQuerySingle, Then, When}
import org.scassandra.server.priming._

import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Try}

object PrimingJsonHelper extends LazyLogging {
  def extractPrimeCriteria(primeQueryRequest: PrimeQuerySingle): Try[PrimeCriteria] = {
    val primeConsistencies = primeQueryRequest.when.consistency.getOrElse(Consistency.all)

    primeQueryRequest.when match {
      // Prime for a specific query
      case When(Some(query), None, _, _, _) =>
        util.Success(PrimeCriteria(query, primeConsistencies, patternMatch = false))
      // Prime for a query pattern
      case When(None, Some(queryPattern), _, _, _) => util.Success(PrimeCriteria(queryPattern, primeConsistencies, patternMatch = true))
      case _ => Failure(new IllegalArgumentException("Can't specify query and queryPattern"))
    }
  }

  def extractPrime(primeRequest: PrimeQuerySingle): Prime = {
    // add the deserialized JSON request to the map of prime requests
    val thenDo = primeRequest.thenDo
    val config = thenDo.config.getOrElse(Map())
    val resultsAsList = thenDo.rows.getOrElse(List())
    val result = thenDo.result.getOrElse(Success)
    val fixedDelay = primeRequest.thenDo.fixedDelay.map(FiniteDuration(_, TimeUnit.MILLISECONDS))
    val primeResult: PrimeResult = convertToPrimeResult(config, result)
    val variableTypes: List[ColumnType[_]] = thenDo.variable_types.getOrElse(List())

    logger.trace("Column types " + primeRequest.thenDo.column_types)
    val columnTypes: Map[String, ColumnType[_]] = Defaulter.defaultColumnTypesToVarchar(primeRequest.thenDo.column_types, resultsAsList)
    logger.trace("Incoming when {}", primeRequest.when)

    val keyspace = primeRequest.when.keyspace.getOrElse("")
    val table = primeRequest.when.table.getOrElse("")

    Prime(resultsAsList, primeResult, columnTypes, keyspace, table, fixedDelay, variableTypes)
  }

  def convertToPrimeResult(config: Map[String, String], result: ResultJsonRepresentation): PrimeResult = {
    val primeResult: PrimeResult = result match {
      case Success => SuccessResult
      case ServerError => ServerErrorResult(
        config.getOrElse(ErrorConstants.Message, "Server Error")
      )
      case ProtocolError => ProtocolErrorResult(
        config.getOrElse(ErrorConstants.Message, "Protocol Error")
      )
      case BadCredentials => BadCredentialsResult(
        config.getOrElse(ErrorConstants.Message, "Bad Credentials")
      )
      case Overloaded => OverloadedResult(
        config.getOrElse(ErrorConstants.Message, "Overloaded")
      )
      case IsBootstrapping => IsBootstrappingResult(
        config.getOrElse(ErrorConstants.Message, "Bootstrapping")
      )
      case TruncateError => TruncateErrorResult(
        config.getOrElse(ErrorConstants.Message, "Truncate Error")
      )
      case SyntaxError => SyntaxErrorResult(
        config.getOrElse(ErrorConstants.Message, "Syntax Error")
      )
      case Unauthorized => UnauthorizedResult(
        config.getOrElse(ErrorConstants.Message, "Unauthorized")
      )
      case Invalid => InvalidResult(
        config.getOrElse(ErrorConstants.Message, "Invalid")
      )
      case ConfigError => ConfigErrorResult(
        config.getOrElse(ErrorConstants.Message, "Config Error")
      )
      case AlreadyExists => AlreadyExistsResult(
        config.getOrElse(ErrorConstants.Message, "Already Exists"),
        config.getOrElse(ErrorConstants.Keyspace, "keyspace"),
        config.getOrElse(ErrorConstants.Table, "")
      )
      case Unprepared => UnpreparedResult(
        config.getOrElse(ErrorConstants.Message, "Unprepared"),
        hex2Bytes(config.getOrElse(ErrorConstants.PrepareId, "0x"))
      )
      case ReadTimeout => ReadRequestTimeoutResult(
        config.getOrElse(ErrorConstants.ReceivedResponse, "0").toInt,
        config.getOrElse(ErrorConstants.RequiredResponse, "1").toInt,
        config.getOrElse(ErrorConstants.DataPresent, "false").toBoolean,
        config.get(ErrorConstants.ConsistencyLevel).map(Consistency.fromString)
      )
      case WriteTimeout => WriteRequestTimeoutResult(
        config.getOrElse(ErrorConstants.ReceivedResponse, "0").toInt,
        config.getOrElse(ErrorConstants.RequiredResponse, "1").toInt,
        WriteType.withName(config.getOrElse(ErrorConstants.WriteType, "SIMPLE")),
        config.get(ErrorConstants.ConsistencyLevel).map(Consistency.fromString)
      )
      case Unavailable => UnavailableResult(
        config.getOrElse(ErrorConstants.RequiredResponse, "1").toInt,
        config.getOrElse(ErrorConstants.Alive, "0").toInt,
        config.get(ErrorConstants.ConsistencyLevel).map(Consistency.fromString)
      )
      case ClosedConnection => ClosedConnectionResult(
        config.getOrElse(ErrorConstants.CloseType, "close")
      )
    }
    primeResult
  }

  def convertToResultJsonRepresentation(primeResult: PrimeResult): ResultJsonRepresentation = {
    primeResult match {
      case SuccessResult => Success
      case _: ReadRequestTimeoutResult => ReadTimeout
      case _: WriteRequestTimeoutResult => WriteTimeout
      case _: UnavailableResult => Unavailable
      case _: ServerErrorResult => ServerError
      case _: ProtocolErrorResult => ProtocolError
      case _: BadCredentialsResult => BadCredentials
      case _: OverloadedResult => Overloaded
      case _: IsBootstrappingResult => IsBootstrapping
      case _: TruncateErrorResult => TruncateError
      case _: SyntaxErrorResult => SyntaxError
      case _: UnauthorizedResult => Unauthorized
      case _: InvalidResult => Invalid
      case _: ConfigErrorResult => ConfigError
      case _: AlreadyExistsResult => AlreadyExists
      case _: UnpreparedResult => Unprepared
      case _: ClosedConnectionResult => ClosedConnection
    }
  }

  def convertBackToPrimeQueryResult(allPrimes: Map[PrimeCriteria, Prime]) = {
    allPrimes.map({ case (primeCriteria, prime) =>
      val when = When(Some(primeCriteria.query), keyspace = Some(prime.keyspace), table = Some(prime.table), consistency = Some(primeCriteria.consistency))

      val fixedDelay = if (prime.fixedDelay.isDefined) Some(prime.fixedDelay.get.toMillis) else None

      val result = convertToResultJsonRepresentation(prime.result)

      val thenDo = Then(Some(prime.rows), result = Some(result), column_types = Some(prime.columnTypes), fixedDelay = fixedDelay)

      PrimeQuerySingle(when, thenDo)
    })
  }

  /**
   * Converts the types produced by the JSON parser based on the actual CQL type information.
    *
    * The goal is for a given CQL type it is only represented by a single type inside scassandra.
    *
    * E.g UUIDs are java.util.UUIDs not a String throughout.
    *
    * This means in the transport layer we want to conver them before passing anything to the prime stores.
   */
  def convertTypesBasedOnCqlTypes(variableTypes: List[ColumnType[_]], outcomes: List[VariableMatch]): List[VariableMatch] = {
    variableTypes.zip(outcomes) map {
      case (t, ExactMatch(exact)) => ExactMatch(exact.flatMap(t.convertJsonToInternal))
      case (t, AnyMatch) => AnyMatch
    }
  }

  case class ValidationError(reason: String)
}
