package scalapb.validate.compiler

import com.google.protobuf.ExtensionRegistry
import protocgen.{CodeGenApp, CodeGenRequest, CodeGenResponse}
import scalapb.options.Scalapb
import io.envoyproxy.pgv.validate.Validate
import protocbridge.Artifact
import com.google.protobuf.Descriptors.FileDescriptor
import scala.jdk.CollectionConverters._
import scalapb.options.Scalapb.Collection
import com.google.protobuf.Message
import scalapb.options.Scalapb.PreprocessorOutput
import scalapb.options.Scalapb.ScalaPbOptions
import java.nio.file.Files
import scalapb.options.Scalapb.FieldTransformation
import com.google.protobuf.TextFormat
import scalapb.compiler.GeneratorException
import com.google.protobuf.Descriptors.FieldDescriptor.Type
import io.envoyproxy.pgv.validate.Validate.FieldRules
import io.envoyproxy.pgv.validate.Validate.RepeatedRules
import io.envoyproxy.pgv.validate.Validate.MapRules

object ValidatePreprocessor extends CodeGenApp {
  override def registerExtensions(registry: ExtensionRegistry): Unit = {
    Scalapb.registerAllExtensions(registry)
    Validate.registerAllExtensions(registry)
    scalapb.validate.Validate.registerAllExtensions(registry)
  }

  override def suggestedDependencies: Seq[Artifact] = Seq.empty

  val NonEmptySet = Collection
    .newBuilder()
    .setType("_root_.cats.data.NonEmptySet")
    .setAdapter("_root_.scalapb.validate.cats.NonEmptySetAdapter")
    .setNonEmpty(true)
    .build()

  val NonEmptyList = Collection
    .newBuilder()
    .setType("_root_.cats.data.NonEmptyList")
    .setAdapter("_root_.scalapb.validate.cats.NonEmptyListAdapter")
    .setNonEmpty(true)
    .build()

  val NonEmptyMap = Collection
    .newBuilder()
    .setType("_root_.cats.data.NonEmptyMap")
    .setAdapter("_root_.scalapb.validate.cats.NonEmptyMapAdapter")
    .setNonEmpty(true)
    .build()

  def process(request: CodeGenRequest): CodeGenResponse = {
    val secondaryResult = new ProcessRequest(request).result()
    val b = com.google.protobuf.Any
      .pack(secondaryResult)
      .toByteArray()
    val secondaryOutputFile = scalapb.compiler.SecondaryOutputProvider
      .secondaryOutputDir(
        request.asProto
      )
      .getOrElse(
        throw new RuntimeException(
          "Secondary output dir not provided. The most likely reason is that you are using an old version of sbt-protoc/protocbridge that does not provide this information. If you are invoking this plugin directly, SECONDARY_OUTPUT_DIR must be provided as an environment variable."
        )
      )
      .toPath
      .resolve(ValidatePreprocessor.PREPROCESSOR_NAME)

    Files.write(secondaryOutputFile, b)

    CodeGenResponse.succeed(Nil)
  }

  val PREPROCESSOR_NAME = "scalapb-validate-preprocessor"
}

class ProcessRequest(req: CodeGenRequest) {

  val cache = PackageOptionsCache.from(req.allProtos)

  def processFile(file: FileDescriptor): Option[ScalaPbOptions] = {
    val b = Scalapb.ScalaPbOptions.newBuilder()

    val packageOptions = cache.get(file.getPackage())
    // Set rules done first, since Cats NonEmptySet is more specific and should override.
    val ts = (
      if (packageOptions.getUniqueToSet) SetRules else Nil
    ) ++ (
      if (packageOptions.getCatsTransforms) CatsRules else Nil
    )

    b.addAllFieldTransformations(ts.asJava)
    b.addAllFieldTransformations(
      file
        .getOptions()
        .getExtension(Scalapb.options)
        .getFieldTransformationsList()
        .asScala
        .flatMap(expandTransformation(_))
        .asJava
    )

    val result = b.build()

    if (result.getSerializedSize() != 0) Some(result) else None
  }

  def result(): PreprocessorOutput = {
    val outs = req.allProtos.map(f => (f.getName(), processFile(f))).collect {
      case (name, Some(out)) => name -> out
    }

    scalapb.options.Scalapb.PreprocessorOutput
      .newBuilder()
      .putAllOptionsByFile(outs.toMap.asJava)
      .build()
  }

  def expandTransformation(t: FieldTransformation): Seq[FieldTransformation] =
    if (!t.getWhen().hasExtension(Validate.rules)) Seq.empty
    else {
      val fieldRules = t.getWhen.getExtension(Validate.rules)
      if (fieldRules.hasRepeated() || fieldRules.hasMap()) Seq.empty
      else {
        val rep = t
          .toBuilder()
          .clearWhen()
        rep
          .getWhenBuilder()
          .setExtension(
            Validate.rules,
            FieldRules
              .newBuilder()
              .setRepeated(RepeatedRules.newBuilder.setItems(fieldRules))
              .build()
          )

        val mapKey = t
          .toBuilder()
          .clearWhen()
        if (t.getSet.hasType()) {
          mapKey.getSetBuilder().clearType()
            .setKeyType(t.getSet.getType())
        }
        mapKey
          .getWhenBuilder()
          .setExtension(
            Validate.rules,
            FieldRules
              .newBuilder()
              .setMap(MapRules.newBuilder.setKeys(fieldRules).build())
              .build()
          )

        val mapValue = t
          .toBuilder()
          .clearWhen()
        mapValue.getSetBuilder().clearType()
          .setKeyType(t.getSet.getType())
        if (t.getSet.hasType()) {
          mapValue.getSetBuilder().clearType()
            .setValueType(t.getSet.getType())
        }
        mapValue
          .getWhenBuilder()
          .setExtension(
            Validate.rules,
            FieldRules
              .newBuilder()
              .setMap(MapRules.newBuilder.setValues(fieldRules).build())
              .build()
          )

        Seq(rep.build(), mapKey.build(), mapValue.build())
      }
    }

