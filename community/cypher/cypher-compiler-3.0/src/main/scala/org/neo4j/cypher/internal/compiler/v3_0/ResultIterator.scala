/*
 * Copyright (c) 2002-2016 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.compiler.v3_0

import org.neo4j.cypher.internal.frontend.v3_0.CypherException
import org.neo4j.cypher.internal.frontend.v3_0.helpers.Eagerly

import scala.collection.immutable

trait ResultIterator extends Iterator[immutable.Map[String, Any]] {
  def toEager: EagerResultIterator
  def wasMaterialized: Boolean
  def close()
}

class EagerResultIterator(result: ResultIterator) extends ResultIterator {
  override val toList = result.toList
  private val inner = toList.iterator

  def toEager: EagerResultIterator = this

  def wasMaterialized: Boolean = true

  def hasNext = inner.hasNext

  def next() = inner.next()

  def close() { result.close() }
}

class ClosingIterator(inner: Iterator[collection.Map[String, Any]],
                      closer: TaskCloser,
                      exceptionDecorator: CypherException => CypherException) extends ResultIterator {

  lazy val still_has_relationships = "Node record Node\\[(\\d),.*] still has relationships".r

  def toEager = new EagerResultIterator(this)

  def wasMaterialized: Boolean = isEmpty

  def hasNext: Boolean = failIfThrows {
    if (!closer.isClosed) {
      val innerHasNext: Boolean = inner.hasNext
      if (!innerHasNext) {
        close(success = true)
      }
      innerHasNext
    } else {
      false
    }
  }

  def next(): Map[String, Any] = failIfThrows {
    if (closer.isClosed) return Iterator.empty.next()

    val value = inner.next()
    val result = Eagerly.immutableMapValues(value, materialize)
    result
  }

  // TODO: Get rid of this in favor of using ScalaRuntimeValueConverte
  private def materialize(v: Any): Any = v match {
    case (x: Stream[_])   => x.map(materialize).toList
    case (x: Map[_, _])   => Eagerly.immutableMapValues(x, materialize)
    case (x: Iterable[_]) => x.map(materialize)
    case x => x
  }

  def close() {
    close(success = true)
  }

  def close(success: Boolean) = decoratedCypherException({
    closer.close(success)
  })

  private def failIfThrows[U](f: => U): U = decoratedCypherException({
    try {
      f
    } catch {
      case t: Throwable =>
        close(success = false)
        throw t
    }
  })

  private def decoratedCypherException[U](f: => U): U = try {
    f
  } catch {
    case e: CypherException =>
      throw exceptionDecorator(e)
  }
}
