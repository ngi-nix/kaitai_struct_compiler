package io.kaitai.struct.translators

import io.kaitai.struct.exprlang.Ast
import io.kaitai.struct.exprlang.Ast.{identifier, expr}
import io.kaitai.struct.exprlang.DataType._

trait TypeProvider {
  def determineType(parentType: String, name: String): BaseType
  def determineType(name: String): BaseType
}

class TypeMismatchError(msg: String) extends RuntimeException(msg)

abstract class BaseTranslator(val provider: TypeProvider) {
  def translate(v: Ast.expr): String = {
    v match {
      case Ast.expr.Num(n) =>
        doIntLiteral(n)
      case Ast.expr.Str(s) =>
        doStringLiteral(s)
      case Ast.expr.Name(name: Ast.identifier) =>
        doLocalName(name.name)
      case Ast.expr.UnaryOp(op: Ast.unaryop, v: Ast.expr) =>
        s"${unaryOp(op)}${translate(v)}"
      case Ast.expr.Compare(left: Ast.expr, op: Ast.cmpop, right: Ast.expr) =>
        (detectType(left), detectType(right)) match {
          case (_: IntType, _: IntType) =>
            doIntCompareOp(left, op, right)
          case (_: StrType, _: StrType) =>
            doStrCompareOp(left, op, right)
          case (ltype, rtype) =>
            throw new RuntimeException(s"can't compare ${ltype} and ${rtype}")
        }
      case Ast.expr.BinOp(left: Ast.expr, op: Ast.operator, right: Ast.expr) =>
        (detectType(left), detectType(right), op) match {
          case (_: IntType, _: IntType, _) =>
            intBinOp(left, op, right)
          case (_: StrType, _: StrType, Ast.operator.Add) =>
            strConcat(left, right)
          case (ltype, rtype, _) =>
            throw new RuntimeException(s"can't do ${ltype} ${op} ${rtype}")
        }
      case Ast.expr.BoolOp(op: Ast.boolop, values: Seq[Ast.expr]) =>
        doBooleanOp(op, values)
      case Ast.expr.IfExp(condition: expr, ifTrue: expr, ifFalse: expr) =>
        doIfExp(condition, ifTrue, ifFalse)
      case Ast.expr.Subscript(container: Ast.expr, idx: Ast.expr) =>
        doSubscript(container, idx)
      case Ast.expr.Attribute(value: Ast.expr, attr: Ast.identifier) =>
        val valType = detectType(value)
        valType match {
          case _: UserType =>
            userTypeField(value, attr.name)
          case _: StrType =>
            attr.name match {
              case "length" => strLength(value)
            }
          case _: IntType =>
            throw new RuntimeException(s"don't know how to call anything on ${valType}")
        }
      case Ast.expr.Call(func: Ast.expr, args: Seq[Ast.expr]) =>
        func match {
          case Ast.expr.Attribute(obj: Ast.expr, methodName: Ast.identifier) =>
            val objType = detectType(obj)
            (objType, methodName.name) match {
              case (_: StrType, "substring") => strSubstring(obj, args(0), args(1))
              case _ => throw new RuntimeException(s"don't know how to call method '$methodName' of object type '$objType'")
            }
        }
    }
  }

  def intBinOp(left: Ast.expr, op: Ast.operator, right: Ast.expr) = {
    s"(${translate(left)} ${binOp(op)} ${translate(right)})"
  }

  def binOp(op: Ast.operator): String = {
    op match {
      case Ast.operator.Add => "+"
      case Ast.operator.Sub => "-"
      case Ast.operator.Mult => "*"
      case Ast.operator.Div => "/"
    }
  }

  def doIntCompareOp(left: Ast.expr, op: Ast.cmpop, right: Ast.expr) = {
    s"${translate(left)} ${cmpOp(op)} ${translate(right)}"
  }

  def doStrCompareOp(left: Ast.expr, op: Ast.cmpop, right: Ast.expr) = {
    s"${translate(left)} ${cmpOp(op)} ${translate(right)}"
  }

  def cmpOp(op: Ast.cmpop): String = {
    op match {
      case Ast.cmpop.Lt => "<"
      case Ast.cmpop.LtE => "<="
      case Ast.cmpop.Gt => ">"
      case Ast.cmpop.GtE => ">="
      case Ast.cmpop.Eq => "=="
      case Ast.cmpop.NotEq => "!="
    }
  }

  def doBooleanOp(op: Ast.boolop, values: Seq[Ast.expr]): String = {
    val opStr = s" ${booleanOp(op)} "
    values.map(translate).mkString(opStr)
  }

