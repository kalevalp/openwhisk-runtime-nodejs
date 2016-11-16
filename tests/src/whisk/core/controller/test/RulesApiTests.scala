/*
 * Copyright 2015-2016 IBM Corporation
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

package whisk.core.controller.test

import scala.language.postfixOps

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport.sprayJsonMarshaller
import spray.httpx.SprayJsonSupport.sprayJsonUnmarshaller
import spray.json.DefaultJsonProtocol.listFormat
import spray.json.DefaultJsonProtocol.RootJsObjectFormat
import spray.json.DefaultJsonProtocol.listFormat
import spray.json.JsObject
import spray.json.pimpString
import whisk.core.controller.WhiskRulesApi
import whisk.core.entity.EntityName
import whisk.core.entity.Exec
import whisk.core.entity.EntityPath
import whisk.core.entity.Parameters
import whisk.core.entity.SemVer
import whisk.core.entity.Status
import whisk.core.entity.AuthKey
import whisk.core.entity.WhiskAuth
import whisk.core.entity.Subject
import whisk.core.entity.WhiskAction
import whisk.core.entity.WhiskEntity
import whisk.core.entity.WhiskRule
import whisk.core.entity.WhiskRulePut
import whisk.core.entity.WhiskTrigger
import whisk.http.ErrorResponse
import scala.language.postfixOps
import whisk.core.entity.WhiskRuleResponse
import whisk.core.entity.ReducedRule
import whisk.core.entity.test.OldWhiskRule
import whisk.core.entity.test.OldWhiskTrigger
import whisk.core.entity.WhiskPackage
import whisk.http.ErrorResponse
import whisk.http.Messages

/**
 * Tests Rules API.
 *
 * Unit tests of the controller service as a standalone component.
 * These tests exercise a fresh instance of the service object in memory -- these
 * tests do NOT communication with a whisk deployment.
 *
 *
 * @Idioglossia
 * "using Specification DSL to write unit tests, as in should, must, not, be"
 * "using Specs2RouteTest DSL to chain HTTP requests for unit testing, as in ~>"
 */
@RunWith(classOf[JUnitRunner])
class RulesApiTests extends ControllerTestCommon with WhiskRulesApi {

    /** Rules API tests */
    behavior of "Rules API"

    val creds = WhiskAuth(Subject(), AuthKey()).toIdentity
    val namespace = EntityPath(creds.subject())
    def aname() = MakeName.next("rules_tests")
    val collectionPath = s"/${EntityPath.DEFAULT}/${collection.path}"
    val activeStatus = s"""{"status":"${Status.ACTIVE}"}""".parseJson.asJsObject
    val inactiveStatus = s"""{"status":"${Status.INACTIVE}"}""".parseJson.asJsObject
    val entityTooBigRejectionMessage = "request entity too large"
    val parametersLimit = Parameters.sizeLimit

    //// GET /rules
    it should "list rules by default/explicit namespace" in {
        implicit val tid = transid()
        val rules = (1 to 2).map { i =>
            WhiskRule(namespace, aname(), EntityName("bogus trigger"), EntityName("bogus action"))
        }.toList
        rules foreach { put(entityStore, _) }
        waitOnView(entityStore, WhiskRule, namespace, 2)
        Get(s"$collectionPath") ~> sealRoute(routes(creds)) ~> check {
            status should be(OK)
            val response = responseAs[List[JsObject]]
            rules.length should be(response.length)
            rules forall { r => response contains r.summaryAsJson } should be(true)
        }

        // it should "list trirulesggers with explicit namespace owned by subject" in {
        Get(s"/$namespace/${collection.path}") ~> sealRoute(routes(creds)) ~> check {
            status should be(OK)
            val response = responseAs[List[JsObject]]
            rules.length should be(response.length)
            rules forall { r => response contains r.summaryAsJson } should be(true)
        }

        // it should "reject list rules with explicit namespace not owned by subject" in {
        val auser = WhiskAuth(Subject(), AuthKey()).toIdentity
        Get(s"/$namespace/${collection.path}") ~> sealRoute(routes(auser)) ~> check {
            status should be(Forbidden)
        }
    }

    //?docs disabled
    ignore should "list rules by default namespace with full docs" in {
        implicit val tid = transid()
        val rules = (1 to 2).map { i =>
            WhiskRule(namespace, aname(), EntityName("bogus trigger"), EntityName("bogus action"))
        }.toList
        rules foreach { put(entityStore, _) }
        waitOnView(entityStore, WhiskRule, namespace, 2)
        Get(s"$collectionPath?docs=true") ~> sealRoute(routes(creds)) ~> check {
            status should be(OK)
            val response = responseAs[List[WhiskRule]]
            rules.length should be(response.length)
            rules forall { r => response contains r } should be(true)
        }
    }

