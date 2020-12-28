package scalapb.validate.cats

import cats.data.{NonEmptyList, NonEmptyMap, NonEmptySet}
import scalapb.descriptors.{PRepeated, Reads, ReadsException}
import scala.collection.immutable.SortedSet
import scala.collection.immutable.SortedMap
import scala.collection.mutable.Builder
import com.google.protobuf.InvalidProtocolBufferException

object NonEmptyListAdapter {
  def foreach[T](coll: NonEmptyList[T])(f: T => Unit) = coll.map(f)

  def empty[T]: NonEmptyList[T] = throw new InvalidProtocolBufferException(
    "NonEmptyList must be non-empty"
  )

  def reads[T](implicit reads: Reads[T]): Reads[NonEmptyList[T]] =
    Reads[NonEmptyList[T]] {
      case PRepeated(value) => fromIterator(value.map(reads.read).iterator)
      case _                => throw new ReadsException("Expected PRepeated")
    }

  def newBuilder[T]: Builder[T, NonEmptyList[T]] = List
    .newBuilder[T]
    .mapResult(list =>
      NonEmptyList
        .fromList(list)
        .getOrElse(
          throw new InvalidProtocolBufferException(
            "NonEmptyList must be non-empty"
          )
        )
    )

  def concat[T](first: NonEmptyList[T], second: Iterable[T]) =
    first ++ second.toList

  def fromIterator[T](x: Iterator[T]): NonEmptyList[T] =
    (newBuilder[T] ++= x).result()

  def toIterator[T](value: NonEmptyList[T]): Iterator[T] = value.iterator

  def size[T](value: NonEmptyList[T]): Int = value.size
}

object NonEmptySetAdapter {
  def foreach[T](coll: NonEmptySet[T])(f: T => Unit) = coll.map(f)

  def empty[T]: NonEmptySet[T] = throw new InvalidProtocolBufferException(
    "NonEmptySet must be non-empty"
  )

  def reads[T: Ordering](implicit reads: Reads[T]): Reads[NonEmptySet[T]] =
    Reads[NonEmptySet[T]] {
      case PRepeated(value) => fromIterator(value.map(reads.read).iterator)
      case _                => throw new ReadsException("Expected PRepeated")
    }

  def newBuilder[T: Ordering]: Builder[T, NonEmptySet[T]] = SortedSet
    .newBuilder[T]
    .mapResult(set =>
      NonEmptySet
        .fromSet(set)
        .getOrElse(
          throw new InvalidProtocolBufferException(
            "NonEmptySet must be non-empty"
          )
        )
    )

  def concat[T](first: NonEmptySet[T], second: Iterable[T]) =
    NonEmptySet.fromSetUnsafe(first.toSortedSet ++ second.toSet)

  def fromIterator[T: Ordering](x: Iterator[T]): NonEmptySet[T] =
    (newBuilder[T] ++= x).result()

  def toIterator[T](value: NonEmptySet[T]): Iterator[T] =
    value.toSortedSet.iterator
  Map[Int, Int]().keys

  def size[T](value: NonEmptySet[T]): Int = value.length
}

object NonEmptyMapAdapter {
  def foreach[K, V](coll: NonEmptyMap[K, V])(f: ((K, V)) => Unit) =
    coll.toSortedMap.foreach(f)

  def empty[K, V]: NonEmptyMap[K, V] = throw new InvalidProtocolBufferException(
    "NonEmptyMap must be non-empty"
  )

  def newBuilder[K: Ordering, V]: Builder[(K, V), NonEmptyMap[K, V]] = SortedMap
    .newBuilder[K, V]
    .mapResult(map =>
      NonEmptyMap
        .fromMap(map)
        .getOrElse(
          throw new InvalidProtocolBufferException(
            "NonEmptyMap must be non-empty"
          )
        )
    )

  def concat[K, V](first: NonEmptyMap[K, V], second: Iterable[(K, V)]) =
    NonEmptyMap.fromMapUnsafe(first.toSortedMap ++ second.toMap)

  def fromIterator[K: Ordering, V](x: Iterator[(K, V)]): NonEmptyMap[K, V] =
    (newBuilder[K, V] ++= x).result()

  def toIterator[K, V](value: NonEmptyMap[K, V]): Iterator[(K, V)] =
    value.toSortedMap.iterator

  def size[K, V](value: NonEmptyMap[K, V]): Int = value.length
}