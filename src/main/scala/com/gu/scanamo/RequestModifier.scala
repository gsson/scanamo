package com.gu.scanamo

import com.amazonaws.services.dynamodbv2.model.GetItemRequest

trait RequestModifier[T] {
  def apply(t: T): T
}

object RequestModifier {
  def nullModifier[T] = new RequestModifier[T] {
    override def apply(t: T): T = t
  }
}

object GetItem {
  type Modifier = RequestModifier[GetItemRequest]
  object WithConsistentRead extends Modifier {
    override def apply(t: GetItemRequest): GetItemRequest = t.withConsistentRead(true)
  }
}