  def fieldTransformation(s: String): FieldTransformation = {
    val er = ExtensionRegistry.newInstance()
    ValidatePreprocessor.registerExtensions(er)
    TextFormat.parse(s, er, classOf[FieldTransformation])
  }

  val SetRules = Seq(
    fieldTransformation("""when: {
          [validate.rules] {
            repeated: {unique: true}
          }
        }
        set: {
          collection_type: "_root_.scala.collection.immutable.Set"
          collection: {
            type: "_root_.scala.collection.immutable.Set"
            adapter: "_root_.scalapb.validate.SetAdapter"
          }
          [scalapb.validate.field] {
            skip_unique_check: true
          }
        }""".stripMargin)
  )

  val CatsRules =
    Seq(
      fieldTransformation("""when: {
             [validate.rules] {
               repeated: {min_items: 1}
             }
           }
           set: {
             collection: {
               type: "_root_.cats.data.NonEmptyList"
               adapter: "_root_.scalapb.validate.cats.NonEmptyListAdapter"
               non_empty: true
             }
           }"""),
      fieldTransformation("""when: {
             [validate.rules] {
               repeated: {unique: true, min_items: 1}
             }
           }
           set: {
             collection: {
               type: "_root_.cats.data.NonEmptySet"
               adapter: "_root_.scalapb.validate.cats.NonEmptySetAdapter"
               non_empty: true
             }
             [scalapb.validate.field] {
               skip_unique_check: true
             }
           }"""),
      fieldTransformation("""when: {
             [validate.rules] {
               map: {min_pairs: 1}
             }
           }
           set: {
             collection: {
               type: "_root_.cats.data.NonEmptyMap"
               adapter: "_root_.scalapb.validate.cats.NonEmptyMapAdapter"
               non_empty: true
             }
           }""")
    )
}

object ProcessRequest {
  private[compiler] def matchPresence[T <: Message](
      msg: T,
      pattern: T
  ): Boolean = {
    val patternFields = pattern.getAllFields().keySet()
    msg.getAllFields().keySet().containsAll(patternFields) &&
    patternFields.asScala.forall { fd =>
      if (fd.isRepeated())
        throw new GeneratorException(
          "Presence matching on repeated fields is not supported"
        )
      else if (fd.getType() == Type.MESSAGE)
        msg.hasField(fd) && matchPresence(
          msg.getField(fd).asInstanceOf[Message],
          pattern.getField(fd).asInstanceOf[Message]
        )
      else
        msg.hasField(fd)
    }
  }

  private[compiler] def fieldByPath(message: Message, path: String): String =
    if (path.isEmpty()) throw new GeneratorException("Got an empty path")
    else
      fieldByPath(message, path.split('.').toList, path) match {
        case Left(error)  => throw new GeneratorException(error)
        case Right(value) => value
      }

  private[compiler] def fieldByPath(
      message: Message,
      path: List[String],
      allPath: String
  ): Either[String, String] =
    for {
      fieldName <- path.headOption.toRight("Got an empty path")
      fd <- Option(message.getDescriptorForType().findFieldByName(fieldName))
        .toRight(
          s"Could not find field named $fieldName when looking for $allPath"
        )
      _ <-
        if (fd.isRepeated()) Left("Repeated fields are not supported")
        else Right(())
      v = message.getField(fd)
      res <- path match {
        case _ :: Nil => Right(v.toString())
        case _ :: tail =>
          if (fd.getType() == Type.MESSAGE)
            fieldByPath(v.asInstanceOf[Message], tail, allPath)
          else
            Left(
              s"Type ${fd.getType.toString} does not have a field ${tail.head} in $allPath"
            )
        case Nil => Left("Unexpected empty path")
      }
    } yield res

  // Substitutes $(path) references in string fields within msg with values coming from the data
  // message
  private[compiler] def interpolateStrings[T <: Message](
      msg: T,
      data: Message
  ): T = {
    val b = msg.toBuilder()
    for {
      (field, value) <- msg.getAllFields().asScala
    } field.getType() match {
      case Type.STRING if (!field.isRepeated()) =>
        b.setField(field, interpolate(value.asInstanceOf[String], data))
      case Type.MESSAGE =>
        if (field.isRepeated())
          b.setField(
            field,
            value
              .asInstanceOf[java.util.List[Message]]
              .asScala
              .map(interpolateStrings(_, data))
              .asJava
          )
        else
          b.setField(
            field,
            interpolateStrings(value.asInstanceOf[Message], data)
          )
      case _ =>
    }
    b.build().asInstanceOf[T]
  }

  val FieldPath: java.util.regex.Pattern =
    raw"[$$]\(([a-zA-Z0-9_.]*)\)".r.pattern

  // Interpolates paths in the given string with values coming from the data message
  private[compiler] def interpolate(value: String, data: Message): String =
    FieldPath.matcher(value).replaceAll(m => fieldByPath(data, m.group(1)))
}