    //// GET /rule/name
    it should "get rule by name in default/explicit namespace" in {
        implicit val tid = transid()
        val rule = WhiskRule(namespace, aname(), EntityName("bogus trigger"), EntityName("bogus action"))
        put(entityStore, rule)
        Get(s"$collectionPath/${rule.name}") ~> sealRoute(routes(creds)) ~> check {
            status should be(OK)
            val response = responseAs[WhiskRuleResponse]
            response should be(rule.withStatus(Status.INACTIVE))
        }

        // it should "get trigger by name in explicit namespace owned by subject" in
        Get(s"/$namespace/${collection.path}/${rule.name}") ~> sealRoute(routes(creds)) ~> check {
            status should be(OK)
            val response = responseAs[WhiskRuleResponse]
            response should be(rule.withStatus(Status.INACTIVE))
        }

        // it should "reject get trigger by name in explicit namespace not owned by subject" in
        val auser = WhiskAuth(Subject(), AuthKey()).toIdentity
        Get(s"/$namespace/${collection.path}/${rule.name}") ~> sealRoute(routes(auser)) ~> check {
            status should be(Forbidden)
        }
    }

    it should "reject get of non existent rule" in {
        implicit val tid = transid()
        Get(s"$collectionPath/xxx") ~> sealRoute(routes(creds)) ~> check {
            status should be(NotFound)
        }
    }

    it should "get rule with active state in trigger" in {
        implicit val tid = transid()
        val ruleName = EntityName("get_active_rule")
        val triggerName = EntityName("get_active_rule trigger")
        val rule = WhiskRule(namespace, ruleName, triggerName, EntityName("an action"))
        val trigger = WhiskTrigger(namespace, triggerName, rules = Some(Map(EntityPath(WhiskEntity.qualifiedName(namespace, ruleName)) -> ReducedRule(namespace, Status.ACTIVE))))

        put(entityStore, trigger)
        put(entityStore, rule)

        Get(s"$collectionPath/${rule.name}") ~> sealRoute(routes(creds)) ~> check {
            status should be(OK)
            val response = responseAs[WhiskRuleResponse]
            response should be(rule.withStatus(Status.ACTIVE))
        }
    }

    it should "get rule with no rule-entries in trigger" in {
        implicit val tid = transid()
        val ruleName = EntityName("get_rule_with_empty_trigger")
        val triggerName = EntityName("get_rule_with_empty_trigger trigger")
        val rule = WhiskRule(namespace, ruleName, triggerName, EntityName("an action"))
        val trigger = WhiskTrigger(namespace, triggerName)

        put(entityStore, trigger)
        put(entityStore, rule)

        Get(s"$collectionPath/${rule.name}") ~> sealRoute(routes(creds)) ~> check {
            status should be(OK)
            val response = responseAs[WhiskRuleResponse]
            response should be(rule.withStatus(Status.INACTIVE))
        }
    }

    it should "report Conflict if the name was of a different type" in {
        implicit val tid = transid()
        val trigger = WhiskTrigger(namespace, aname())
        put(entityStore, trigger)
        Get(s"/$namespace/${collection.path}/${trigger.name}") ~> sealRoute(routes(creds)) ~> check {
            status should be(Conflict)
        }
    }

    // DEL /rules/name
    it should "reject delete rule in state active" in {
        implicit val tid = transid()
        val ruleName = EntityName("reject_delete_rule_active")
        val triggerName = aname()
        val trigger = WhiskTrigger(namespace, triggerName, rules = Some(Map(EntityPath(WhiskEntity.qualifiedName(namespace, ruleName)) -> ReducedRule(namespace, Status.ACTIVE))))
        val rule = WhiskRule(namespace, ruleName, triggerName, EntityName("an action"))
        put(entityStore, trigger)
        put(entityStore, rule)
        Delete(s"$collectionPath/${rule.name}") ~> sealRoute(routes(creds)) ~> check {
            status should be(Conflict)
            val response = responseAs[ErrorResponse]
            response.error should be(s"rule status is '${Status.ACTIVE}', must be '${Status.INACTIVE}' to delete")
            response.code() should be >= 1L
        }
    }

