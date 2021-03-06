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
package org.scassandra.server.actors

import akka.actor.ActorSystem
import akka.testkit.{TestActorRef, TestProbe, TestKit}
import org.scalatest.{FunSuiteLike, Matchers}
import org.scassandra.server.cqlmessages.VersionTwoMessageFactory

class OptionsHandlerTest extends TestKit(ActorSystem("TestSystem")) with FunSuiteLike with Matchers {

  test("Should send supported message on any Options message") {
    val senderTestProbe = TestProbe()
    val cqlMessageFactory = VersionTwoMessageFactory
    val stream : Byte = 0x24

    val expectedSupportedMessage = cqlMessageFactory.createSupportedMessage(stream)
    val underTest = TestActorRef(new OptionsHandler(senderTestProbe.ref, cqlMessageFactory))

    underTest ! OptionsHandlerMessages.OptionsMessage(stream)

    senderTestProbe.expectMsg(expectedSupportedMessage)
  }

}
