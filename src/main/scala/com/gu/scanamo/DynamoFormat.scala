package com.gu.scanamo

import cats.Show
import cats.data._
import cats.std.list._
import cats.std.map._
import cats.syntax.traverse._
import cats.syntax.apply._
import com.amazonaws.services.dynamodbv2.model.AttributeValue
import shapeless._
import shapeless.labelled._
import simulacrum.typeclass
import collection.convert.decorateAll._

/**
  * Type class for defining serialisation to and from
  * DynamoDB's `AttributeValue`
  *
  * {{{
  * >>> val mapF = DynamoFormat[Map[String, List[Int]]]
  * >>> mapF.read(mapF.write(Map("foo" -> List(1, 2, 3), "bar" -> List(3, 2, 1))))
  * Valid(Map(foo -> List(1, 2, 3), bar -> List(3, 2, 1)))
  * }}}
  *
  * Also supports automatic derivation for case classes
  *
  * {{{
  * >>> case class Farm(animals: List[String])
  * >>> case class Farmer(name: String, age: Long, farm: Farm)
  * >>> val farmerF = DynamoFormat[Farmer]
  * >>> farmerF.read(farmerF.write(Farmer("McDonald", 156L, Farm(List("sheep", "cow")))))
  * Valid(Farmer(McDonald,156,Farm(List(sheep, cow))))
  * }}}
  *
  * Problems reading a value are detailed
  * {{{
  * >>> case class Developer(name: String, age: String, problems: Int)
  * >>> val invalid = DynamoFormat[Farmer].read(DynamoFormat[Developer].write(Developer("Alice", "none of your business", 99)))
  * Invalid(OneAnd(PropertyReadError(age,OneAnd(NoPropertyOfType(N),List())),List(PropertyReadError(farm,OneAnd(MissingProperty,List())))))
  * >>> invalid.leftMap(DynamoReadError.describeAll(_))
  * Invalid('age': not of type: 'N', 'farm': missing)
  * }}}
  *
  * Optional properties are defaulted to None
  * {{{
  * >>> case class LargelyOptional(a: Option[String], b: Option[String])
  * >>> DynamoFormat[LargelyOptional].read(DynamoFormat[Map[String, String]].write(Map("b" -> "X")))
  * Valid(LargelyOptional(None,Some(X)))
  * }}}
  */
@typeclass trait DynamoFormat[T] {
  def read(av: AttributeValue): ValidatedNel[DynamoReadError, T]
  def write(t: T): AttributeValue
  def default: Option[T] = None
}

object DynamoFormat extends DerivedDynamoFormat {
  def attribute[T](
    decode: AttributeValue => T, propertyType: String)(
    encode: AttributeValue => T => AttributeValue
  ): DynamoFormat[T] = {
    new DynamoFormat[T] {
      override def read(av: AttributeValue): ValidatedNel[DynamoReadError, T] =
        Validated.fromOption(Option(decode(av)), NonEmptyList(NoPropertyOfType(propertyType)))
      override def write(t: T): AttributeValue =
        encode(new AttributeValue())(t)
    }
  }
  def xmap[T, U](f: DynamoFormat[T])(r: T => ValidatedNel[DynamoReadError, U])(w: U => T) = new DynamoFormat[U] {
    override def read(item: AttributeValue): ValidatedNel[DynamoReadError, U] = f.read(item).andThen(r)
    override def write(t: U): AttributeValue = f.write(w(t))
  }

  /**
    * prop> (s: String) => DynamoFormat[String].read(DynamoFormat[String].write(s)) == cats.data.Validated.valid(s)
    */
  implicit val stringFormat = attribute(_.getS, "S")(_.withS)

  implicit val javaBooleanFormat = attribute[java.lang.Boolean](_.getBOOL, "BOOL")(_.withBOOL)

  /**
    * prop> (b: Boolean) => DynamoFormat[Boolean].read(DynamoFormat[Boolean].write(b)) == cats.data.Validated.valid(b)
    */
  implicit val booleanFormat = xmap(javaBooleanFormat)(b => Validated.valid(Boolean.unbox(b)))(Boolean.box)


  val numFormat = attribute(_.getN, "N")(_.withN)
  def coerce[N](f: String => N): String => ValidatedNel[DynamoReadError, N] = s =>
    Validated.catchOnly[NumberFormatException](f(s)).leftMap(TypeCoercionError(_)).toValidatedNel
  /**
    * prop> (l: Long) => DynamoFormat[Long].read(DynamoFormat[Long].write(l)) == cats.data.Validated.valid(l)
    */
  implicit val longFormat = xmap(numFormat)(coerce(_.toLong))(_.toString)
  /**
    * prop> (i: Int) => DynamoFormat[Int].read(DynamoFormat[Int].write(i)) == cats.data.Validated.valid(i)
    */
  implicit val intFormat = xmap(numFormat)(coerce(_.toInt))(_.toString)