    it should "delete rule in state inactive" in {
        implicit val tid = transid()
        val ruleName = aname()
        val ruleNameQualified = EntityPath(WhiskEntity.qualifiedName(namespace, ruleName))
        val triggerName = aname()
        val actionName = aname()
        val actionNameQualified = EntityPath(WhiskEntity.qualifiedName(namespace, actionName))
        val triggerLink = ReducedRule(actionNameQualified, Status.INACTIVE)
        val trigger = WhiskTrigger(namespace, triggerName, rules = Some(Map(ruleNameQualified -> triggerLink)))
        val rule = WhiskRule(namespace, ruleName, triggerName, actionName)
        put(entityStore, trigger, false)
        put(entityStore, rule)
        Delete(s"$collectionPath/${rule.name}") ~> sealRoute(routes(creds)) ~> check {
            val t = get(entityStore, trigger.docid, WhiskTrigger)
            deleteTrigger(t.docid)

            status should be(OK)
            t.rules.get.get(ruleNameQualified) shouldBe None
            val response = responseAs[WhiskRuleResponse]
            response should be(rule.withStatus(Status.INACTIVE))
        }
    }

    it should "delete rule in state inactive even if the trigger has been deleted" in {
        implicit val tid = transid()
        val rule = WhiskRule(namespace, EntityName("delete_rule_inactive"), EntityName("a trigger"), EntityName("an action"))
        put(entityStore, rule)
        Delete(s"$collectionPath/${rule.name}") ~> sealRoute(routes(creds)) ~> check {
            status should be(OK)
            val response = responseAs[WhiskRuleResponse]
            response should be(rule.withStatus(Status.INACTIVE))
        }
    }

    it should "delete rule in state inactive even if the trigger has no reference to the rule" in {
        implicit val tid = transid()
        val ruleName = aname()
        val ruleNameQualified = EntityPath(WhiskEntity.qualifiedName(namespace, ruleName))
        val triggerName = aname()
        val trigger = WhiskTrigger(namespace, triggerName)
        val rule = WhiskRule(namespace, ruleName, triggerName, EntityName("an action"))
        put(entityStore, trigger, false)
        put(entityStore, rule)
        Delete(s"$collectionPath/${rule.name}") ~> sealRoute(routes(creds)) ~> check {
            deleteTrigger(trigger.docid)

            status should be(OK)
            val response = responseAs[WhiskRuleResponse]
            response should be(rule.withStatus(Status.INACTIVE))
        }
    }

    //// PUT /rules/name
    it should "create rule" in {
        implicit val tid = transid()

        val ruleName = aname()
        val ruleNameQualified = EntityPath(WhiskEntity.qualifiedName(namespace, ruleName))
        val triggerName = aname()
        val actionName = aname()
        val actionNameQualified = EntityPath(WhiskEntity.qualifiedName(namespace, actionName))
        val trigger = WhiskTrigger(namespace, triggerName)
        val rule = WhiskRule(namespace, ruleName, triggerName, actionName)
        val action = WhiskAction(namespace, actionName, Exec.js("??"))
        val content = WhiskRulePut(Some(trigger.name), Some(action.name))
        put(entityStore, trigger, false)
        put(entityStore, action)
        Put(s"$collectionPath/${rule.name}", content) ~> sealRoute(routes(creds)) ~> check {
            val t = get(entityStore, trigger.docid, WhiskTrigger)
            deleteTrigger(t.docid)
            deleteRule(rule.docid)

            status should be(OK)
            t.rules.get(ruleNameQualified) shouldBe ReducedRule(actionNameQualified, Status.ACTIVE)
            val response = responseAs[WhiskRuleResponse]
            response should be(rule.withStatus(Status.ACTIVE))
        }
    }

    ignore should "create rule with an action in a package" in {
        implicit val tid = transid()

        val provider = WhiskPackage(namespace, aname())
        put(entityStore, provider)

        val actionName = aname()
        val action = WhiskAction(provider.path, actionName, Exec.js("??"))
        // TODO: this should be an EntityQName, not an EntityPath
        val actionNameQualified = EntityPath(WhiskEntity.qualifiedName(namespace, action.name))

        val triggerName = aname()
        val trigger = WhiskTrigger(namespace, triggerName)

        val ruleName = aname()
        val ruleNameQualified = EntityPath(WhiskEntity.qualifiedName(namespace, ruleName))

        val rule = WhiskRule(namespace, ruleName, triggerName, actionName)
        val content = WhiskRulePut(Some(trigger.name), Some(action.name))

        put(entityStore, trigger, false)
        put(entityStore, action)
        Put(s"$collectionPath/${rule.name}", content) ~> sealRoute(routes(creds)) ~> check {
            val t = get(entityStore, trigger.docid, WhiskTrigger)
            deleteTrigger(t.docid)
            deleteRule(rule.docid)

            status should be(OK)
            t.rules.get(ruleNameQualified) shouldBe ReducedRule(actionNameQualified, Status.ACTIVE)
            val response = responseAs[WhiskRuleResponse]
            response should be(rule.withStatus(Status.ACTIVE))
        }
    }

