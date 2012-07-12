/*
 *  ____    ____    _____    ____    ___     ____ 
 * |  _ \  |  _ \  | ____|  / ___|  / _/    / ___|        Precog (R)
 * | |_) | | |_) | |  _|   | |     | |  /| | |  _         Advanced Analytics Engine for NoSQL Data
 * |  __/  |  _ <  | |___  | |___  |/ _| | | |_| |        Copyright (C) 2010 - 2013 SlamData, Inc.
 * |_|     |_| \_\ |_____|  \____|   /__/   \____|        All Rights Reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the 
 * GNU Affero General Public License as published by the Free Software Foundation, either version 
 * 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; 
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See 
 * the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this 
 * program. If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.precog
package daze

import blueeyes.json.JPath

import com.precog.yggdrasil._
import com.precog.yggdrasil.serialization._
import com.precog.util._
import com.precog.common.{Path, VectorCase}

import org.joda.time._
import org.joda.time.format._
import org.joda.time.DateTimeZone

import java.lang.Math._
import collection.immutable.ListSet

import akka.dispatch.{Await, Future}
import akka.util.duration._
import blueeyes.json.{JPathField, JPathIndex}

import scalaz.{NonEmptyList => NEL, _}
import scalaz.effect._
import scalaz.syntax.traverse._
import scalaz.std.list._
import scalaz.std.partialFunction._

import com.weiglewilczek.slf4s.Logging

trait EvaluatorConfig {
  implicit def valueSerialization: SortSerialization[SValue]
  implicit def eventSerialization: SortSerialization[(Identities, SValue)]
  implicit def groupSerialization: SortSerialization[(SValue, Identities, SValue)]
  implicit def memoSerialization: IncrementalSerialization[(Identities, SValue)]
  def maxEvalDuration: akka.util.Duration
  def idSource: IdSource
}

trait Evaluator extends DAG
    with CrossOrdering
    with Memoizer
    with TableModule        // TODO specific implementation
    with ImplLibrary
    with InfixLib
    with UnaryLib
    with BigDecimalOperations
    with YggConfigComponent 
    with Logging { self =>
  
  import Function._
  
  import instructions._
  import dag._
  import trans._

  type YggConfig <: EvaluatorConfig 

  sealed trait Context

  // implicit def asyncContext: akka.dispatch.ExecutionContext

  def withContext[A](f: Context => A): A = 
    f(new Context {})

  import yggConfig._

  implicit val valueOrder: (SValue, SValue) => Ordering = Order[SValue].order _
  
  def PrimitiveEqualsF2: F2
  def ConstantEmptyArray: F1
  
  def eval(userUID: String, graph: DepGraph, ctx: Context): Table = {
    logger.debug("Eval for %s = %s".format(userUID, graph))

    def loop(graph: DepGraph, assume: Map[DepGraph, Table], splits: Unit, ctx: Context): PendingTable = graph match {
      case g if assume contains g => PendingTable(assume(g), graph, TransSpec1.Id)
      
      case s @ SplitParam(_, index) =>
        PendingTable(ops.empty, graph, TransSpec1.Id)     // TODO
      
      case s @ SplitGroup(_, index, _) =>
        PendingTable(ops.empty, graph, TransSpec1.Id)     // TODO
      
      case Root(_, instr) => {
        val table = graph.value collect {
          case SString(str) => ops.constString(Set(CString(str)))
          case SDecimal(d) => ops.constDecimal(Set(CNum(d)))
          case SBoolean(b) => ops.constBoolean(Set(CBoolean(b)))
          case SNull => ops.constNull
          case SObject(map) if map.isEmpty => ops.constEmptyObject
          case SArray(Vector()) => ops.constEmptyArray
        }
        
        PendingTable(table.get, graph, TransSpec1.Id)       // die a horrible death if isEmpty
      }
      
      case dag.New(_, parent) => loop(parent, assume, splits, ctx)   // TODO John swears this part is easy
      
      case dag.LoadLocal(_, _, parent, _) => {
        val back = parent.value match {
          case Some(SString(str)) => ops.loadStatic(Path(str))
          case Some(_) => ops.empty
          
          case None => {
            val PendingTable(table, _, trans) = loop(parent, assume, splits, ctx)
            ops.loadDynamic(table.transform(liftToValues(trans)))
          }
        }
        
        PendingTable(back, graph, TransSpec1.Id)
      }
      
      case dag.Morph1(_, m, parent) => {
        val PendingTable(parentTable, parentGraph, parentTrans) = loop(parent, assume, splits, ctx)
        PendingTable(m(parentTable.transform(parentTrans)), graph, TransSpec1.Id)
      }
      
      case dag.Morph2(_, m, left, right) => {
        val PendingTable(leftTable, _, leftTrans) = loop(left, assume, splits, ctx)
        val PendingTable(rightTable, _, rightTrans) = loop(right, assume, splits, ctx)
        
        val leftResult = leftTable.transform(leftTrans)
        val rightResult = rightTable.transform(rightTrans)
        
        val spec = trans.ArrayConcat(trans.WrapArray(Leaf(SourceLeft)), trans.WrapArray(Leaf(SourceRight)))
        val key = trans.DerefObjectStatic(Leaf(Source), constants.Key)
        
        val aligned = m.alignment match {
          case Some(MorphismAlignment.Cross) => leftResult.cross(rightResult)(spec)
          case Some(MorphismAlignment.Match) => join(leftResult, rightResult)(key, spec)
          case None => sys.error("oh the calamity!")
        }
        
        PendingTable(m(aligned), graph, TransSpec1.Id)
      }
      
      case dag.Distinct(_, parent) =>
        PendingTable(ops.empty, graph, TransSpec1.Id)     // TODO
      
      case Operate(_, instructions.WrapArray, parent) => {
        val PendingTable(parentTable, parentGraph, parentTrans) = loop(parent, assume, splits, ctx)
        PendingTable(parentTable, parentGraph, trans.WrapArray(parentTrans))
      }
      
      case o @ Operate(_, op, parent) => {
        val PendingTable(parentTable, parentGraph, parentTrans) = loop(parent, assume, splits, ctx)
        
        // TODO unary typing
        PendingTable(parentTable, parentGraph, trans.Map1(parentTrans, op1(op).f1))
      }
      
      case r @ dag.Reduce(_, red, parent) => {
        val PendingTable(parentTable, _, parentTrans) = loop(parent, assume, splits, ctx)
        val result = red(parentTable.transform(parentTrans))
        PendingTable(result, graph, TransSpec1.Id)
      }
      
      case s @ dag.Split(line, specs, child) =>
        PendingTable(ops.empty, graph, TransSpec1.Id)     // TODO
      
      // VUnion and VIntersect removed, TODO: remove from bytecode
      
      case Join(_, instr @ (IUnion | IIntersect | SetDifference), left, right) =>
        PendingTable(ops.empty, graph, TransSpec1.Id)     // TODO
      
      case Join(_, Map2Cross(Eq) | Map2CrossLeft(Eq) | Map2CrossRight(Eq), left, right) if right.value.isDefined => {
        val PendingTable(parentTable, parentGraph, parentTrans) = loop(left, assume, splits, ctx)
        PendingTable(parentTable, parentGraph, trans.Map1(parentTrans, PrimitiveEqualsF2.partialRight(svalueToCValue(right.value.get))))
      }
      
      case Join(_, Map2Cross(Eq) | Map2CrossLeft(Eq) | Map2CrossRight(Eq), left, right) if left.value.isDefined => {
        val PendingTable(parentTable, parentGraph, parentTrans) = loop(right, assume, splits, ctx)
        PendingTable(parentTable, parentGraph, trans.Map1(parentTrans, PrimitiveEqualsF2.partialLeft(svalueToCValue(left.value.get))))
      }
      
      case Join(_, Map2Cross(NotEq) | Map2CrossLeft(NotEq) | Map2CrossRight(NotEq), left, right) if right.value.isDefined => {
        val PendingTable(parentTable, parentGraph, parentTrans) = loop(left, assume, splits, ctx)
        val eqTrans = trans.Map1(parentTrans, PrimitiveEqualsF2.partialRight(svalueToCValue(right.value.get)))
        PendingTable(parentTable, parentGraph, trans.Map1(eqTrans, op1(Comp).f1))
      }
      
      case Join(_, Map2Cross(NotEq) | Map2CrossLeft(NotEq) | Map2CrossRight(NotEq), left, right) if left.value.isDefined => {
        val PendingTable(parentTable, parentGraph, parentTrans) = loop(right, assume, splits, ctx)
        val eqTrans = trans.Map1(parentTrans, PrimitiveEqualsF2.partialRight(svalueToCValue(left.value.get)))
        PendingTable(parentTable, parentGraph, trans.Map1(eqTrans, op1(Comp).f1))
      }
      
      case Join(_, Map2Cross(instructions.WrapObject) | Map2CrossLeft(instructions.WrapObject) | Map2CrossRight(instructions.WrapObject), left, right) if left.value.isDefined => {
        left.value match {
          case Some(value @ SString(str)) => {
            val PendingTable(parentTable, parentGraph, parentTrans) = loop(left, assume, splits, ctx)
            PendingTable(parentTable, parentGraph, trans.WrapObject(parentTrans, str))
          }
          
          case _ =>
            PendingTable(ops.empty, graph, TransSpec1.Id)
        }
      }
      
      case Join(_, Map2Cross(DerefObject) | Map2CrossLeft(DerefObject) | Map2CrossRight(DerefObject), left, right) if right.value.isDefined => {
        right.value match {
          case Some(value @ SString(str)) => {
            val PendingTable(parentTable, parentGraph, parentTrans) = loop(left, assume, splits, ctx)
            PendingTable(parentTable, parentGraph, DerefObjectStatic(parentTrans, JPathField(str)))
          }
          
          case _ =>
            PendingTable(ops.empty, graph, TransSpec1.Id)
        }
      }
      
      case Join(_, Map2Cross(DerefArray) | Map2CrossLeft(DerefArray) | Map2CrossRight(DerefArray), left, right) if right.value.isDefined => {
        right.value match {
          case Some(value @ SDecimal(d)) => {
            val PendingTable(parentTable, parentGraph, parentTrans) = loop(left, assume, splits, ctx)
            PendingTable(parentTable, parentGraph, DerefArrayStatic(parentTrans, JPathIndex(d.toInt)))
          }
          
          // TODO other numeric types
          
          case _ =>
            PendingTable(ops.empty, graph, TransSpec1.Id)
        }
      }
      
      case Join(_, Map2Cross(instructions.ArraySwap) | Map2CrossLeft(instructions.ArraySwap) | Map2CrossRight(instructions.ArraySwap), left, right) if right.value.isDefined => {
        right.value match {
          case Some(value @ SDecimal(d)) => {     // TODO other numeric types
            val PendingTable(parentTable, parentGraph, parentTrans) = loop(left, assume, splits, ctx)
            PendingTable(parentTable, parentGraph, trans.ArraySwap(parentTrans, d.toInt))
          }
          
          case _ =>
            PendingTable(ops.empty, graph, TransSpec1.Id)
        }
      }

      // case Join(_, Map2CrossLeft(op), left, right) if right.isSingleton =>
      
      // case Join(_, Map2CrossRight(op), left, right) if left.isSingleton =>
      
      // begin: annoyance with Scala's lousy pattern matcher
      case Join(_, opSpec @ (Map2Cross(_) | Map2CrossRight(_) | Map2CrossLeft(_)), left, right) if right.value.isDefined => {
        val op = opSpec match {
          case Map2Cross(op) => op
          case Map2CrossRight(op) => op
          case Map2CrossLeft(op) => op
        }
        
        val PendingTable(parentTable, parentGraph, parentTrans) = loop(left, assume, splits, ctx)
        
        val cv = svalueToCValue(right.value.get)
        val f1 = op2(op).f2.partialRight(cv)
        
        PendingTable(parentTable, parentGraph, trans.Map1(parentTrans, f1))
      }
      
      case Join(_, opSpec @ (Map2Cross(_) | Map2CrossRight(_) | Map2CrossLeft(_)), left, right) if left.value.isDefined => {
        val op = opSpec match {
          case Map2Cross(op) => op
          case Map2CrossRight(op) => op
          case Map2CrossLeft(op) => op
        }
        
        val PendingTable(parentTable, parentGraph, parentTrans) = loop(right, assume, splits, ctx)
        
        val cv = svalueToCValue(left.value.get)
        val f1 = op2(op).f2.partialLeft(cv)
        
        PendingTable(parentTable, parentGraph, trans.Map1(parentTrans, f1))
      }
      // end: annoyance
      
      case Join(_, Map2Match(op), left, right) => {
        // TODO binary typing
        
        val PendingTable(parentLeftTable, parentLeftGraph, parentLeftTrans) = loop(left, assume, splits, ctx)
        val PendingTable(parentRightTable, parentRightGraph, parentRightTrans) = loop(right, assume, splits, ctx)
        
        if (parentLeftGraph == parentRightGraph) {
          PendingTable(parentLeftTable, parentLeftGraph, transFromBinOp(op)(parentLeftTrans, parentRightTrans))
        } else {
          val key = trans.DerefObjectStatic(Leaf(Source), constants.Key)
          
          val leftResult = parentLeftTable.transform(parentLeftTrans)
          val rightResult = parentRightTable.transform(parentRightTrans)
          
          val spec = buildWrappedJoinSpec(sharedPrefixLength(left, right), left.provenance.length, right.provenance.length)(transFromBinOp(op))
          
          val result = join(leftResult, rightResult)(key, spec)
          PendingTable(result, graph, TransSpec1.Id)
        } 
      }

      // guaranteed: cross, cross_left and cross_right
      case j @ Join(_, instr, left, right) => {
        val (op, isLeft) = instr match {
          case Map2Cross(op) => (op, true)
          case Map2CrossRight(op) => (op, false)
          case Map2CrossLeft(op) => (op, true)
        }
        
        val PendingTable(parentLeftTable, parentLeftGraph, parentLeftTrans) = loop(left, assume, splits, ctx)
        val PendingTable(parentRightTable, parentRightGraph, parentRightTrans) = loop(right, assume, splits, ctx)
        
        val leftResult = parentLeftTable.transform(parentLeftTrans)
        val rightResult = parentRightTable.transform(parentRightTrans)
        
        val result = if (isLeft)
          leftResult.cross(rightResult)(buildWrappedCrossSpec(transFromBinOp(op)))
        else
          rightResult.cross(leftResult)(buildWrappedCrossSpec(flip(transFromBinOp(op))))
        
        PendingTable(result, graph, TransSpec1.Id)
      }
      
      case dag.Filter(_, None, target, boolean) => {
        // TODO binary typing
        
        val PendingTable(parentTargetTable, parentTargetGraph, parentTargetTrans) = loop(target, assume, splits, ctx)
        val PendingTable(parentBooleanTable, parentBooleanGraph, parentBooleanTrans) = loop(boolean, assume, splits, ctx)
        
        if (parentTargetGraph == parentBooleanGraph)
          PendingTable(parentTargetTable, parentTargetGraph, trans.Filter(parentTargetTrans, parentBooleanTrans))
        else {
          val key = trans.DerefObjectStatic(Leaf(Source), constants.Key)
          
          val targetResult = parentTargetTable.transform(parentTargetTrans)
          val booleanResult = parentBooleanTable.transform(parentBooleanTrans)
          
          val spec = buildWrappedJoinSpec(sharedPrefixLength(target, boolean), target.provenance.length, boolean.provenance.length) { (srcLeft, srcRight) =>
            trans.Filter(srcLeft, srcRight)
          }
          
          val result = join(targetResult, booleanResult)(key, spec)
          PendingTable(result, graph, TransSpec1.Id)
        }
      }
      
      case f @ dag.Filter(_, Some(cross), target, boolean) => {
        val isLeft = cross match {
          case CrossNeutral => true
          case CrossRight => false
          case CrossLeft => true
        }
        
        val PendingTable(parentTargetTable, parentTargetGraph, parentTargetTrans) = loop(target, assume, splits, ctx)
        val PendingTable(parentBooleanTable, parentBooleanGraph, parentBooleanTrans) = loop(boolean, assume, splits, ctx)
        
        /* target match {
          case Join(_, Map2Cross(Eq) | Map2CrossLeft(Eq) | Map2CrossRight(Eq), left, right) => {
            
          }
        } */
        
        val targetResult = parentTargetTable.transform(parentTargetTrans)
        val booleanResult = parentBooleanTable.transform(parentBooleanTrans)
        
        val result = if (isLeft) {
          val spec = buildWrappedCrossSpec { (srcLeft, srcRight) =>
            trans.Filter(srcLeft, srcRight)
          }
          targetResult.cross(booleanResult)(spec)
        } else {
          val spec = buildWrappedCrossSpec { (srcLeft, srcRight) =>
            trans.Filter(srcRight, srcLeft)
          }
          booleanResult.cross(targetResult)(spec)
        }
        
        PendingTable(result, graph, TransSpec1.Id)
      }
      
      case s @ Sort(parent, indexes) =>
        PendingTable(ops.empty, graph, TransSpec1.Id)     // TODO
      
      case m @ Memoize(parent, _) =>
        loop(parent, assume, splits, ctx)     // TODO
    }
    
    val PendingTable(table, _, spec) = loop(memoize(orderCrosses(graph)), Map(), (), ctx)
    table.transform(liftToValues(spec))
  }
  
  private def op1(op: UnaryOperation): Op1 = op match {
    case BuiltInFunction1Op(op1) => op1
    
    case instructions.New | instructions.WrapArray => sys.error("assertion error")
    
    case Comp => Unary.Comp
    case Neg => Unary.Neg
  }
  
  private def op2(op: BinaryOperation): Op2 = op match {
    case BuiltInFunction2Op(op2) => op2
    
    case instructions.Add => Infix.Add
    case instructions.Sub => Infix.Sub
    case instructions.Mul => Infix.Mul
    case instructions.Div => Infix.Div
    
    case instructions.Lt => Infix.Lt
    case instructions.LtEq => Infix.LtEq
    case instructions.Gt => Infix.Gt
    case instructions.GtEq => Infix.GtEq
    
    case instructions.Eq | instructions.NotEq => sys.error("assertion error")
    
    case instructions.Or => Infix.Or
    case instructions.And => Infix.And
    
    case instructions.WrapObject | instructions.JoinObject |
         instructions.JoinArray | instructions.ArraySwap |
         instructions.DerefObject | instructions.DerefArray => sys.error("assertion error")
  }
  
  private def transFromBinOp[A <: SourceType](op: BinaryOperation)(left: TransSpec[A], right: TransSpec[A]): TransSpec[A] = op match {
    case Eq => trans.Equal(left, right)
    case NotEq => trans.Map1(trans.Equal(left, right), op1(Comp).f1)
    case instructions.WrapObject => WrapObjectDynamic(left, right)
    case JoinObject => ObjectConcat(left, right)
    case JoinArray => ArrayConcat(left, right)
    case instructions.ArraySwap => sys.error("nothing happens")
    case DerefObject => DerefObjectDynamic(left, right)
    case DerefArray => DerefArrayDynamic(left, right)
    case _ => trans.Map2(left, right, op2(op).f2)
  }

  private def sharedPrefixLength(left: DepGraph, right: DepGraph): Int =
    left.provenance zip right.provenance takeWhile { case (a, b) => a == b } length
  
  private def svalueToCValue(sv: SValue) = sv match {
    case SString(str) => CString(str)
    case SDecimal(d) => CNum(d)
    // case SLong(l) => CLong(l)
    // case SDouble(d) => CDouble(d)
    case SNull => CNull
    case SObject(obj) if obj.isEmpty => CEmptyObject
    case SArray(Vector()) => CEmptyArray
    case _ => sys.error("die a horrible death")
  }
  
  private def join(left: Table, right: Table)(key: TransSpec1, spec: TransSpec2): Table = {
    val emptySpec = trans.Map1(Leaf(Source), ConstantEmptyArray)
    val result = left.cogroup(key, key, right)(emptySpec, emptySpec, trans.WrapArray(spec))
    result.transform(trans.DerefArrayStatic(Leaf(Source), JPathIndex(0)))
  }
  
  private def buildWrappedJoinSpec(sharedLength: Int, leftLength: Int, rightLength: Int)(spec: (TransSpec2, TransSpec2) => TransSpec2): TransSpec2 = {
    val leftIdentitySpec = DerefObjectStatic(Leaf(SourceLeft), constants.Key)
    val rightIdentitySpec = DerefObjectStatic(Leaf(SourceRight), constants.Key)
    
    val sharedDerefs = for (i <- 0 until sharedLength)
      yield DerefArrayStatic(leftIdentitySpec, JPathIndex(i))
    
    val unsharedLeft = for (i <- (sharedLength - 1) until (leftLength - sharedLength))
      yield DerefArrayStatic(leftIdentitySpec, JPathIndex(i))
    
    val unsharedRight = for (i <- (sharedLength - 1) until (rightLength - sharedLength))
      yield DerefArrayStatic(rightIdentitySpec, JPathIndex(i))
    
    val derefs: Seq[TransSpec2] = sharedDerefs ++ unsharedLeft ++ unsharedRight
    
    val newIdentitySpec = if (derefs.isEmpty)
      trans.Map1(Leaf(SourceLeft), ConstantEmptyArray)
    else
      derefs reduce { trans.ArrayConcat(_, _) }
    
    val wrappedIdentitySpec = trans.WrapObject(newIdentitySpec, constants.Key.name)
    
    val leftValueSpec = DerefObjectStatic(Leaf(SourceLeft), constants.Value)
    val rightValueSpec = DerefObjectStatic(Leaf(SourceRight), constants.Value)
    
    val wrappedValueSpec = trans.WrapObject(spec(leftValueSpec, rightValueSpec), constants.Value.name)
      
    ObjectConcat(
      ObjectConcat(
        ObjectConcat(Leaf(SourceLeft), Leaf(SourceRight)),
        wrappedIdentitySpec),
      wrappedValueSpec)
  }
  
  private def buildWrappedCrossSpec(spec: (TransSpec2, TransSpec2) => TransSpec2): TransSpec2 = {
    val leftIdentitySpec = DerefObjectStatic(Leaf(SourceLeft), constants.Key)
    val rightIdentitySpec = DerefObjectStatic(Leaf(SourceRight), constants.Key)
    
    val newIdentitySpec = ArrayConcat(leftIdentitySpec, rightIdentitySpec)
    
    val wrappedIdentitySpec = trans.WrapObject(newIdentitySpec, constants.Key.name)
    
    val leftValueSpec = DerefObjectStatic(Leaf(SourceLeft), constants.Value)
    val rightValueSpec = DerefObjectStatic(Leaf(SourceRight), constants.Value)
    
    val wrappedValueSpec = trans.WrapObject(spec(leftValueSpec, rightValueSpec), constants.Value.name)
      
    ObjectConcat(
      ObjectConcat(
        ObjectConcat(Leaf(SourceLeft), Leaf(SourceRight)),
        wrappedIdentitySpec),
      wrappedValueSpec)
  }
  
  private def flip[A, B, C](f: (A, B) => C)(b: B, a: A): C = f(a, b)      // is this in scalaz?
  
  private def liftToValues(trans: TransSpec1): TransSpec1 =
    TableTransSpec.makeTransSpec(Map(constants.Value -> trans))
   
  
  private type TableTransSpec[+A <: SourceType] = Map[JPathField, TransSpec[A]]
  private type TableTransSpec1 = TableTransSpec[Source1]
  private type TableTransSpec2 = TableTransSpec[Source2]
  
  private object TableTransSpec {
    def makeTransSpec(tableTrans: TableTransSpec1): TransSpec1 = {
      val wrapped = for ((key @ JPathField(fieldName), value) <- tableTrans) yield {
        val mapped = deepMap(value) {
          case lf @ Leaf(_) =>
            DerefObjectStatic(lf, key)
        }
        
        trans.WrapObject(mapped, fieldName)
      }
      
      wrapped.foldLeft(Leaf(Source): TransSpec1) { (acc, ts) =>
        trans.ObjectConcat(acc, ts)
      }
    }
    
    private def deepMap(spec: TransSpec1)(f: PartialFunction[TransSpec1, TransSpec1]): TransSpec1 = spec match {
      case x if f isDefinedAt x => f(x)
      case x @ Leaf(_) => x
      case trans.Filter(source, pred) => trans.Filter(deepMap(source)(f), deepMap(pred)(f))
      case Scan(source, scanner) => Scan(deepMap(source)(f), scanner)
      case trans.Map1(source, f1) => trans.Map1(deepMap(source)(f), f1)
      case trans.Map2(left, right, f2) => trans.Map2(deepMap(left)(f), deepMap(right)(f), f2)
      case trans.ObjectConcat(left, right) => trans.ObjectConcat(deepMap(left)(f), deepMap(right)(f))
      case trans.ArrayConcat(left, right) => trans.ArrayConcat(deepMap(left)(f), deepMap(right)(f))
      case trans.WrapObject(source, field) => trans.WrapObject(deepMap(source)(f), field)
      case trans.WrapArray(source) => trans.WrapArray(deepMap(source)(f))
      case DerefObjectStatic(source, field) => DerefObjectStatic(deepMap(source)(f), field)
      case DerefObjectDynamic(left, right) => DerefObjectDynamic(deepMap(left)(f), deepMap(right)(f))
      case DerefArrayStatic(source, element) => DerefArrayStatic(deepMap(source)(f), element)
      case DerefArrayDynamic(left, right) => DerefArrayDynamic(deepMap(left)(f), deepMap(right)(f))
      case trans.ArraySwap(source, index) => trans.ArraySwap(deepMap(source)(f), index)
      case Typed(source, tpe) => Typed(deepMap(source)(f), tpe)
      case trans.Equal(left, right) => trans.Equal(deepMap(left)(f), deepMap(right)(f))
    }
  }
  
  private case class PendingTable(table: Table, graph: DepGraph, trans: TransSpec1)
}
