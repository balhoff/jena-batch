package org.renci.jenabatch

import zio.test._

object ConfigSpec extends ZIOSpecDefault {

  // case-app's ArgParser.apply signature is (prev: Option[T], arg: String).
  // For these tests there's never a prior value to accumulate, so we always
  // pass None.
  override def spec = suite("CLI argument parsers")(
    test("ShexInput parser splits on '=' into three non-empty parts") {
      val parsed = JenaBatchConfig.shexInputParser.apply(None, "gpad=foo.shex=bar.shapeMap")
      assertTrue(parsed == Right(ShexInput("gpad", "foo.shex", "bar.shapeMap")))
    },
    test("ShexInput parser rejects two-part input") {
      val parsed = JenaBatchConfig.shexInputParser.apply(None, "gpad=foo.shex")
      assertTrue(parsed.isLeft)
    },
    test("ShexInput parser rejects empty id") {
      val parsed = JenaBatchConfig.shexInputParser.apply(None, "=foo.shex=bar.shapeMap")
      assertTrue(parsed.isLeft)
    },
    test("ShexContextInput parser splits on first '=' only") {
      val parsed = JenaBatchConfig.shexContextInputParser.apply(None, "gpad=path/with=equals.ttl")
      assertTrue(parsed == Right(ShexContextInput("gpad", "path/with=equals.ttl")))
    },
    test("ShexContextInput parser rejects empty id") {
      val parsed = JenaBatchConfig.shexContextInputParser.apply(None, "=context.ttl")
      assertTrue(parsed.isLeft)
    },
    test("QueryInput parser splits on first '=' only") {
      val parsed = JenaBatchConfig.queryInputParser.apply(None, "disconnected=path/to/q.rq")
      assertTrue(parsed == Right(QueryInput("disconnected", "path/to/q.rq")))
    },
    test("QueryInput parser rejects empty id") {
      val parsed = JenaBatchConfig.queryInputParser.apply(None, "=foo.rq")
      assertTrue(parsed.isLeft)
    },
    test("QueryInput parser rejects no '='") {
      val parsed = JenaBatchConfig.queryInputParser.apply(None, "just-a-path.rq")
      assertTrue(parsed.isLeft)
    },
    test("BoolValue parser accepts true") {
      assertTrue(JenaBatchConfig.boolParser.apply(None, "true") == Right(TrueValue))
    },
    test("BoolValue parser accepts false") {
      assertTrue(JenaBatchConfig.boolParser.apply(None, "false") == Right(FalseValue))
    },
    test("BoolValue parser accepts 1 / 0") {
      assertTrue(
        JenaBatchConfig.boolParser.apply(None, "1") == Right(TrueValue) &&
          JenaBatchConfig.boolParser.apply(None, "0") == Right(FalseValue)
      )
    },
    test("BoolValue parser rejects garbage") {
      assertTrue(JenaBatchConfig.boolParser.apply(None, "yes").isLeft)
    }
  )

}