    it should "reject create rule with annotations which are too big" in {
        implicit val tid = transid()
        val keys: List[Long] = List.range(Math.pow(10, 9) toLong, (Parameters.sizeLimit.toBytes / 2 / 20 + Math.pow(10, 9) + 2) toLong)
        val parameters = keys map { key =>
            Parameters(key.toString, "a" * 10)
        } reduce (_ ++ _)
        val trigger = WhiskTrigger(namespace, aname())
        val action = WhiskAction(namespace, aname(), Exec.js("??"))
        val content = s"""{"trigger":"${trigger.name}","action":"${action.name}","annotations":$parameters}""".parseJson.asJsObject
        put(entityStore, trigger, false)
        put(entityStore, action)
        Put(s"$collectionPath/${aname()}", content) ~> sealRoute(routes(creds)) ~> check {
            val t = get(entityStore, trigger.docid, WhiskTrigger)
            deleteTrigger(t.docid)

            status should be(RequestEntityTooLarge)
            response.entity.toString should include(entityTooBigRejectionMessage)
        }
    }

    it should "reject update rule with annotations which are too big" in {
        implicit val tid = transid()
        val keys: List[Long] = List.range(Math.pow(10, 9) toLong, (Parameters.sizeLimit.toBytes / 2 / 20 + Math.pow(10, 9) + 2) toLong)
        val parameters = keys map { key =>
            Parameters(key.toString, "a" * 10)
        } reduce (_ ++ _)
        val trigger = WhiskTrigger(namespace, aname())
        val action = WhiskAction(namespace, aname(), Exec.js("??"))
        val rule = WhiskRule(namespace, aname(), trigger.name, action.name)
        val content = s"""{"trigger":"${trigger.name}","action":"${action.name}","annotations":$parameters}""".parseJson.asJsObject
        put(entityStore, trigger, false)
        put(entityStore, action)
        put(entityStore, rule)
        Put(s"$collectionPath/${rule.name}?overwrite=true", content) ~> sealRoute(routes(creds)) ~> check {
            val t = get(entityStore, trigger.docid, WhiskTrigger)
            deleteTrigger(t.docid)

            status should be(RequestEntityTooLarge)
            response.entity.toString should include(entityTooBigRejectionMessage)
        }
    }

    it should "reject rule if action does not exist" in {
        implicit val tid = transid()
        val trigger = WhiskTrigger(namespace, aname())
        val content = WhiskRulePut(Some(trigger.name), Some(EntityName("bogus action")))
        put(entityStore, trigger)
        Put(s"$collectionPath/xxx", content) ~> sealRoute(routes(creds)) ~> check {
            status should be(BadRequest)
            responseAs[String] === s"${WhiskEntity.qualifiedName(namespace, content.action.get)} does not exist"
        }
    }

    it should "reject rule if trigger does not exist" in {
        implicit val tid = transid()
        val action = WhiskAction(namespace, aname(), Exec.js("??"))
        val content = WhiskRulePut(Some(EntityName("bogus trigger")), Some(action.name))
        put(entityStore, action)
        Put(s"$collectionPath/xxx", content) ~> sealRoute(routes(creds)) ~> check {
            status should be(BadRequest)
            responseAs[String] === s"${WhiskEntity.qualifiedName(namespace, content.trigger.get)} does not exist"
        }
    }

    it should "reject rule if neither action or trigger do not exist" in {
        implicit val tid = transid()
        val content = WhiskRulePut(Some(EntityName("bogus trigger")), Some(EntityName("bogus action")))
        Put(s"$collectionPath/xxx", content) ~> sealRoute(routes(creds)) ~> check {
            status should be(BadRequest)
            responseAs[String].contains("does not exist") should be(true)
        }
    }

    it should "update rule updating trigger and action at once" in {
        implicit val tid = transid()
        val trigger = WhiskTrigger(namespace, aname())
        val action = WhiskAction(namespace, aname(), Exec.js("??"))
        val rule = WhiskRule(namespace, aname(), EntityName("bogus trigger"), EntityName("bogus action"))
        put(entityStore, trigger, false)
        put(entityStore, action)
        put(entityStore, rule, false)
        val content = WhiskRulePut(Some(trigger.name), Some(action.name))
        Put(s"$collectionPath/${rule.name}?overwrite=true", content) ~> sealRoute(routes(creds)) ~> check {
            val t = get(entityStore, trigger.docid, WhiskTrigger)
            deleteTrigger(t.docid)
            deleteRule(rule.docid)

            status should be(OK)

            t.rules.get(EntityPath(WhiskEntity.qualifiedName(namespace, rule.name))).action should be(EntityPath(WhiskEntity.qualifiedName(namespace, action.name)))
            val response = responseAs[WhiskRuleResponse]
            response should be(WhiskRuleResponse(namespace, rule.name, Status.ACTIVE, trigger.name, action.name, version = SemVer().upPatch))
        }
    }

