/****************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one   *
 * or more contributor license agreements.  See the NOTICE file *
 * distributed with this work for additional information        *
 * regarding copyright ownership.  The ASF licenses this file   *
 * to you under the Apache License, Version 2.0 (the            *
 * "License"); you may not use this file except in compliance   *
 * with the License.  You may obtain a copy of the License at   *
 *                                                              *
 *   http://www.apache.org/licenses/LICENSE-2.0                 *
 *                                                              *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY       *
 * KIND, either express or implied.  See the License for the    *
 * specific language governing permissions and limitations      *
 * under the License.                                           *
 ****************************************************************/
package org.apache.james.jmap.method

import eu.timepit.refined.auto._
import javax.inject.Inject
import org.apache.james.jmap.json.{EmailQuerySerializer, ResponseSerializer}
import org.apache.james.jmap.mail.{Comparator, EmailQueryRequest, EmailQueryResponse}
import org.apache.james.jmap.model.CapabilityIdentifier.CapabilityIdentifier
import org.apache.james.jmap.model.DefaultCapabilities.{CORE_CAPABILITY, MAIL_CAPABILITY}
import org.apache.james.jmap.model.Invocation.{Arguments, MethodName}
import org.apache.james.jmap.model.{CanCalculateChanges, Capabilities, ErrorCode, Invocation, Limit, Position, QueryState}
import org.apache.james.jmap.model.Limit.Limit
import org.apache.james.jmap.model.Position.Position
import org.apache.james.jmap.routes.ProcessingContext
import org.apache.james.jmap.utils.search.MailboxFilter
import org.apache.james.jmap.utils.search.MailboxFilter.QueryFilter
import org.apache.james.mailbox.exception.MailboxNotFoundException
import org.apache.james.mailbox.model.{MultimailboxesSearchQuery, SearchQuery}
import org.apache.james.mailbox.{MailboxManager, MailboxSession}
import org.apache.james.metrics.api.MetricFactory
import org.reactivestreams.Publisher
import play.api.libs.json.{JsError, JsSuccess}
import reactor.core.scala.publisher.{SFlux, SMono}

import scala.jdk.CollectionConverters._

class EmailQueryMethod @Inject() (serializer: EmailQuerySerializer,
                                  mailboxManager: MailboxManager,
                                  metricFactory: MetricFactory) extends Method {
  override val methodName = MethodName("Email/query")
  override val requiredCapabilities: Capabilities = Capabilities(CORE_CAPABILITY, MAIL_CAPABILITY)

  override def process(capabilities: Set[CapabilityIdentifier], invocation: Invocation, mailboxSession: MailboxSession, processingContext: ProcessingContext): Publisher[(Invocation, ProcessingContext)] =
    metricFactory.decoratePublisherWithTimerMetricLogP99(JMAP_RFC8621_PREFIX + methodName.value,
      asEmailQueryRequest(invocation.arguments)
        .flatMap(processRequest(mailboxSession, invocation, _))
        .onErrorResume {
          case e: IllegalArgumentException => SMono.just(Invocation.error(ErrorCode.InvalidArguments, e.getMessage, invocation.methodCallId))
          case e: MailboxNotFoundException => SMono.just(Invocation.error(ErrorCode.InvalidArguments, e.getMessage, invocation.methodCallId))
          case e: Throwable => SMono.raiseError(e)
        }
        .map(invocationResult => (invocationResult, processingContext)))

  private def processRequest(mailboxSession: MailboxSession, invocation: Invocation, request: EmailQueryRequest): SMono[Invocation] = {
    val searchQuery: MultimailboxesSearchQuery = searchQueryFromRequest(request)
    for {
      positionToUse <- Position.validateRequestPosition(request.position)
      limitToUse <- Limit.validateRequestLimit(request.limit)
      response <- executeQuery(mailboxSession, request, searchQuery, positionToUse, limitToUse)
    } yield Invocation(methodName = methodName, arguments = Arguments(serializer.serialize(response)), methodCallId = invocation.methodCallId)
  }

  private def executeQuery(mailboxSession: MailboxSession, request: EmailQueryRequest, searchQuery: MultimailboxesSearchQuery, position: Position, limitToUse: Limit): SMono[EmailQueryResponse] = {
    SFlux.fromPublisher(mailboxManager.search(searchQuery, mailboxSession, limitToUse))
      .drop(position.value)
      .collectSeq()
      .map(ids => EmailQueryResponse(accountId = request.accountId,
        queryState = QueryState.forIds(ids),
        canCalculateChanges = CanCalculateChanges.CANNOT,
        ids = ids,
        position = position,
        limit = Some(limitToUse).filterNot(used => request.limit.map(_.value).contains(used.value))))
  }

  private def searchQueryFromRequest(request: EmailQueryRequest): MultimailboxesSearchQuery = {
    val comparators: List[Comparator] = request.comparator.getOrElse(Set(Comparator.default)).toList
    val sortedSearchQuery: SearchQuery = QueryFilter.buildQuery(request)
      .sorts(comparators.map(_.toSort).asJava)
      .build()

    MailboxFilter.buildQuery(request, sortedSearchQuery)
  }

  private def asEmailQueryRequest(arguments: Arguments): SMono[EmailQueryRequest] =
    serializer.deserializeEmailQueryRequest(arguments.value) match {
      case JsSuccess(emailQueryRequest, _) => SMono.just(emailQueryRequest)
      case errors: JsError => SMono.raiseError(new IllegalArgumentException(ResponseSerializer.serialize(errors).toString))
    }
}