  def booleanOp(op: Ast.boolop) = op match {
    case Ast.boolop.Or => "||"
    case Ast.boolop.Or => "&&"
  }

  def unaryOp(op: Ast.unaryop) = op match {
    case Ast.unaryop.Invert => "~"
    case Ast.unaryop.Minus => "-"
    case Ast.unaryop.Not => "!"
  }

  def doSubscript(container: expr, idx: expr): String
  def doIfExp(condition: expr, ifTrue: expr, ifFalse: expr): String

  // Literals
  def doIntLiteral(n: Any): String = n.toString
  def doStringLiteral(s: String): String = "\"" + s + "\""

  def doLocalName(s: String): String = doName(s)
  def doName(s: String): String
  def userTypeField(value: expr, attrName: String): String =
    s"${translate(value)}.${doName(attrName)}"

  // Predefined methods of various types
  def strConcat(left: Ast.expr, right: Ast.expr): String = s"${translate(left)} + ${translate(right)}"
  def strToInt(s: Ast.expr, base: Ast.expr): String
  def strLength(s: Ast.expr): String
  def strSubstring(s: Ast.expr, from: Ast.expr, to: Ast.expr): String

  def detectType(v: Ast.expr): BaseType = {
    v match {
      case Ast.expr.Num(_) => CalcIntType
      case Ast.expr.Str(_) => CalcStrType
      case Ast.expr.Name(name: Ast.identifier) => provider.determineType(name.name)
      case Ast.expr.UnaryOp(op: Ast.unaryop, v: Ast.expr) =>
        val t = detectType(v)
        t match {
          case _: IntType => t
          case _ => throw new RuntimeException(s"unable to apply unary operator ${op} to ${t}")
        }
      case Ast.expr.Compare(left: Ast.expr, op: Ast.cmpop, right: Ast.expr) =>
        val ltype = detectType(left)
        val rtype = detectType(right)
        if (ltype == rtype) {
          BooleanType
        } else {
          throw new RuntimeException(s"can't compare ${ltype} and ${rtype}")
        }
      case Ast.expr.BinOp(left: Ast.expr, op: Ast.operator, right: Ast.expr) =>
        (detectType(left), detectType(right), op) match {
          case (_: IntType, _: IntType, _) => CalcIntType
          case (_: StrType, _: StrType, Ast.operator.Add) => CalcStrType
          case (ltype, rtype, _) =>
            throw new RuntimeException(s"can't apply operator ${op} to ${ltype} and ${rtype}")
        }
      case Ast.expr.BoolOp(op: Ast.boolop, values: Seq[Ast.expr]) =>
        values.foreach(v => {
          val t = detectType(v)
          if (t != BooleanType) {
            throw new RuntimeException(s"unable to use ${t} argument in ${op} boolean expression")
          }
        })
        BooleanType
      case Ast.expr.IfExp(condition: expr, ifTrue: expr, ifFalse: expr) =>
        detectType(condition) match {
          case BooleanType =>
            val trueType = detectType(ifTrue)
            val falseType = detectType(ifFalse)
            if (trueType == falseType) {
              trueType
            } else {
              throw new TypeMismatchError(s"ternary operator with different output types: ${trueType} vs ${falseType}")
            }
          case other => throw new TypeMismatchError(s"unable to switch over ${other}")
        }
      case Ast.expr.Subscript(container: Ast.expr, idx: Ast.expr) =>
        detectType(container) match {
          case ArrayType(elType: BaseType) =>
            detectType(idx) match {
              case _: IntType => elType
              case idxType => throw new TypeMismatchError(s"unable to index an array using ${idxType}")
            }
          case cntType => throw new TypeMismatchError(s"unable to apply operation [] to ${cntType}")
        }
      case Ast.expr.Attribute(value: Ast.expr, attr: Ast.identifier) =>
        val valType = detectType(value)
        valType match {
          case t: UserType =>
            provider.determineType(t.name, attr.name)
          case _: StrType =>
            attr.name match {
              case "length" => CalcIntType
              case _ => throw new RuntimeException(s"called invalid attribute '${attr.name}' on expression of type ${valType}")
            }
          case _ =>
            throw new RuntimeException(s"don't know how to call anything on ${valType}")
        }
      case Ast.expr.Call(func: Ast.expr, args: Seq[Ast.expr]) =>
        func match {
          case Ast.expr.Attribute(obj: Ast.expr, methodName: Ast.identifier) =>
            val objType = detectType(obj)
            (objType, methodName.name) match {
              case (_: StrType, "substring") => CalcStrType
              case _ => throw new RuntimeException(s"don't know how to call method '$methodName' of object type '$objType'")
            }
        }
    }
  }
}