    it should "update rule with a new action while passing the same trigger" in {
        implicit val tid = transid()
        val trigger = WhiskTrigger(namespace, aname())
        val action = WhiskAction(namespace, aname(), Exec.js("??"))
        val rule = WhiskRule(namespace, aname(), trigger.name, EntityName("bogus action"))
        put(entityStore, trigger, false)
        put(entityStore, action)
        put(entityStore, rule, false)
        val content = WhiskRulePut(Some(trigger.name), Some(action.name))
        Put(s"$collectionPath/${rule.name}?overwrite=true", content) ~> sealRoute(routes(creds)) ~> check {
            val t = get(entityStore, trigger.docid, WhiskTrigger)
            deleteTrigger(t.docid)
            deleteRule(rule.docid)

            status should be(OK)
            t.rules.get(EntityPath(WhiskEntity.qualifiedName(namespace, rule.name))).action should be(EntityPath(WhiskEntity.qualifiedName(namespace, action.name)))
            val response = responseAs[WhiskRuleResponse]
            response should be(WhiskRuleResponse(namespace, rule.name, Status.ACTIVE, trigger.name, action.name, version = SemVer().upPatch))
        }
    }

    it should "update rule with just a new action" in {
        implicit val tid = transid()
        val trigger = WhiskTrigger(namespace, aname())
        val action = WhiskAction(namespace, aname(), Exec.js("??"))
        val rule = WhiskRule(namespace, aname(), trigger.name, EntityName("bogus action"))
        put(entityStore, trigger, false)
        put(entityStore, action)
        put(entityStore, rule, false)
        val content = WhiskRulePut(action = Some(action.name))
        Put(s"$collectionPath/${rule.name}?overwrite=true", content) ~> sealRoute(routes(creds)) ~> check {
            val t = get(entityStore, trigger.docid, WhiskTrigger)
            deleteTrigger(t.docid)
            deleteRule(rule.docid)

            status should be(OK)
            t.rules.map { rules => rules(EntityPath(WhiskEntity.qualifiedName(namespace, rule.name))).action }.get should be(EntityPath(WhiskEntity.qualifiedName(namespace, action.name)))
            val response = responseAs[WhiskRuleResponse]
            response should be(WhiskRuleResponse(namespace, rule.name, Status.ACTIVE, trigger.name, action.name, version = SemVer().upPatch))
        }
    }

    it should "update rule with just a new trigger" in {
        implicit val tid = transid()
        val trigger = WhiskTrigger(namespace, aname())
        val action = WhiskAction(namespace, aname(), Exec.js("??"))
        val rule = WhiskRule(namespace, aname(), trigger.name, action.name)
        put(entityStore, trigger, false)
        put(entityStore, action)
        put(entityStore, rule, false)
        val content = WhiskRulePut(trigger = Some(trigger.name))
        Put(s"$collectionPath/${rule.name}?overwrite=true", content) ~> sealRoute(routes(creds)) ~> check {
            val t = get(entityStore, trigger.docid, WhiskTrigger)
            deleteTrigger(t.docid)
            deleteRule(rule.docid)

            status should be(OK)
            t.rules.get.get(EntityPath(WhiskEntity.qualifiedName(namespace, rule.name))) shouldBe a[Some[_]]
            val response = responseAs[WhiskRuleResponse]
            response should be(WhiskRuleResponse(namespace, rule.name, Status.ACTIVE, trigger.name, action.name, version = SemVer().upPatch))
        }
    }

    it should "update rule when no new content is provided" in {
        implicit val tid = transid()
        val trigger = WhiskTrigger(namespace, aname())
        val action = WhiskAction(namespace, aname(), Exec.js("??"))
        val rule = WhiskRule(namespace, aname(), trigger.name, action.name)
        put(entityStore, trigger, false)
        put(entityStore, action)
        put(entityStore, rule, false)
        val content = WhiskRulePut(None, None, None, None, None)
        Put(s"$collectionPath/${rule.name}?overwrite=true", content) ~> sealRoute(routes(creds)) ~> check {
            deleteTrigger(trigger.docid)
            deleteRule(rule.docid)

            status should be(OK)
            val response = responseAs[WhiskRuleResponse]
            response should be(WhiskRuleResponse(namespace, rule.name, Status.ACTIVE, trigger.name, action.name, version = SemVer().upPatch))
        }
    }

