package com.gu.scanamo

trait RequestModifier[T] {
  def apply(t: T): T
}

object RequestModifier {
  def nullModifier[T] = new RequestModifier[T] {
    override def apply(t: T): T = t
  }
}