  val javaListFormat = attribute(_.getL, "L")(_.withL)
  /**
    * prop> (l: List[String]) => DynamoFormat[List[String]].read(DynamoFormat[List[String]].write(l)) == cats.data.Validated.valid(l)
    */
  implicit def listFormat[T](implicit f: DynamoFormat[T]): DynamoFormat[List[T]] =
    xmap(javaListFormat)(_.asScala.toList.traverseU(f.read))(_.map(f.write).asJava)

  val javaMapFormat = attribute(_.getM, "M")(_.withM)
  /**
    * prop> (m: Map[String, Int]) => DynamoFormat[Map[String, Int]].read(DynamoFormat[Map[String, Int]].write(m)) == cats.data.Validated.valid(m)
    */
  implicit def mapFormat[V](implicit f: DynamoFormat[V]): DynamoFormat[Map[String, V]] =
    xmap(javaMapFormat)(_.asScala.toMap.traverseU(f.read))(_.mapValues(f.write).asJava)

  /**
    * prop> (o: Option[Long]) => DynamoFormat[Option[Long]].read(DynamoFormat[Option[Long]].write(o)) == cats.data.Validated.valid(o)
    */
  implicit def optionFormat[T](implicit f: DynamoFormat[T]) = new DynamoFormat[Option[T]] {
    def read(av: AttributeValue): ValidatedNel[DynamoReadError, Option[T]] = {
      // Yea, `isNull` can return null
      if (Option(av.isNULL).map(_.booleanValue).getOrElse(false)) {
        Validated.valid(None)
      } else {
        f.read(av).map(t => Some(t))
      }
    }

    def write(t: Option[T]): AttributeValue = t.map(f.write).getOrElse(new AttributeValue().withNULL(true))
    override val default = Some(None)
  }
}

trait DerivedDynamoFormat {
  implicit val hnil: DynamoFormat[HNil] =
    new DynamoFormat[HNil] {
      def read(av: AttributeValue) = Validated.valid(HNil)
      def write(t: HNil): AttributeValue = new AttributeValue().withM(Map.empty.asJava)
    }

  implicit def hcons[K <: Symbol, V, T <: HList](implicit
    headFormat: Lazy[DynamoFormat[V]],
    tailFormat: Lazy[DynamoFormat[T]],
    fieldWitness: Witness.Aux[K]
  ): DynamoFormat[FieldType[K, V] :: T] =
    new DynamoFormat[FieldType[K, V] :: T] {
      def read(av: AttributeValue): ValidatedNel[DynamoReadError, FieldType[K, V] :: T] = {
        val fieldName = fieldWitness.value.name

        val possibleValue = av.getM.asScala.get(fieldName).map(headFormat.value.read).orElse(headFormat.value.default.map(Validated.valid))

        val validatedValue = possibleValue.getOrElse(Validated.invalidNel[DynamoReadError, V](MissingProperty))

        def withPropertyError(x: Validated[NonEmptyList[DynamoReadError], V]): Validated[NonEmptyList[DynamoReadError], V] =
          x.leftMap(e => NonEmptyList(PropertyReadError(fieldName, e)))

        val head: ValidatedNel[DynamoReadError, FieldType[K, V]] = withPropertyError(validatedValue).map(field[K](_))
        val tail = tailFormat.value.read(av)

        head.map2(tail)(_ :: _)
      }
      def write(t: FieldType[K, V] :: T): AttributeValue = {
        val tailValue = tailFormat.value.write(t.tail)
        tailValue.withM((tailValue.getM.asScala + (fieldWitness.value.name -> headFormat.value.write(t.head))).asJava)
      }
    }

  implicit def generic[T, R](implicit gen: LabelledGeneric.Aux[T, R], formatR: Lazy[DynamoFormat[R]]): DynamoFormat[T] =
    new DynamoFormat[T] {
      def read(av: AttributeValue): ValidatedNel[DynamoReadError, T] = formatR.value.read(av).map(gen.from)
      def write(t: T): AttributeValue = formatR.value.write(gen.to(t))
    }
}

sealed trait DynamoReadError
case class PropertyReadError(name: String, problem: NonEmptyList[DynamoReadError]) extends DynamoReadError
case class NoPropertyOfType(propertyType: String) extends DynamoReadError
case class TypeCoercionError(e: Exception) extends DynamoReadError
case object MissingProperty extends DynamoReadError

object DynamoReadError {
  def describeAll(l: NonEmptyList[DynamoReadError]): String = l.unwrap.map(describe(_)).mkString(", ")
  def describe(d: DynamoReadError): String =  d match {
    case PropertyReadError(name, problem) => s"'${name}': ${describeAll(problem)}"
    case NoPropertyOfType(propertyType) => s"not of type: '$propertyType'"
    case TypeCoercionError(e) => s"could not be converted to desired type: $e"
    case MissingProperty => "missing"
  }
}