    it should "reject update rule if trigger does not exist" in {
        implicit val tid = transid()
        val action = WhiskAction(namespace, aname(), Exec.js("??"))
        val rule = WhiskRule(namespace, aname(), EntityName("bogus trigger"), action.name)
        put(entityStore, action)
        put(entityStore, rule)
        val content = WhiskRulePut(action = Some(action.name))
        Put(s"$collectionPath/${rule.name}?overwrite=true", content) ~> sealRoute(routes(creds)) ~> check {
            status should be(BadRequest)
            responseAs[String] === s"${WhiskEntity.qualifiedName(namespace, EntityName(rule.trigger.name))} does not exist"
        }
    }

    it should "reject update rule if action does not exist" in {
        implicit val tid = transid()
        val trigger = WhiskTrigger(namespace, aname())
        val rule = WhiskRule(namespace, aname(), trigger.name, EntityName("bogus action"))
        put(entityStore, trigger)
        put(entityStore, rule)
        val content = WhiskRulePut(trigger = Some(trigger.name))
        Put(s"$collectionPath/${rule.name}?overwrite=true", content) ~> sealRoute(routes(creds)) ~> check {
            status should be(BadRequest)
            responseAs[String] === s"${WhiskEntity.qualifiedName(namespace, EntityName(rule.action.name))} does not exist"
        }
    }

    it should "reject update rule if neither trigger or action exist" in {
        implicit val tid = transid()
        val rule = WhiskRule(namespace, aname(), EntityName("bogus trigger"), EntityName("bogus action"))
        put(entityStore, rule)
        val content = WhiskRulePut(None, None, None, None, None)
        Put(s"$collectionPath/${rule.name}?overwrite=true", content) ~> sealRoute(routes(creds)) ~> check {
            status should be(BadRequest)
            responseAs[String].contains("does not exist") should be(true)
        }
    }

    it should "reject update rule in state active" in {
        implicit val tid = transid()
        val triggerName = EntityName("a trigger")
        val ruleName = aname()
        val trigger = WhiskTrigger(namespace, triggerName, rules = Some(Map(EntityPath(WhiskEntity.qualifiedName(namespace, ruleName)) -> ReducedRule(namespace, Status.ACTIVE))))
        val rule = WhiskRule(namespace, ruleName, triggerName, EntityName("an action"))
        put(entityStore, trigger)
        put(entityStore, rule)
        val content = WhiskRulePut(publish = Some(!rule.publish))
        Put(s"$collectionPath/${rule.name}?overwrite=true", content) ~> sealRoute(routes(creds)) ~> check {
            status should be(Conflict)
        }
    }

    //// POST /rules/name
    it should "do nothing to disable already disabled rule" in {
        implicit val tid = transid()
        val rule = WhiskRule(namespace, aname(), EntityName("a trigger"), EntityName("an action"))
        put(entityStore, rule)
        Post(s"$collectionPath/${rule.name}", inactiveStatus) ~> sealRoute(routes(creds)) ~> check {
            status should be(OK)
        }
    }

    it should "do nothing to enable already enabled rule" in {
        implicit val tid = transid()
        val ruleName = aname()
        val triggerName = aname()
        val trigger = WhiskTrigger(namespace, triggerName, rules = Some(Map(EntityPath(WhiskEntity.qualifiedName(namespace, ruleName)) -> ReducedRule(namespace, Status.ACTIVE))))
        val rule = WhiskRule(namespace, ruleName, triggerName, EntityName("an action"))
        put(entityStore, trigger)
        put(entityStore, rule)
        Post(s"$collectionPath/${rule.name}", activeStatus) ~> sealRoute(routes(creds)) ~> check {
            status should be(OK)
        }
    }

    it should "reject post with status undefined" in {
        implicit val tid = transid()
        Post(s"$collectionPath/xyz") ~> sealRoute(routes(creds)) ~> check {
            status should be(BadRequest)
        }
    }

    it should "reject post with invalid status" in {
        implicit val tid = transid()
        val badStatus = s"""{"status":"xxx"}""".parseJson.asJsObject
        Post(s"$collectionPath/xyz", badStatus) ~> sealRoute(routes(creds)) ~> check {
            status should be(BadRequest)
        }
    }

    it should "activate rule" in {
        implicit val tid = transid()
        val ruleName = aname()
        val ruleNameQualified = EntityPath(WhiskEntity.qualifiedName(namespace, ruleName))
        val triggerName = aname()
        val rule = WhiskRule(namespace, ruleName, triggerName, EntityName("an action"))
        val trigger = WhiskTrigger(namespace, triggerName, rules = Some(Map(ruleNameQualified -> ReducedRule(namespace, Status.INACTIVE))))
        put(entityStore, trigger, false)
        put(entityStore, rule)
        Post(s"$collectionPath/${rule.name}", activeStatus) ~> sealRoute(routes(creds)) ~> check {
            val t = get(entityStore, trigger.docid, WhiskTrigger)
            deleteTrigger(t.docid)

            status should be(OK)

            t.rules.get(ruleNameQualified).status should be(Status.ACTIVE)
        }
    }

    it should "activate rule without rule in trigger" in {
        implicit val tid = transid()
        val ruleName = aname()
        val ruleNameQualified = EntityPath(WhiskEntity.qualifiedName(namespace, ruleName))
        val triggerName = aname()
        val rule = WhiskRule(namespace, ruleName, triggerName, EntityName("an action"))
        val trigger = WhiskTrigger(namespace, triggerName)
        put(entityStore, trigger, false)
        put(entityStore, rule)
        Post(s"$collectionPath/${rule.name}", activeStatus) ~> sealRoute(routes(creds)) ~> check {
            val t = get(entityStore, trigger.docid, WhiskTrigger)
            deleteTrigger(t.docid)

            status should be(OK)
            t.rules.get(ruleNameQualified).status should be(Status.ACTIVE)
        }
    }

    it should "reject rule activation, if the trigger is absent" in {
        implicit val tid = transid()
        val ruleName = aname()
        val ruleNameQualified = EntityPath(WhiskEntity.qualifiedName(namespace, ruleName))
        val triggerName = aname()
        val rule = WhiskRule(namespace, ruleName, triggerName, EntityName("an action"))
        put(entityStore, rule)
        Post(s"$collectionPath/${rule.name}", activeStatus) ~> sealRoute(routes(creds)) ~> check {
            status should be(NotFound)
        }
    }

    it should "deactivate rule" in {
        implicit val tid = transid()
        val ruleName = aname()
        val ruleNameQualified = EntityPath(WhiskEntity.qualifiedName(namespace, ruleName))
        val triggerName = aname()
        val rule = WhiskRule(namespace, ruleName, triggerName, EntityName("an action"))
        val trigger = WhiskTrigger(namespace, triggerName, rules = Some(Map(ruleNameQualified -> ReducedRule(namespace, Status.ACTIVE))))
        put(entityStore, trigger, false)
        put(entityStore, rule)
        Post(s"$collectionPath/${rule.name}", inactiveStatus) ~> sealRoute(routes(creds)) ~> check {
            val t = get(entityStore, trigger.docid, WhiskTrigger)
            deleteTrigger(t.docid)

            status should be(OK)
            t.rules.get(ruleNameQualified).status should be(Status.INACTIVE)
        }
    }

    // invalid resource
    it should "reject invalid resource" in {
        implicit val tid = transid()
        val rule = WhiskRule(namespace, aname(), EntityName("a trigger"), EntityName("an action"))
        put(entityStore, rule)
        Get(s"$collectionPath/${rule.name}/bar") ~> sealRoute(routes(creds)) ~> check {
            status should be(NotFound)
        }
    }

    // migration path
    it should "handle a rule with the old schema gracefully" in {
        implicit val tid = transid()
        val rule = OldWhiskRule(namespace, aname(), EntityName("a trigger"), EntityName("an action"), Status.ACTIVE)
        put(entityStore, rule)
        Get(s"$collectionPath/${rule.name}") ~> sealRoute(routes(creds)) ~> check {
            status should be(OK)
            val response = responseAs[WhiskRuleResponse]
            response should be(rule.toWhiskRule.withStatus(Status.INACTIVE))
        }
    }

    it should "create rule even if the attached trigger has the old schema" in {
        implicit val tid = transid()

        val ruleName = aname()
        val ruleNameQualified = EntityPath(WhiskEntity.qualifiedName(namespace, ruleName))
        val triggerName = aname()
        val actionName = aname()
        val actionNameQualified = EntityPath(WhiskEntity.qualifiedName(namespace, actionName))
        val trigger = OldWhiskTrigger(namespace, triggerName)
        val rule = WhiskRule(namespace, ruleName, triggerName, actionName)
        val action = WhiskAction(namespace, actionName, Exec.js("??"))
        val content = WhiskRulePut(Some(trigger.name), Some(action.name))
        put(entityStore, trigger, false)
        put(entityStore, action)
        Put(s"$collectionPath/${rule.name}", content) ~> sealRoute(routes(creds)) ~> check {
            val t = get(entityStore, trigger.docid, WhiskTrigger)
            deleteTrigger(t.docid)
            deleteRule(rule.docid)

            status should be(OK)
            t.rules.get(ruleNameQualified) shouldBe ReducedRule(actionNameQualified, Status.ACTIVE)
            val response = responseAs[WhiskRuleResponse]
            response should be(rule.withStatus(Status.ACTIVE))
        }
    }

    it should "activate rule even if it is still in the old schema" in {
        implicit val tid = transid()
        val ruleName = aname()
        val ruleNameQualified = EntityPath(WhiskEntity.qualifiedName(namespace, ruleName))
        val triggerName = aname()
        val rule = OldWhiskRule(namespace, ruleName, triggerName, EntityName("an action"), Status.ACTIVE)
        val trigger = WhiskTrigger(namespace, triggerName, rules = Some(Map(ruleNameQualified -> ReducedRule(namespace, Status.INACTIVE))))
        put(entityStore, trigger, false)
        put(entityStore, rule)
        Post(s"$collectionPath/${rule.name}", activeStatus) ~> sealRoute(routes(creds)) ~> check {
            val t = get(entityStore, trigger.docid, WhiskTrigger)
            deleteTrigger(t.docid)

            status should be(OK)

            t.rules.get(ruleNameQualified).status should be(Status.ACTIVE)
        }
    }

    it should "report proper error when record is corrupted on delete" in {
        implicit val tid = transid()
        val entity = BadEntity(namespace, aname())
        put(entityStore, entity)

        Delete(s"$collectionPath/${entity.name}") ~> sealRoute(routes(creds)) ~> check {
            status should be(InternalServerError)
            responseAs[ErrorResponse].error shouldBe Messages.corruptedEntity
        }
    }

    it should "report proper error when record is corrupted on get" in {
        implicit val tid = transid()
        val entity = BadEntity(namespace, aname())
        put(entityStore, entity)

        Get(s"$collectionPath/${entity.name}") ~> sealRoute(routes(creds)) ~> check {
            status should be(InternalServerError)
            responseAs[ErrorResponse].error shouldBe Messages.corruptedEntity
        }
    }

    it should "report proper error when record is corrupted on put" in {
        implicit val tid = transid()
        val entity = BadEntity(namespace, aname())
        put(entityStore, entity)

        val content = WhiskRulePut()
        Put(s"$collectionPath/${entity.name}", content) ~> sealRoute(routes(creds)) ~> check {
            status should be(InternalServerError)
            responseAs[ErrorResponse].error shouldBe Messages.corruptedEntity
        }
    }

    /*
    it should "report proper error when action record is corrupted on put" in {
        implicit val tid = transid()
        val tentity = BadEntity(namespace, aname())
        val aentity = BadEntity(namespace, aname())
        val rule = WhiskRule(namespace, aname(), afullname(namespace, aname().name), afullname(namespace, aname().name))
        val trigger = WhiskTrigger(namespace, rule.trigger.name)
        val action = WhiskAction(namespace, rule.action.name, Exec.js("??"))

        val contenta = WhiskRulePut(Some(tentity.fullyQualifiedName(false)), Some(aentity.fullyQualifiedName(false)))
        val contentb = WhiskRulePut(Some(trigger.fullyQualifiedName(false)), Some(aentity.fullyQualifiedName(false)))
        val contentc = WhiskRulePut(Some(tentity.fullyQualifiedName(false)), Some(action.fullyQualifiedName(false)))

        put(entityStore, tentity)
        put(entityStore, aentity)
        put(entityStore, trigger)
        put(entityStore, action)

        Put(s"$collectionPath/${aname()}", contenta) ~> sealRoute(routes(creds)) ~> check {
            status should be(BadRequest)
            responseAs[ErrorResponse].error shouldBe Messages.corruptedEntity
        }

        Put(s"$collectionPath/${aname()}", contentb) ~> sealRoute(routes(creds)) ~> check {
            status should be(BadRequest)
            responseAs[ErrorResponse].error shouldBe Messages.corruptedEntity
        }

        Put(s"$collectionPath/${aname()}", contentc) ~> sealRoute(routes(creds)) ~> check {
            status should be(BadRequest)
            responseAs[ErrorResponse].error shouldBe Messages.corruptedEntity
        }
    }*/
}
